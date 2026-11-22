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
import com.exam.platform.modules.trade.dto.RefundApplyRequest;
import com.exam.platform.modules.trade.entity.TradeOrder;
import com.exam.platform.modules.trade.entity.TradeOrderItem;
import com.exam.platform.modules.trade.entity.TradePayment;
import com.exam.platform.modules.trade.entity.TradeRefund;
import com.exam.platform.modules.trade.mapper.TradeOrderItemMapper;
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
 * 退款：整单退款与按明细的部分退款。
 *
 * <p>流程：考生申请（APPLIED）→ 运营审核 → 通过则调用支付平台退款并回收权益。</p>
 *
 * <p>部分退款的金额规则（必须固定，否则每次退多少会变成客服口算）：</p>
 * <ol>
 *   <li>优惠券抵扣按<b>整单比例</b>摊到每条明细：明细实付 = 明细原价 × 订单实付 ÷ 订单原价；</li>
 *   <li>再按数量切分：本次退款 = 明细实付 × 本次数量 ÷ 明细数量；</li>
 *   <li>累计不得超过明细实付（用明细上的 {@code refundedAmountFen} 卡住）。</li>
 * </ol>
 *
 * <p>权益回收的取舍：<b>只有整单退完才回收</b>。部分退款不回收权益——权益是「次数包」语义，
 * 按比例回收会引出「退掉的是哪几份、已用完的算不算」的争议，而这类争议最终都靠人工裁决，
 * 与其算一个可能错的数，不如保持账户不动并留下审计记录。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RefundService {

    private final TradeRefundMapper refundMapper;
    private final TradeOrderMapper orderMapper;
    private final TradePaymentMapper paymentMapper;
    private final TradeOrderItemMapper orderItemMapper;
    private final EntitlementService entitlementService;
    private final TradeService tradeService;

    @Transactional
    public TradeRefund apply(Long userId, Long orderId, RefundApplyRequest request) {
        TradeOrder order = tradeService.getOwnedOrder(orderId, userId);
        if (TradeOrder.STATUS_REFUNDED.equals(order.getStatus())) {
            // 已全退的订单再申请：说「没有可退金额」比「状态非法」更贴近用户看到的事实
            throw new BizException("REFUND_NOTHING", "订单已全额退款");
        }
        if (TradeOrder.STATUS_CLOSED.equals(order.getStatus())) {
            throw new BizException("ORDER_STATUS_INVALID", "订单已关闭，无法退款");
        }
        if (!TradeOrder.STATUS_COMPLETED.equals(order.getStatus())
                && !TradeOrder.STATUS_PAID.equals(order.getStatus())
                && !TradeOrder.STATUS_PARTIALLY_REFUNDED.equals(order.getStatus())) {
            throw new BizException("ORDER_STATUS_INVALID", "当前订单状态不可退款：" + order.getStatus());
        }

        Long itemId = request == null ? null : request.orderItemId();
        int quantity = request == null || request.quantity() == null ? 0 : request.quantity();
        String reason = request == null ? null : request.reason();

        long refundAmount;
        TradeOrderItem item = null;
        if (itemId == null) {
            assertNoPendingRefund(orderId, null);
            refundAmount = order.getPayAmountFen() - refundedTotal(orderId);
        } else {
            item = orderItemMapper.selectById(itemId);
            if (item == null || !item.getOrderId().equals(order.getId())) {
                throw new BizException("REFUND_ITEM_NOT_FOUND", "退款明细不存在或不属于该订单");
            }
            assertNoPendingRefund(orderId, itemId);
            int qty = quantity <= 0 ? item.getQuantity() : quantity;
            if (qty < 1 || qty > item.getQuantity()) {
                throw BizException.badRequest("退款数量需在 1~" + item.getQuantity() + " 之间");
            }
            refundAmount = itemRefundAmount(order, item, qty);
        }
        if (refundAmount <= 0) {
            throw new BizException("REFUND_NOTHING", "没有可退金额（可能已全额退款）");
        }
        if (refundAmount > order.getPayAmountFen() - refundedTotal(orderId)) {
            // 兜底：任何算法错误都不能让退款总额超过实付
            refundAmount = order.getPayAmountFen() - refundedTotal(orderId);
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
        refund.setAmountFen(refundAmount);
        refund.setReason(reason);
        refund.setStatus(TradeRefund.STATUS_APPLIED);
        refund.setAppliedAt(LocalDateTime.now());
        refund.setOrderItemId(item == null ? null : item.getId());
        refund.setQuantity(item == null ? 1 : Math.max(1, quantity == 0 ? item.getQuantity() : quantity));
        Entities.forInsert(refund);
        refundMapper.insert(refund);
        log.info("退款申请 refundNo={} orderNo={} amountFen={} itemId={}",
                refund.getRefundNo(), refund.getOrderNo(), refundAmount, refund.getOrderItemId());
        return refund;
    }

    /**
     * 明细可退金额：先按整单实付占比折算该行实付，再按数量切分，最后受「已退金额」上限约束。
     */
    private long itemRefundAmount(TradeOrder order, TradeOrderItem item, int quantity) {
        long paid = order.getPayAmountFen() == null ? 0L : order.getPayAmountFen();
        long total = order.getTotalAmountFen() == null || order.getTotalAmountFen() <= 0
                ? paid : order.getTotalAmountFen();
        long itemAmount = item.getAmountFen() == null ? 0L : item.getAmountFen();
        long itemPaidShare = total <= 0 ? 0L : itemAmount * paid / total;
        int itemQty = item.getQuantity() == null || item.getQuantity() <= 0 ? 1 : item.getQuantity();
        // 每件实付必须是「整行实付 ÷ 整行数量」，与已退多少无关。
        // 若用「剩余可退 ÷ 数量」，每退一次单价都会被摊薄：退 2 件里的第 1 件是 9400，
        // 第 2 件只剩 4700，累计永远退不到实付。
        long perUnit = itemPaidShare / itemQty;
        long requested = perUnit * Math.min(quantity, itemQty);
        long already = item.getRefundedAmountFen() == null ? 0L : item.getRefundedAmountFen();
        long refundable = Math.max(0L, itemPaidShare - already);
        return Math.min(requested, refundable);
    }

    private void assertNoPendingRefund(Long orderId, Long itemId) {
        long pending = refundMapper.selectCount(Wrappers.<TradeRefund>lambdaQuery()
                .eq(TradeRefund::getOrderId, orderId)
                .eq(itemId != null, TradeRefund::getOrderItemId, itemId)
                .eq(TradeRefund::getStatus, TradeRefund.STATUS_APPLIED));
        if (pending > 0) {
            throw new BizException("REFUND_EXISTS", "该订单已有待审核的退款申请");
        }
    }

    private long refundedTotal(Long orderId) {
        List<TradeRefund> done = refundMapper.selectList(Wrappers.<TradeRefund>lambdaQuery()
                .eq(TradeRefund::getOrderId, orderId)
                .eq(TradeRefund::getStatus, TradeRefund.STATUS_SUCCESS));
        return done.stream().mapToLong(r -> r.getAmountFen() == null ? 0L : r.getAmountFen()).sum();
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
        log.info("执行退款 refundNo={} orderNo={} amountFen={}",
                refund.getRefundNo(), refund.getOrderNo(), refund.getAmountFen());

        LocalDateTime now = LocalDateTime.now();
        TradeRefund update = new TradeRefund();
        update.setId(refund.getId());
        update.setStatus(TradeRefund.STATUS_SUCCESS);
        update.setAuditRemark(remark);
        update.setAuditedBy(UserContext.userId());
        update.setAuditedAt(now);
        update.setRefundedAt(now);
        refundMapper.updateById(update);

        if (refund.getOrderItemId() != null) {
            TradeOrderItem item = orderItemMapper.selectById(refund.getOrderItemId());
            if (item != null) {
                long refunded = (item.getRefundedAmountFen() == null ? 0L : item.getRefundedAmountFen())
                        + refund.getAmountFen();
                TradeOrderItem itemUpdate = new TradeOrderItem();
                itemUpdate.setId(item.getId());
                itemUpdate.setRefundedAmountFen(refunded);
                orderItemMapper.updateById(itemUpdate);
            }
        }

        long refundedTotal = refundedTotal(order.getId());
        boolean fullyRefunded = refundedTotal >= order.getPayAmountFen();
        TradeOrder orderUpdate = new TradeOrder();
        orderUpdate.setId(order.getId());
        orderUpdate.setStatus(fullyRefunded ? TradeOrder.STATUS_REFUNDED : TradeOrder.STATUS_PARTIALLY_REFUNDED);
        orderMapper.updateById(orderUpdate);

        if (fullyRefunded) {
            int revoked = entitlementService.revokeByOrder(order.getUserId(), order.getId(),
                    "REFUND-" + refund.getRefundNo());
            log.info("整单退款完成并回收权益 refundNo={} revoked={} refundedTotal={}",
                    refund.getRefundNo(), revoked, refundedTotal);
        } else {
            // 部分退款保留权益：按比例回收会产生「退掉的是哪几份」的争议，见类注释
            log.info("部分退款完成，权益保留 refundNo={} amountFen={} refundedTotal={}/{}",
                    refund.getRefundNo(), refund.getAmountFen(), refundedTotal, order.getPayAmountFen());
        }
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
