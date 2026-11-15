package com.exam.platform.modules.entitlement;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.exam.platform.common.exception.BizException;
import com.exam.platform.common.util.BizNo;
import com.exam.platform.common.util.Entities;
import com.exam.platform.modules.entitlement.entity.EntitlementAccount;
import com.exam.platform.modules.entitlement.entity.EntitlementLedger;
import com.exam.platform.modules.entitlement.mapper.EntitlementAccountMapper;
import com.exam.platform.modules.entitlement.mapper.EntitlementLedgerMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 权益发放与查询。
 *
 * <p>幂等：流水表的唯一约束 (tenant_id, event_id, entitlement_type, resource_id)
 * 保证同一支付事件重复回调只发一次权益。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EntitlementService {

    private final EntitlementAccountMapper accountMapper;
    private final EntitlementLedgerMapper ledgerMapper;

    @Transactional
    public EntitlementAccount grant(Long userId, String entitlementType, String resourceType, Long resourceId,
                                    int amount, LocalDateTime expireAt, Long sourceOrderId, String eventId) {
        EntitlementLedger duplicated = ledgerMapper.selectOne(Wrappers.<EntitlementLedger>lambdaQuery()
                .eq(EntitlementLedger::getEventId, eventId)
                .eq(EntitlementLedger::getEntitlementType, entitlementType)
                .eq(EntitlementLedger::getResourceId, resourceId)
                .last("LIMIT 1"));
        if (duplicated != null) {
            log.info("权益已发放，跳过重复事件 eventId={}", eventId);
            return accountMapper.selectById(duplicated.getAccountId());
        }

        EntitlementAccount account = accountMapper.selectOne(Wrappers.<EntitlementAccount>lambdaQuery()
                .eq(EntitlementAccount::getUserId, userId)
                .eq(EntitlementAccount::getEntitlementType, entitlementType)
                .eq(resourceId != null, EntitlementAccount::getResourceId, resourceId)
                .isNull(resourceId == null, EntitlementAccount::getResourceId)
                .last("LIMIT 1"));

        if (account == null) {
            account = new EntitlementAccount();
            account.setUserId(userId);
            account.setEntitlementType(entitlementType);
            account.setResourceType(resourceType);
            account.setResourceId(resourceId);
            account.setTotalAmount(amount);
            account.setUsedAmount(0);
            account.setStatus(EntitlementAccount.STATUS_AVAILABLE);
            account.setExpireAt(expireAt);
            account.setSourceOrderId(sourceOrderId);
            Entities.forInsert(account);
            accountMapper.insert(account);
        } else {
            account.setTotalAmount(account.getTotalAmount() + amount);
            account.setStatus(EntitlementAccount.STATUS_AVAILABLE);
            account.setExpireAt(expireAt == null ? account.getExpireAt() : expireAt);
            accountMapper.updateById(account);
        }

        EntitlementLedger ledger = new EntitlementLedger();
        ledger.setUserId(userId);
        ledger.setAccountId(account.getId());
        ledger.setEntitlementType(entitlementType);
        ledger.setResourceId(resourceId);
        ledger.setChangeType(EntitlementLedger.CHANGE_GRANT);
        ledger.setChangeAmount(amount);
        ledger.setBalanceAfter(account.getTotalAmount() - account.getUsedAmount());
        ledger.setSourceOrderId(sourceOrderId);
        ledger.setEventId(eventId);
        ledger.setExpireAt(expireAt);
        Entities.forInsert(ledger);
        try {
            ledgerMapper.insert(ledger);
        } catch (DuplicateKeyException e) {
            // 并发重复事件，权益已在另一事务中发放
            log.info("并发重复权益事件已忽略 eventId={}", eventId);
        }
        return account;
    }

    public List<EntitlementAccount> listByUser(Long userId) {
        return accountMapper.selectList(Wrappers.<EntitlementAccount>lambdaQuery()
                .eq(EntitlementAccount::getUserId, userId)
                .orderByDesc(EntitlementAccount::getId));
    }

    public List<EntitlementLedger> ledgerByUser(Long userId) {
        return ledgerMapper.selectList(Wrappers.<EntitlementLedger>lambdaQuery()
                .eq(EntitlementLedger::getUserId, userId)
                .orderByDesc(EntitlementLedger::getId));
    }

    /** 判断用户是否持有某资源的有效权益（报名/开考校验的扩展点）。 */
    public boolean hasActive(Long userId, String entitlementType, Long resourceId) {
        LocalDateTime now = LocalDateTime.now();
        List<EntitlementAccount> accounts = accountMapper.selectList(Wrappers.<EntitlementAccount>lambdaQuery()
                .eq(EntitlementAccount::getUserId, userId)
                .eq(EntitlementAccount::getEntitlementType, entitlementType)
                .eq(resourceId != null, EntitlementAccount::getResourceId, resourceId)
                .eq(EntitlementAccount::getStatus, EntitlementAccount.STATUS_AVAILABLE));
        return accounts.stream().anyMatch(a -> a.getExpireAt() == null || a.getExpireAt().isAfter(now));
    }

    /**
     * 权益核销：按次数消耗，requestId 相同的重复请求不重复扣减。
     */
    @Transactional
    public EntitlementAccount consume(Long userId, Long accountId, int amount, String requestId) {
        EntitlementAccount account = accountMapper.selectById(accountId);
        if (account == null || !account.getUserId().equals(userId)) {
            throw BizException.notFound("权益");
        }
        int consumeAmount = amount <= 0 ? 1 : amount;
        if (account.getExpireAt() != null && LocalDateTime.now().isAfter(account.getExpireAt())) {
            throw new BizException("ENTITLEMENT_EXPIRED", "权益已过期");
        }
        if (EntitlementAccount.STATUS_REVOKED.equals(account.getStatus())
                || EntitlementAccount.STATUS_EXPIRED.equals(account.getStatus())) {
            throw new BizException("ENTITLEMENT_UNAVAILABLE", "权益当前不可用：" + account.getStatus());
        }

        String eventId = "USE-" + (requestId == null || requestId.isBlank() ? BizNo.refundNo() : requestId);
        if (ledgerExists(eventId, account.getEntitlementType(), account.getResourceId())) {
            log.info("权益核销请求重复，直接返回当前状态 eventId={}", eventId);
            return account;
        }

        int remaining = account.getTotalAmount() - account.getUsedAmount();
        if (remaining < consumeAmount) {
            throw new BizException("ENTITLEMENT_INSUFFICIENT",
                    "权益次数不足，剩余 " + Math.max(remaining, 0) + " 次");
        }
        int used = account.getUsedAmount() + consumeAmount;
        account.setUsedAmount(used);
        account.setStatus(used >= account.getTotalAmount()
                ? EntitlementAccount.STATUS_USED : EntitlementAccount.STATUS_PART_USED);
        accountMapper.updateById(account);
        writeLedger(account, EntitlementLedger.CHANGE_USE, -consumeAmount,
                account.getTotalAmount() - used, eventId, account.getSourceOrderId());
        return account;
    }

    /**
     * 退款成功 → 撤销该订单发放的剩余权益。
     *
     * <p>已使用部分不回收（文档要求已使用权益的退款走人工审核，审核动作本身即人工确认）。
     * 以 {@code REFUND-退款单号} 作为 eventId，重复审核不会重复扣减。</p>
     */
    @Transactional
    public int revokeByOrder(Long userId, Long orderId, String eventId) {
        List<EntitlementAccount> accounts = accountMapper.selectList(Wrappers.<EntitlementAccount>lambdaQuery()
                .eq(EntitlementAccount::getUserId, userId)
                .eq(EntitlementAccount::getSourceOrderId, orderId));
        int revoked = 0;
        for (EntitlementAccount account : accounts) {
            if (ledgerExists(eventId, account.getEntitlementType(), account.getResourceId())) {
                continue;
            }
            int remaining = Math.max(account.getTotalAmount() - account.getUsedAmount(), 0);
            if (remaining > 0) {
                // 回收未使用额度：总量收敛到已使用量，余额归零
                account.setTotalAmount(account.getUsedAmount());
                account.setStatus(EntitlementAccount.STATUS_REVOKED);
                accountMapper.updateById(account);
                revoked += remaining;
            } else {
                account.setStatus(EntitlementAccount.STATUS_USED);
            }
            writeLedger(account, EntitlementLedger.CHANGE_REVOKE, -remaining, 0, eventId, orderId);
        }
        return revoked;
    }

    private boolean ledgerExists(String eventId, String entitlementType, Long resourceId) {
        return ledgerMapper.selectCount(Wrappers.<EntitlementLedger>lambdaQuery()
                .eq(EntitlementLedger::getEventId, eventId)
                .eq(EntitlementLedger::getEntitlementType, entitlementType)
                .eq(resourceId != null, EntitlementLedger::getResourceId, resourceId)
                .isNull(resourceId == null, EntitlementLedger::getResourceId)) > 0;
    }

    private void writeLedger(EntitlementAccount account, String changeType, int changeAmount,
                             int balanceAfter, String eventId, Long sourceOrderId) {
        EntitlementLedger ledger = new EntitlementLedger();
        ledger.setUserId(account.getUserId());
        ledger.setAccountId(account.getId());
        ledger.setEntitlementType(account.getEntitlementType());
        ledger.setResourceId(account.getResourceId());
        ledger.setChangeType(changeType);
        ledger.setChangeAmount(changeAmount);
        ledger.setBalanceAfter(Math.max(balanceAfter, 0));
        ledger.setSourceOrderId(sourceOrderId);
        ledger.setEventId(eventId);
        ledger.setExpireAt(account.getExpireAt());
        Entities.forInsert(ledger);
        ledgerMapper.insert(ledger);
    }
}
