package com.exam.platform.modules.message;

import com.exam.platform.common.util.Json;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 本地联调用的模拟通道：不请求微信服务器，只打印并返回成功。
 *
 * <p>打开 {@code app.message.mock=false} 后该 Bean 不再装配，需要提供真实实现，
 * 否则启动时缺少 {@link MessageChannel} 会直接失败 —— 这是刻意设计，
 * 避免线上误用模拟通道而静默丢消息。</p>
 *
 * <p>返回的 {@code request} 是可读文案（而不是原始 JSON payload）：
 * 它会被写进 {@code msg_send_log}，出问题时能直接看出「当时到底想发什么」，
 * 不必再对照模板编码去猜 payload 的含义。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.message.mock", havingValue = "true", matchIfMissing = true)
public class MockMessageChannel implements MessageChannel {

    private final MessageTemplateService templateService;

    @Override
    public String name() {
        return "MOCK";
    }

    @Override
    public SendOutcome send(Long userId, String templateCode, Map<String, Object> payload) {
        // 模板没配就用代码兜底，两者都没有才退回原始 payload（消息照发，只会难看一点）
        String rendered = templateService.render(templateCode, payload)
                .map(MessageTemplateService.RenderedMessage::oneLine)
                .orElseGet(() -> Json.write(payload));

        log.info("[mock-message] userId={} template={} rendered={}", userId, templateCode, rendered);
        return SendOutcome.ok(rendered, "{\"errcode\":0,\"errmsg\":\"ok\"}");
    }
}
