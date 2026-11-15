package com.exam.platform.modules.message;

import java.util.Map;

/**
 * 消息模板编码与默认文案。
 *
 * <p>编码是业务契约（发布方与通道之间靠它对齐）；文案则是运营资产，正式取值优先读
 * {@code msg_template} 表（运营可改、不用发版），这里只保留一份「兜底文案」——
 * 库里没配（新租户、升级后新增编码、运营临时停用）时仍然能把消息发出去。</p>
 */
public final class MessageTemplates {

    /** 报名成功 */
    public static final String EXAM_REGISTER = "EXAM_REGISTER_SUCCESS";
    /** 支付成功 */
    public static final String PAYMENT_SUCCESS = "PAYMENT_SUCCESS";
    /** 成绩发布 */
    public static final String RESULT_PUBLISHED = "EXAM_RESULT_PUBLISHED";

    /**
     * 默认文案。
     *
     * <p>占位符严格对应各发布方 payload 的键名（见 {@code TradeService}/{@code ExamSessionService}/
     * {@code GradingService}）；写错键名时渲染会原样保留 {@code {xxx}}，运维一眼能看出是模板问题，
     * 而不是「消息内容莫名少了一段」。</p>
     */
    private static final Map<String, DefaultTemplate> DEFAULTS = Map.of(
            EXAM_REGISTER, new DefaultTemplate("报名成功",
                    "你已成功报名 {sessionName}，准考证号 {ticketNo}，开考时间 {startAt}。"),
            PAYMENT_SUCCESS, new DefaultTemplate("支付成功",
                    "订单 {orderNo}（{productName}）已支付，共计 {amountFen} 分，权益已到账。"),
            RESULT_PUBLISHED, new DefaultTemplate("成绩发布",
                    "成绩已发布：{sessionName} 总分 {totalScore}，满分 {fullScore}，答卷号 {attemptId}。")
    );

    private MessageTemplates() {
    }

    /** 兜底文案；编码未知时返回 null（由通道决定降级为原始 payload）。 */
    public static DefaultTemplate defaultOf(String templateCode) {
        return templateCode == null ? null : DEFAULTS.get(templateCode);
    }

    public record DefaultTemplate(String title, String content) {
    }
}
