package com.exam.platform.modules.org;

import com.exam.platform.common.exception.BizException;
import com.exam.platform.common.util.BizNo;
import com.exam.platform.modules.entitlement.EntitlementService;
import com.exam.platform.modules.entitlement.entity.EntitlementAccount;
import com.exam.platform.modules.org.dto.OrgGrantRequest;
import com.exam.platform.modules.org.dto.OrgGrantResult;
import com.exam.platform.modules.org.entity.SysOrg;
import com.exam.platform.modules.org.entity.SysOrgMember;
import com.exam.platform.modules.org.entity.SysOrgQuota;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 机构 / 企业批量授权。
 *
 * <p>核心原则：批量授权只是「按组织维度把同一个权益事件发给多个成员」，
 * 复用 {@link EntitlementService#grant} 的幂等能力（唯一键
 * {@code (tenant_id, event_id, entitlement_type, resource_id)}），
 * 因此同一批次重复提交不会重复发放。</p>
 *
 * <p>eventId 形如 {@code ORG-<batchNo>-<userId>}，批次号由服务端生成，
 * 便于审计与问题回溯。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrgGrantService {

    private static final Set<String> TYPES = Set.of(
            EntitlementAccount.TYPE_MEMBER, EntitlementAccount.TYPE_QUESTION_BANK,
            EntitlementAccount.TYPE_COURSE, EntitlementAccount.TYPE_EXAM);

    private final OrgService orgService;
    private final EntitlementService entitlementService;
    private final OrgQuotaService orgQuotaService;

    @Transactional
    public OrgGrantResult grant(OrgGrantRequest request) {
        validate(request);
        SysOrg org = orgService.getOrg(request.orgId());
        if (!SysOrg.STATUS_ENABLED.equals(org.getStatus())) {
            throw new BizException("ORG_DISABLED", "组织已停用，不能授权");
        }

        List<Long> targets = resolveTargets(request);
        if (targets.isEmpty()) {
            throw new BizException("ORG_NO_MEMBER", "组织内没有可授权的有效成员");
        }

        String batchNo = BizNo.grantNo();
        int amount = request.amount() == null ? 1 : request.amount();

        // 机构有额度账户时必须够发：余额判断与扣减在同一条 UPDATE 里完成，
        // 「先查余额再扣减」在并发发放时会两边都读到同一份余额、双双放行
        SysOrgQuota quota = orgQuotaService.findAccount(org.getId(), request.entitlementType(),
                request.resourceId());
        long needed = (long) targets.size() * amount;
        if (quota != null && quota.expired()) {
            // 过期账户绝不能被当成「没有额度账户」放行：那样一来，额度到期就等于绕过了限额
            throw new BizException("ORG_QUOTA_EXPIRED",
                    "机构额度已于 " + quota.getExpireAt() + " 到期，请先续充额度");
        }
        if (quota != null && !orgQuotaService.consume(quota.getId(), needed)) {
            throw new BizException("ORG_QUOTA_INSUFFICIENT",
                    "机构可用额度不足：本次需要 " + needed + " 份，当前可用 " + quota.balance() + " 份");
        }

        List<Long> skipped = new ArrayList<>();
        int granted = 0;
        for (Long userId : targets) {
            String eventId = "ORG-" + batchNo + "-" + userId;
            try {
                entitlementService.grant(userId, request.entitlementType(), request.resourceType(),
                        request.resourceId(), amount, request.expireAt(), null, eventId);
                granted++;
            } catch (Exception e) {
                log.warn("批量授权失败 userId={} batchNo={}", userId, batchNo, e);
                skipped.add(userId);
            }
        }

        // 被跳过的成员没拿到权益，他们的额度必须退回，否则机构等于白花钱
        if (quota != null && !skipped.isEmpty()) {
            orgQuotaService.refund(quota.getId(), (long) skipped.size() * amount);
        }
        // 发放流水：净额（扣减 - 退款）必须等于账户 used_amount 的增量，
        // 对账任务就是拿这两个数比对的
        if (quota != null) {
            orgQuotaService.recordConsume(quota, batchNo, targets.size(), amount, granted, skipped.size());
        }

        log.info("组织批量授权完成 orgId={} batchNo={} targets={} granted={} skipped={} quotaId={}",
                org.getId(), batchNo, targets.size(), granted, skipped.size());
        return new OrgGrantResult(batchNo, org.getId(), targets.size(), granted, skipped.size(), skipped,
                request.entitlementType(), request.resourceId(), request.expireAt());
    }

    /** 指定成员时校验其确实属于本组织；否则发给组织全部有效成员。 */
    private List<Long> resolveTargets(OrgGrantRequest request) {
        if (request.userIds() != null && !request.userIds().isEmpty()) {
            List<Long> requested = request.userIds();
            List<Long> activeUserIds = orgService.listActiveMembers(request.orgId()).stream()
                    .map(SysOrgMember::getUserId)
                    .toList();
            List<Long> targets = requested.stream()
                    .filter(activeUserIds::contains)
                    .distinct()
                    .toList();
            if (targets.size() != requested.size()) {
                log.warn("批量授权忽略非本组织成员 orgId={} 请求={} 有效={}",
                        request.orgId(), requested.size(), targets.size());
            }
            return targets;
        }
        return orgService.listActiveMembers(request.orgId()).stream()
                .map(SysOrgMember::getUserId)
                .distinct()
                .toList();
    }

    private void validate(OrgGrantRequest request) {
        if (request.orgId() == null) {
            throw BizException.badRequest("组织不能为空");
        }
        if (request.entitlementType() == null || !TYPES.contains(request.entitlementType())) {
            throw BizException.badRequest("权益类型不合法，支持 MEMBER/QUESTION_BANK/COURSE/EXAM");
        }
        if (request.amount() != null && request.amount() <= 0) {
            throw BizException.badRequest("授权数量必须大于 0");
        }
    }
}
