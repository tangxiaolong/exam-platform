package com.exam.platform.modules.message;

import com.exam.platform.modules.message.entity.MsgTemplate;
import com.exam.platform.support.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 消息模板可配置化。
 *
 * <p>验的是「优先读库、读不到用兜底、都没配就不渲染」这条取值链，
 * 以及运营改文案后渲染立刻生效（不需要发版）。</p>
 */
@DisplayName("消息模板：可配置文案与兜底")
class MessageTemplateTest extends ApiTestSupport {

    @Autowired
    private MessageTemplateService templateService;

    /**
     * 每个用例都会重建库，但模板缓存是单例、不会跟着重建——
     * 不清就会读到上一个用例留下的文案，表现为「库里明明是这条、渲染出来却是那条」。
     */
    @BeforeEach
    void resetTemplateCache() {
        templateService.clearCache();
    }

    @Test
    @DisplayName("运营改文案后立刻生效，占位符按 payload 填充")
    void shouldRenderConfiguredCopy() {
        String admin = adminToken();
        JsonNode saved = data(HttpMethod.PUT, "/admin/messages/templates/PAYMENT_SUCCESS", admin,
                Map.of("title", "付款成功",
                        "content", "订单 {orderNo} 已付款 {amountFen} 分，{productName} 已开通。"));
        assertThat(saved.path("templateCode").asText()).isEqualTo("PAYMENT_SUCCESS");
        assertThat(saved.path("title").asText()).isEqualTo("付款成功");

        JsonNode list = data("/admin/messages/templates", admin);
        assertThat(list.size()).isEqualTo(1);

        String rendered = templateService.render("PAYMENT_SUCCESS", Map.of(
                        "orderNo", "ORD-2026-0001", "amountFen", 9900, "productName", "考前冲刺课"))
                .orElseThrow()
                .oneLine();
        assertThat(rendered).isEqualTo("付款成功：订单 ORD-2026-0001 已付款 9900 分，考前冲刺课 已开通。");

        assertThat(jdbcTemplate.queryForList(
                "SELECT action FROM sys_audit_log WHERE action = 'MESSAGE_TEMPLATE_UPDATE'", String.class))
                .as("改文案是运营动作，要留痕")
                .isNotEmpty();
    }

    @Test
    @DisplayName("没配模板的编码用代码兜底文案，不会发不出去")
    void shouldFallbackToBuiltInCopy() {
        String rendered = templateService.render("EXAM_REGISTER_SUCCESS", Map.of(
                        "sessionName", "2026 秋考", "ticketNo", "TK-0001", "startAt", "2026-11-20 09:00"))
                .orElseThrow()
                .oneLine();
        assertThat(rendered).contains("2026 秋考").contains("TK-0001").contains("2026-11-20 09:00");
    }

    @Test
    @DisplayName("模板停用后回退兜底文案；未知占位符原样保留")
    void shouldFallbackWhenDisabledAndKeepUnknownPlaceholder() {
        String admin = adminToken();
        data(HttpMethod.PUT, "/admin/messages/templates/EXAM_RESULT_PUBLISHED", admin,
                Map.of("title", "成绩单", "content", "总分 {totalScore}，{notAKey} 未定义", "enabled", false));

        String rendered = templateService.render("EXAM_RESULT_PUBLISHED", Map.of(
                        "totalScore", 82, "fullScore", 100, "attemptId", 7))
                .orElseThrow()
                .oneLine();
        // 停用 → 用兜底文案（兜底里含 fullScore/attemptId）
        assertThat(rendered).contains("82").contains("100");

        // 启用后无关键名原样保留，便于运维一眼看出模板写错了键
        data(HttpMethod.PUT, "/admin/messages/templates/EXAM_RESULT_PUBLISHED", admin,
                Map.of("title", "成绩单", "content", "总分 {totalScore}，{notAKey} 未定义", "enabled", true));
        String withUnknownKey = templateService.render("EXAM_RESULT_PUBLISHED",
                Map.of("totalScore", 82)).orElseThrow().oneLine();
        assertThat(withUnknownKey).isEqualTo("成绩单：总分 82，{notAKey} 未定义");
    }

    @Test
    @DisplayName("缓存的是模板原文：同一模板对不同 payload 渲染出各自的内容")
    void shouldRenderPerPayloadFromCache() {
        String admin = adminToken();
        data(HttpMethod.PUT, "/admin/messages/templates/PAYMENT_SUCCESS", admin,
                Map.of("title", "付款成功", "content", "订单 {orderNo} 已付款。"));

        String first = templateService.render("PAYMENT_SUCCESS", Map.of("orderNo", "ORD-A"))
                .orElseThrow().oneLine();
        String second = templateService.render("PAYMENT_SUCCESS", Map.of("orderNo", "ORD-B"))
                .orElseThrow().oneLine();

        assertThat(first).isEqualTo("付款成功：订单 ORD-A 已付款。");
        assertThat(second)
                .as("缓存模板原文而不是渲染结果，否则所有人的消息会变成同一个人的内容")
                .isEqualTo("付款成功：订单 ORD-B 已付款。");
    }

    @Test
    @DisplayName("编码完全未知时不渲染，由通道退回原始 payload")
    void shouldReturnEmptyForUnknownCode() {
        assertThat(templateService.render("NO_SUCH_TEMPLATE", Map.of())).isEmpty();
        assertThat(MsgTemplate.ENABLED).isEqualTo(1);
    }
}
