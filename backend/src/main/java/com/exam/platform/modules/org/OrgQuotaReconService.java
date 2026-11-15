package com.exam.platform.modules.org;

import com.exam.platform.modules.entitlement.entity.EntitlementLedger;
import com.exam.platform.modules.entitlement.mapper.EntitlementLedgerMapper;
import com.exam.platform.modules.org.dto.QuotaBatchMismatch;
import com.exam.platform.modules.org.dto.QuotaMismatch;
import com.exam.platform.modules.org.dto.QuotaReconReport;
import com.exam.platform.modules.org.entity.SysOrgQuota;
import com.exam.platform.modules.org.entity.SysOrgQuotaConsume;
import com.exam.platform.modules.org.mapper.QuotaNetRow;
import com.exam.platform.modules.org.mapper.SysOrgQuotaConsumeMapper;
import com.exam.platform.modules.org.mapper.SysOrgQuotaMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 机构额度对账，分两层。
 *
 * <p>第一层（账户级）：账户 {@code used_amount} 与发放流水净额必须相等。抓「扣减/退款与记账不同步」。</p>
 * <p>第二层（批次级）：发放流水净额与 {@code entitlement_ledger} 里该批次的实际入账金额必须相等。
 * 抓「钱扣了但权益没发出去」——第一层对此完全无感，因为两本账的批次号一致。</p>
 *
 * <p>跨租户：对账是平台级运维动作，跑在无登录态的系统线程里，只看平台租户会漏掉其它租户
 * （详见各 mapper 上的 {@code @InterceptorIgnore}）。</p>
 *
 * <h3>为什么两层的查询范围不一样</h3>
 * <p>账户上的 {@code used_amount} 是<b>累计值</b>，只能和累计的流水净额比：给它加时间窗，
 * 会把「窗口之前发生的消耗」全部算成差异，产出一堆假告警。所以账户级用全量聚合，
 * 但只发一条 {@code GROUP BY} 查询（而不是「列账户 + 逐个 SUM」的 N+1）。</p>
 * <p>批次级的每一行在写入后就不再变化，天然可以按时间窗增量核查——这正是让对账能长期
 * 跑下去的关键：流水只增不删，全表扫迟早会撞上语句超时，而<b>超时的对账等于没对账</b>。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrgQuotaReconService {

    /** 组织批量发放的 event_id 前缀，形如 {@code ORG-<batchNo>-<userId>}。 */
    private static final String ORG_EVENT_PREFIX = "ORG-";

    /**
     * 台账时间窗的两侧留白。
     *
     * <p>流水与台账在同一事务里写，但 {@code created_at} 是对象构造时刻（先逐人写台账、
     * 最后写流水），批次刚好落在窗口边界时可能只有一侧被选中，从而误报差异。
     * 留一小时的余量足以覆盖同事务内的写入间隔，代价只是多扫一小段索引。</p>
     */
    private static final long WINDOW_MARGIN_MINUTES = 60L;

    private final SysOrgQuotaMapper quotaMapper;
    private final SysOrgQuotaConsumeMapper consumeMapper;
    private final EntitlementLedgerMapper ledgerMapper;

    /** 第一层：账户已用 vs 全量流水净额；返回不一致明细，空列表表示全部对得上。 */
    public List<QuotaMismatch> reconcileAccounts() {
        Map<Long, Long> netByQuota = new HashMap<>();
        for (QuotaNetRow row : consumeMapper.sumNetGrouped()) {
            netByQuota.put(row.getQuotaId(), row.getNet() == null ? 0L : row.getNet());
        }

        List<QuotaMismatch> mismatches = new ArrayList<>();
        for (SysOrgQuota quota : quotaMapper.selectAllCrossTenant()) {
            long used = quota.getUsedAmount() == null ? 0L : quota.getUsedAmount();
            long ledgerNet = netByQuota.getOrDefault(quota.getId(), 0L);
            if (used != ledgerNet) {
                mismatches.add(new QuotaMismatch(quota.getOrgId(), quota.getId(),
                        quota.getEntitlementType(), used, ledgerNet, used - ledgerNet));
            }
        }
        return mismatches;
    }

    /** 第二层：最近 {@code lookbackDays} 天的流水净额 vs 台账实际到账；返回不一致明细。 */
    public List<QuotaBatchMismatch> reconcileBatches(int lookbackDays) {
        LocalDateTime to = LocalDateTime.now();
        LocalDateTime from = to.minusDays(Math.max(1, lookbackDays));
        return reconcileBatches(from, to);
    }

    /** 第二层（显式时间窗），便于排障时只核某一段。 */
    public List<QuotaBatchMismatch> reconcileBatches(LocalDateTime from, LocalDateTime to) {
        return compareBatches(
                consumeMapper.selectCrossTenantBetween(from, to),
                ledgerMapper.selectOrgGrantEventsBetween(
                        from.minusMinutes(WINDOW_MARGIN_MINUTES), to.plusMinutes(WINDOW_MARGIN_MINUTES)));
    }

    /**
     * 第二层的全量版本：不设时间窗，把开工至今所有批次都核一遍。
     *
     * <p>存在的意义是「怀疑历史遗留问题时用一次」。日常绝不能走这条路：台账只增不删，
     * 全量扫的耗时随运营时间线性增长，最终会撞上 {@code default-statement-timeout}
     * ——一次超时比对账失败更糟，因为它会让人以为「核过了，没问题」。</p>
     *
     * <p>如果时间窗版本报出差异，而你又想知道「是只有这一批，还是历史上一直有」，
     * 全量版本回答的就是这个问题。</p>
     */
    public List<QuotaBatchMismatch> reconcileAllBatches() {
        return compareBatches(consumeMapper.selectAllCrossTenant(), ledgerMapper.selectOrgGrantEvents());
    }

    /** 一次对账：两层都跑，返回可直接给运维看的报告。 */
    public QuotaReconReport recon(int lookbackDays, boolean fullScan) {
        List<QuotaMismatch> accountMismatches = reconcileAccounts();
        List<QuotaBatchMismatch> batchMismatches;
        LocalDateTime from = null;
        LocalDateTime to = null;
        if (fullScan) {
            batchMismatches = reconcileAllBatches();
        }
        else {
            to = LocalDateTime.now();
            from = to.minusDays(Math.max(1, lookbackDays));
            batchMismatches = reconcileBatches(from, to);
        }
        return QuotaReconReport.of(fullScan, from, to, accountMismatches, batchMismatches);
    }

    private List<QuotaBatchMismatch> compareBatches(List<SysOrgQuotaConsume> flows,
                                                    List<EntitlementLedger> ledgers) {
        Map<String, Long> ledgerByBatch = new HashMap<>();
        for (EntitlementLedger ledger : ledgers) {
            String batchNo = batchNoOf(ledger.getEventId());
            if (batchNo == null) {
                continue;
            }
            long amount = ledger.getChangeAmount() == null ? 0L : ledger.getChangeAmount().longValue();
            ledgerByBatch.merge(batchNo, amount, Long::sum);
        }

        List<QuotaBatchMismatch> mismatches = new ArrayList<>();
        for (SysOrgQuotaConsume flow : flows) {
            long ledgerAmount = ledgerByBatch.getOrDefault(flow.getBatchNo(), 0L);
            long flowNet = flow.net();
            if (flowNet != ledgerAmount) {
                mismatches.add(new QuotaBatchMismatch(flow.getBatchNo(), flow.getOrgId(), flow.getQuotaId(),
                        flow.getEntitlementType(), flowNet, ledgerAmount, flowNet - ledgerAmount));
            }
        }
        return mismatches;
    }

    /**
     * 从 {@code ORG-<batchNo>-<userId>} 里取出批次号。
     *
     * <p>最后一段一定是数字 userId，所以「最后一个 - 之前」就是批次号——
     * 不依赖批次号自身是否含 {@code -}，也就不会出现「批次前缀互相包含」时误匹配。</p>
     */
    private String batchNoOf(String eventId) {
        if (eventId == null || !eventId.startsWith(ORG_EVENT_PREFIX)) {
            return null;
        }
        int lastSep = eventId.lastIndexOf('-');
        if (lastSep <= ORG_EVENT_PREFIX.length()) {
            return null;
        }
        return eventId.substring(ORG_EVENT_PREFIX.length(), lastSep);
    }
}
