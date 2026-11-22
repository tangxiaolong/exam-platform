package com.exam.platform.modules.trade.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 交易域统计查询。
 *
 * <p>只放「按状态聚合」这类列表页顶部指标用的 SQL：走 GROUP BY 一次拿回整组数字，
 * 避免为每个状态各发一次 {@code selectCount}。所有语句都显式带 {@code tenant_id}，
 * 与 {@link TradeOrderMapper#sumPaidAmountFen(long)} 保持同一口径。</p>
 */
@Mapper
public interface TradeStatsMapper {

    @Select("SELECT status, COUNT(*) AS cnt, COALESCE(SUM(pay_amount_fen), 0) AS amount_fen "
            + "FROM trade_order WHERE tenant_id = #{tenantId} AND deleted = 0 GROUP BY status")
    List<StatusAgg> aggregateOrders(@Param("tenantId") long tenantId);

    /** 今日成交：按支付时间落在今天统计，而不是按创建时间——未支付的订单不算成交。 */
    @Select("SELECT COUNT(*) AS cnt, COALESCE(SUM(pay_amount_fen), 0) AS amount_fen "
            + "FROM trade_order WHERE tenant_id = #{tenantId} AND deleted = 0 "
            + "AND status IN ('PAID', 'COMPLETED') AND paid_at >= CURRENT_DATE")
    StatusAgg todayPaid(@Param("tenantId") long tenantId);

    @Select("SELECT status, COUNT(*) AS cnt, COALESCE(SUM(amount_fen), 0) AS amount_fen "
            + "FROM trade_refund WHERE tenant_id = #{tenantId} AND deleted = 0 GROUP BY status")
    List<StatusAgg> aggregateRefunds(@Param("tenantId") long tenantId);

    @Select("SELECT status, COUNT(*) AS cnt, COALESCE(SUM(claimed_quantity), 0) AS amount_fen "
            + "FROM marketing_coupon WHERE tenant_id = #{tenantId} AND deleted = 0 GROUP BY status")
    List<StatusAgg> aggregateCoupons(@Param("tenantId") long tenantId);

    @Select("SELECT status, COUNT(*) AS cnt, 0 AS amount_fen "
            + "FROM mall_product WHERE tenant_id = #{tenantId} AND deleted = 0 GROUP BY status")
    List<StatusAgg> aggregateProducts(@Param("tenantId") long tenantId);

    /** 按状态聚合的通用行：{@code amountFen} 的含义随查询而变（金额 / 领取量 / 恒为 0）。 */
    class StatusAgg {
        private String status;
        private Long cnt;
        private Long amountFen;

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }

        public Long getCnt() {
            return cnt;
        }

        public void setCnt(Long cnt) {
            this.cnt = cnt;
        }

        public Long getAmountFen() {
            return amountFen;
        }

        public void setAmountFen(Long amountFen) {
            this.amountFen = amountFen;
        }
    }
}
