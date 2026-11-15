package com.exam.platform.modules.org;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.exam.platform.common.exception.BizException;
import com.exam.platform.common.security.LoginUser;
import com.exam.platform.common.security.UserContext;
import com.exam.platform.common.util.Entities;
import com.exam.platform.modules.entitlement.entity.EntitlementAccount;
import com.exam.platform.modules.org.dto.ExpiredQuotaView;
import com.exam.platform.modules.org.entity.SysOrg;
import com.exam.platform.modules.org.entity.SysOrgQuota;
import com.exam.platform.modules.org.entity.SysOrgQuotaConsume;
import com.exam.platform.modules.org.entity.SysOrgQuotaRecharge;
import com.exam.platform.modules.org.mapper.SysOrgMapper;
import com.exam.platform.modules.org.mapper.SysOrgQuotaConsumeMapper;
import com.exam.platform.modules.org.mapper.SysOrgQuotaMapper;
import com.exam.platform.modules.org.mapper.SysOrgQuotaRechargeMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 机构额度池：机构买了多少、发了多少、还剩多少。
 *
 * <p>没有额度账户的机构，发放行为与之前完全一致（不限额）——这是刻意的向后兼容：
 * 额度池是给「已经签约采购、需要按量控制」的机构用的，而不是给所有机构套上一道新门槛。</p>
 *
 * <p>一旦机构有账户，就按「买多少发多少」执行，余额判断与扣减在同一条 UPDATE 里完成。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrgQuotaService {

    private static final Set<String> TYPES = Set.of(
            EntitlementAccount.TYPE_MEMBER, EntitlementAccount.TYPE_QUESTION_BANK,
            EntitlementAccount.TYPE_COURSE, EntitlementAccount.TYPE_EXAM);

    private final SysOrgQuotaMapper quotaMapper;
    private final SysOrgQuotaRechargeMapper rechargeMapper;
    private final SysOrgQuotaConsumeMapper consumeMapper;
    private final SysOrgMapper orgMapper;

    /** 平台给机构充值；同一 (机构, 类型, 资源) 累加到同一账户。 */
    @Transactional
    public SysOrgQuota recharge(Long orgId, String entitlementType, Long resourceId, Long amount,
                                String remark, LocalDateTime expireAt) {
        if (orgId == null) {
            throw BizException.badRequest("机构不能为空");
        }
        if (entitlementType == null || !TYPES.contains(entitlementType)) {
            throw BizException.badRequest("权益类型不合法，支持 MEMBER/QUESTION_BANK/COURSE/EXAM");
        }
        if (amount == null || amount <= 0) {
            throw BizException.badRequest("充值额度必须大于 0");
        }
        if (expireAt != null && !expireAt.isAfter(LocalDateTime.now())) {
            throw BizException.badRequest("额度有效期必须晚于当前时间");
        }
        long resource = normalize(resourceId);

        SysOrgQuota existing = findExact(orgId, entitlementType, resource);
        if (existing != null) {
            quotaMapper.recharge(existing.getId(), amount, remark, expireAt, LocalDateTime.now());
            SysOrgQuota updated = quotaMapper.selectById(existing.getId());
            writeRecharge(updated, amount, expireAt, remark);
            return updated;
        }

        SysOrgQuota quota = new SysOrgQuota();
        quota.setOrgId(orgId);
        quota.setEntitlementType(entitlementType);
        quota.setResourceId(resource);
        quota.setTotalAmount(amount);
        quota.setUsedAmount(0L);
        quota.setRemark(remark);
        quota.setExpireAt(expireAt);
        Entities.forInsert(quota);
        quotaMapper.insert(quota);
        writeRecharge(quota, amount, expireAt, remark);
        return quota;
    }

    /** 充值流水（按时间倒序）。对账要的是「哪笔采购充了多少」，不是账户上的一个汇总数。 */
    public List<SysOrgQuotaRecharge> listRecharges(Long orgId) {
        return rechargeMapper.selectList(Wrappers.<SysOrgQuotaRecharge>lambdaQuery()
                .eq(SysOrgQuotaRecharge::getOrgId, orgId)
                .orderByDesc(SysOrgQuotaRecharge::getId)
                .last("LIMIT 200"));
    }

    /**
     * 已过期的额度账户（含未用余额）。
     *
     * <p>让运营看见「哪些机构的额度作废了多少」，而不是删除账户——
     * 删掉账户会让机构退化成「没有额度账户」，而「没有账户」在当前实现里等于不限额。</p>
     *
     * <p>逐个账户查机构名而不做联表：过期账户数量很小（每个机构每种类型至多一行），
     * 换来的是一条不需要解释的简单查询。</p>
     */
    public List<ExpiredQuotaView> listExpired(LocalDateTime now) {
        List<SysOrgQuota> expired = quotaMapper.selectList(Wrappers.<SysOrgQuota>lambdaQuery()
                .isNotNull(SysOrgQuota::getExpireAt)
                .le(SysOrgQuota::getExpireAt, now)
                .orderByAsc(SysOrgQuota::getExpireAt));
        List<ExpiredQuotaView> views = new ArrayList<>(expired.size());
        for (SysOrgQuota quota : expired) {
            SysOrg org = orgMapper.selectById(quota.getOrgId());
            views.add(new ExpiredQuotaView(quota.getOrgId(),
                    org == null ? "(机构已删除)" : org.getName(),
                    quota.getEntitlementType(), quota.getResourceId(),
                    quota.balance(), quota.getExpireAt()));
        }
        return views;
    }

    /**
     * 落一条充值流水。
     *
     * <p>与账户累加在同一个事务里，两者一起成功或一起回滚；
     * 流水之和与账户 totalAmount 必须相等，测试直接断言这一点。</p>
     */
    private void writeRecharge(SysOrgQuota quota, long amount, LocalDateTime expireAt, String remark) {
        SysOrgQuotaRecharge recharge = new SysOrgQuotaRecharge();
        recharge.setOrgId(quota.getOrgId());
        recharge.setQuotaId(quota.getId());
        recharge.setEntitlementType(quota.getEntitlementType());
        recharge.setResourceId(quota.getResourceId() == null ? SysOrgQuota.RESOURCE_UNIVERSAL : quota.getResourceId());
        recharge.setAmount(amount);
        recharge.setExpireAt(expireAt);
        recharge.setRemark(remark);
        LoginUser user = UserContext.get();
        // 脚本补录或定时任务没有登录态，操作人留空——不伪造一个操作人
        recharge.setOperatorId(user == null ? null : user.getUserId());
        Entities.forInsert(recharge);
        rechargeMapper.insert(recharge);
    }

    public List<SysOrgQuota> list(Long orgId) {
        return quotaMapper.selectList(Wrappers.<SysOrgQuota>lambdaQuery()
                .eq(SysOrgQuota::getOrgId, orgId)
                .orderByAsc(SysOrgQuota::getId));
    }

    /**
     * 找到可用于本次发放的额度账户：先精确匹配资源，再回退到该类型的通用额度。
     *
     * <p>回退是必要的：机构买的是「这门课的 100 个名额」还是「任意课程 100 个名额」，
     * 运营充值时的粒度未必与发放时的粒度一致，只认精确匹配会让「通用额度」永远用不掉。</p>
     */
    public SysOrgQuota findAccount(Long orgId, String entitlementType, Long resourceId) {
        long resource = normalize(resourceId);
        SysOrgQuota exact = findExact(orgId, entitlementType, resource);
        if (exact != null) {
            return exact;
        }
        if (resource == SysOrgQuota.RESOURCE_UNIVERSAL) {
            return null;
        }
        return findExact(orgId, entitlementType, SysOrgQuota.RESOURCE_UNIVERSAL);
    }

    /** 扣减额度；余额不足返回 false（判断与扣减是同一条 UPDATE，不存在先查后改的双花窗口）。 */
    public boolean consume(Long accountId, long amount) {
        return quotaMapper.consume(accountId, amount, LocalDateTime.now()) > 0;
    }

    /** 退还额度：发放过程中被跳过的成员，其额度必须退回。 */
    public void refund(Long accountId, long amount) {
        if (amount <= 0) {
            return;
        }
        if (quotaMapper.refund(accountId, amount, LocalDateTime.now()) == 0) {
            // 退款没生效（并发或数据异常）：流水净额与账户余额会不一致，对账任务会把它检出来
            log.warn("额度退款未生效 accountId={} amount={}", accountId, amount);
        }
    }

    /**
     * 落一条发放流水。
     *
     * <p>净额（{@code consumedAmount - refundedAmount}）必须等于账户 {@code usedAmount} 的增量：
     * 前者是「按批次记的明细」，后者是「账户上的汇总」，两者不等就说明有扣减没落流水，
     * {@link OrgQuotaReconService} 会定期把这类偏差检出来。</p>
     */
    public SysOrgQuotaConsume recordConsume(SysOrgQuota quota, String batchNo, int targetCount,
                                            int amountPerUser, int grantedCount, int skippedCount) {
        SysOrgQuotaConsume consume = new SysOrgQuotaConsume();
        consume.setOrgId(quota.getOrgId());
        consume.setQuotaId(quota.getId());
        consume.setBatchNo(batchNo);
        consume.setEntitlementType(quota.getEntitlementType());
        consume.setResourceId(quota.getResourceId() == null
                ? SysOrgQuota.RESOURCE_UNIVERSAL : quota.getResourceId());
        consume.setTargetCount(targetCount);
        consume.setAmountPerUser((long) amountPerUser);
        consume.setConsumedAmount((long) targetCount * amountPerUser);
        consume.setGrantedCount(grantedCount);
        consume.setSkippedCount(skippedCount);
        consume.setRefundedAmount((long) skippedCount * amountPerUser);
        LoginUser user = UserContext.get();
        consume.setOperatorId(user == null ? null : user.getUserId());
        Entities.forInsert(consume);
        consumeMapper.insert(consume);
        return consume;
    }

    private SysOrgQuota findExact(Long orgId, String entitlementType, long resourceId) {
        return quotaMapper.selectOne(Wrappers.<SysOrgQuota>lambdaQuery()
                .eq(SysOrgQuota::getOrgId, orgId)
                .eq(SysOrgQuota::getEntitlementType, entitlementType)
                .eq(SysOrgQuota::getResourceId, resourceId));
    }

    private long normalize(Long resourceId) {
        return resourceId == null ? SysOrgQuota.RESOURCE_UNIVERSAL : resourceId;
    }
}
