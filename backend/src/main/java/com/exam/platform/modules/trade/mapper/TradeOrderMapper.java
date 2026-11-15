package com.exam.platform.modules.trade.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.exam.platform.modules.trade.entity.TradeOrder;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface TradeOrderMapper extends BaseMapper<TradeOrder> {

    @Select("SELECT COALESCE(SUM(pay_amount_fen), 0) FROM trade_order WHERE tenant_id = #{tenantId} "
            + "AND status IN ('PAID', 'COMPLETED') AND deleted = 0")
    long sumPaidAmountFen(@Param("tenantId") long tenantId);
}
