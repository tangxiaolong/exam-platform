package com.exam.platform.modules.trade.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.exam.platform.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * 对账异常明细。每条记录对应一处「账实不符」，不自动修复——涉及资金与权益，
 * 自动补偿的风险高于人工确认，首期只做检出与留痕。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("trade_recon_anomaly")
public class TradeReconAnomaly extends BaseEntity {

    /** 订单已支付，但始终没有对应的权益发放流水（回调后置已支付成功、发权益失败）。 */
    public static final String TYPE_ORDER_PAID_WITHOUT_ENTITLEMENT = "ORDER_PAID_WITHOUT_ENTITLEMENT";
    /** 订单已完成，却没有权益发放流水（状态被改坏或补偿脚本跳过）。 */
    public static final String TYPE_ORDER_COMPLETED_WITHOUT_ENTITLEMENT = "ORDER_COMPLETED_WITHOUT_ENTITLEMENT";
    /** 支付单已支付，订单却仍是待支付/已关闭（回调只改了一半）。 */
    public static final String TYPE_PAYMENT_PAID_ORDER_NOT_PAID = "PAYMENT_PAID_ORDER_NOT_PAID";
    /** 支付单金额与订单应付金额不一致（下单后被改价或回调金额被篡改）。 */
    public static final String TYPE_PAYMENT_AMOUNT_MISMATCH = "PAYMENT_AMOUNT_MISMATCH";
    /** 权益账户余额与流水累计不一致（并发更新丢失或手工改库）。 */
    public static final String TYPE_ACCOUNT_LEDGER_DRIFT = "ACCOUNT_LEDGER_DRIFT";

    public static final String BIZ_ORDER = "ORDER";
    public static final String BIZ_PAYMENT = "PAYMENT";
    public static final String BIZ_ACCOUNT = "ACCOUNT";

    /** 待处置：刚检出，还没人认领。 */
    public static final String HANDLE_PENDING = "PENDING";
    /** 已处理：运营已修复数据或补发权益。 */
    public static final String HANDLE_HANDLED = "HANDLED";
    /** 已忽略：人工核对后确认账实相符，属于对账规则误判。 */
    public static final String HANDLE_IGNORED = "IGNORED";

    public static final Set<String> HANDLE_STATUSES =
            Set.of(HANDLE_PENDING, HANDLE_HANDLED, HANDLE_IGNORED);

    private Long reportId;
    private String anomalyType;
    private String bizType;
    private Long bizId;
    private String bizNo;
    private String expectedValue;
    private String actualValue;
    private String detail;
    /** 处置状态，见 {@link #HANDLE_STATUSES}。 */
    private String handleStatus;
    private String handleRemark;
    private Long handledBy;
    private LocalDateTime handledAt;
}
