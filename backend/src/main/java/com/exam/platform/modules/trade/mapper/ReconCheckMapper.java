package com.exam.platform.modules.trade.mapper;

import lombok.Data;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 对账只读检查。
 *
 * <p>用 SQL 直接筛出不一致的行，而不是把全量数据捞到内存再比：对账是「每天都会跑」的
 * 长期任务，数据量只增不减，全量比对迟早会变成一次 OOM 事故。
 * 租户条件由 MyBatis-Plus 租户插件在 SQL 层自动追加，与业务代码口径一致。</p>
 */
@Mapper
public interface ReconCheckMapper {

    @Select("SELECT COUNT(*) FROM trade_order WHERE deleted = 0")
    int countOrders();

    @Select("SELECT COUNT(*) FROM trade_payment WHERE deleted = 0")
    int countPayments();

    @Select("SELECT COUNT(*) FROM entitlement_account WHERE deleted = 0")
    int countAccounts();

    /**
     * 订单已进入指定状态，却没有权益发放流水。
     *
     * <p>用 {@code before} 卡住一个宽限期：回调刚置为已支付、权益还没发完的那几秒属于正常窗口，
     * 对账必须避开它，否则每次都会误报。</p>
     */
    @Select("""
            SELECT o.id AS orderId, o.order_no AS orderNo, o.status AS status,
                   o.pay_amount_fen AS payAmountFen, o.paid_at AS paidAt
            FROM trade_order o
            WHERE o.deleted = 0
              AND o.status = #{status}
              AND o.paid_at IS NOT NULL
              AND o.paid_at < #{before}
              AND NOT EXISTS (
                  SELECT 1 FROM entitlement_ledger l
                  WHERE l.deleted = 0
                    AND l.source_order_id = o.id
                    AND l.change_type = 'GRANT'
              )
            ORDER BY o.id
            LIMIT #{limit}
            """)
    List<OrderWithoutEntitlementRow> findOrdersWithoutEntitlement(@Param("status") String status,
                                                                  @Param("before") LocalDateTime before,
                                                                  @Param("limit") int limit);

    /** 支付单已支付，订单却还停在待支付/已关闭——回调只改了一半。 */
    @Select("""
            SELECT p.id AS paymentId, p.pay_no AS payNo, o.id AS orderId, o.order_no AS orderNo, o.status AS orderStatus
            FROM trade_payment p
            JOIN trade_order o ON o.id = p.order_id AND o.deleted = 0
            WHERE p.deleted = 0
              AND p.status = 'PAID'
              AND o.status IN ('PENDING_PAYMENT', 'CLOSED')
            ORDER BY p.id
            LIMIT #{limit}
            """)
    List<PaymentOrderStatusRow> findPaidPaymentsWithUnpaidOrder(@Param("limit") int limit);

    /** 支付金额与订单应付金额不一致。 */
    @Select("""
            SELECT p.id AS paymentId, p.pay_no AS payNo,
                   p.amount_fen AS paymentAmountFen, o.pay_amount_fen AS orderPayAmountFen
            FROM trade_payment p
            JOIN trade_order o ON o.id = p.order_id AND o.deleted = 0
            WHERE p.deleted = 0
              AND p.status = 'PAID'
              AND p.amount_fen <> o.pay_amount_fen
            ORDER BY p.id
            LIMIT #{limit}
            """)
    List<AmountMismatchRow> findPaymentAmountMismatch(@Param("limit") int limit);

    /**
     * 权益账户余额与流水累计不一致。
     *
     * <p>不变量：{@code total_amount - used_amount == SUM(ledger.change_amount)}。
     * 发放记正数、核销记负数、退款回收也记负数，账户收敛后两者必然相等；
     * 不相等说明有并发更新丢失或手工改库。</p>
     */
    @Select("""
            SELECT a.id AS accountId, a.total_amount AS totalAmount, a.used_amount AS usedAmount,
                   COALESCE(SUM(l.change_amount), 0) AS ledgerSum
            FROM entitlement_account a
            LEFT JOIN entitlement_ledger l ON l.account_id = a.id AND l.deleted = 0
            WHERE a.deleted = 0
            GROUP BY a.id, a.total_amount, a.used_amount
            HAVING (a.total_amount - a.used_amount) <> COALESCE(SUM(l.change_amount), 0)
            ORDER BY a.id
            LIMIT #{limit}
            """)
    List<AccountDriftRow> findAccountLedgerDrift(@Param("limit") int limit);

    @Data
    class OrderWithoutEntitlementRow {
        private Long orderId;
        private String orderNo;
        private String status;
        private Long payAmountFen;
        private LocalDateTime paidAt;
    }

    @Data
    class PaymentOrderStatusRow {
        private Long paymentId;
        private String payNo;
        private Long orderId;
        private String orderNo;
        private String orderStatus;
    }

    @Data
    class AmountMismatchRow {
        private Long paymentId;
        private String payNo;
        private Long paymentAmountFen;
        private Long orderPayAmountFen;
    }

    @Data
    class AccountDriftRow {
        private Long accountId;
        private Integer totalAmount;
        private Integer usedAmount;
        private Integer ledgerSum;
    }
}
