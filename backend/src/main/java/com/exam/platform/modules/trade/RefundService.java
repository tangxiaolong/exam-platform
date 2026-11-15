package com.exam.platform.modules.trade;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.exam.platform.common.api.PageQuery;
import com.exam.platform.common.api.PageResult;
import com.exam.platform.common.exception.BizException;
import com.exam.platform.common.security.UserContext;
import com.exam.platform.common.util.BizNo;
import com.exam.platform.common.util.Entities;
import com.exam.platform.modules.entitlement.EntitlementService;
import com.exam.platform.modules.trade.entity.TradeOrder;
import com.exam.platform.modules.trade.entity.TradePayment;
import com.exam.platform.modules.trade.entity.TradeRefund;
import com.exam.platform.modules.trade.mapper.TradeOrderMapper;
import com.exam.platform.modules.trade.mapper.TradePaymentMapper;
import com.exam.platform.modules.trade.mapper.TradeRefundMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 退款。
 *
 * <p>流程：考生申请（APPLIED）→ 运营审核 → 通过则调用支付平台退款并撤销未使用权益，
 * 驳回则订单保持可用的已完成状态。首期为整单全额退款。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RefundService {

    private final TradeRefundMapper refundMapper;
    private final TradeOrderMapper orderMapper;
    private final TradePaymentMapper paymentMapper;
    private final EntitlementService entitlementService;
    private final TradeService tradeService;

    @Transactional
    public TradeRefund apply(Long userId, Long orderId, String reason) {
        TradeOrder order = tradeService.getOwnedOrder(orderId, userId);
        if (!TradeOrder.STATUS_COMPLETED.equals(order.getStatus())
                && !TradeOrder.STATUS_PAID.equals(order.getStatus())) {
            throw new BizException("ORDER_STATUS_INVALID", "当前订单状态不可退款：" + order.getStatus());
        }
        List<TradeRefund> active = refundMapper.selectList(Wrappers.<TradeRefund>lambdaQuery()
                .eq(TradeRefund::getOrderId, orderId)
                .in(TradeRefund::getStatus, TradeRefund.STATUS_APPLIED, TradeRefund.STATUS_SUCCESS));
        if (!active.isEmpty()) {
            throw new BizException("REFUND_EXISTS", "该订单已有退款申请或已退款");
        }

        TradePayment payment = paymentMapper.selectOne(Wrappers.<TradePayment>lambdaQuery()
                .eq(TradePayment::getOrderId, orderId)
                .eq(TradePayment::getStatus, TradePayment.STATUS_PAID)
                .orderByDesc(TradePayment::getId)
                .last("LIMIT 1"));

        TradeRefund refund = new TradeRefund();
        refund.setRefundNo(BizNo.refundNo());
        refund.setOrderId(order.getId());
        refund.setOrderNo(order.getOrderNo());
        refund.setUserId(userId);
        refund.setPayNo(payment == null ? null : payment.getPayNo());
        refund.setAmountFen(order.getPayAmountFen());
        refund.setReason(reason);
        refund.setStatus(TradeRefund.STATUS_APPLIED);
        refund.setAppliedAt(LocalDateTime.now());
        Entities.forInsert(refund);
        refundMapper.insert(refund);
        return refund;
    }

    @Transactional
    public TradeRefund approve(Long refundId, String remark) {
        TradeRefund refund = get(refundId);
        if (TradeRefund.STATUS_SUCCESS.equals(refund.getStatus())) {
            return refund;
        }
        if (!TradeRefund.STATUS_APPLIED.equals(refund.getStatus())) {
            throw new BizException("REFUND_STATUS_INVALID", "当前退款状态不可审核：" + refund.getStatus());
        }
        TradeOrder order = orderMapper.selectById(refund.getOrderId());
        if (order == null) {
            throw BizException.notFound("订单");
        }

        // 真实环境：此处调用微信支付退款接口并等待退款回调；mock 模式直接视为退款成功
        log.info("执行退款 refundNo={} orderNo={} amountFen={}", refund.getRefundNo(), refund.getOrderNo(),
                refund.getAmountFen());

        LocalDateTime now = LocalDateTime.now();
        TradeRefund update = new TradeRefund();
        update.setId(refund.getId());
        update.setStatus(TradeRefund.STATUS_SUCCESS);
        update.setAuditRemark(remark);
        update.setAuditedBy(UserContext.userId());
        update.setAuditedAt(now);
        update.setRefundedAt(now);
        refundMapper.updateById(update);

        TradeOrder refunded = new TradeOrder();
        refunded.setId(order.getId());
        refunded.setStatus(TradeOrder.STATUS_REFUNDED);
        orderMapper.updateById(refunded);

        int revoked = entitlementService.revokeByOrder(order.getUserId(), order.getId(),
                "REFUND-" + refund.getRefundNo());
        log.info("退款成功并回收权益 refundNo={} revoked={}", refund.getRefundNo(), revoked);
        return get(refundId);
    }

    @Transactional
    public TradeRefund reject(Long refundId, String remark) {
        TradeRefund refund = get(refundId);
        if (TradeRefund.STATUS_REJECTED.equals(refund.getStatus())) {
            return refund;
        }
        if (!TradeRefund.STATUS_APPLIED.equals(refund.getStatus())) {
            throw new BizException("REFUND_STATUS_INVALID", "当前退款状态不可审核：" + refund.getStatus());
        }
        TradeRefund update = new TradeRefund();
        update.setId(refundId);
        update.setStatus(TradeRefund.STATUS_REJECTED);
        update.setAuditRemark(remark);
        update.setAuditedBy(UserContext.userId());
        update.setAuditedAt(LocalDateTime.now());
        refundMapper.updateById(update);
        return get(refundId);
    }

    public TradeRefund get(Long refundId) {
        TradeRefund refund = refundMapper.selectById(refundId);
        if (refund == null) {
            throw BizException.notFound("退款单");
        }
        return refund;
    }

    public PageResult<TradeRefund> pageMy(Long userId, PageQuery pageQuery) {
        Page<TradeRefund> page = refundMapper.selectPage(pageQuery.toPage(), Wrappers.<TradeRefund>lambdaQuery()
                .eq(TradeRefund::getUserId, userId)
                .orderByDesc(TradeRefund::getId));
        return PageResult.of(page.getRecords(), page.getTotal(), page.getCurrent(), page.getSize());
    }

    public PageResult<TradeRefund> pageAll(String status, PageQuery pageQuery) {
        Page<TradeRefund> page = refundMapper.selectPage(pageQuery.toPage(), Wrappers.<TradeRefund>lambdaQuery()
                .eq(status != null && !status.isBlank(), TradeRefund::getStatus, status)
                .orderByDesc(TradeRefund::getId));
        return PageResult.of(page.getRecords(), page.getTotal(), page.getCurrent(), page.getSize());
    }
}
