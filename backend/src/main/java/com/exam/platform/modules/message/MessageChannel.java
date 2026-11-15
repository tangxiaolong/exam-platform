package com.exam.platform.modules.message;

import java.util.Map;

/**
 * 消息通道抽象。真实接入微信订阅消息时实现该类即可，业务侧无需改动。
 */
public interface MessageChannel {

    String name();

    /**
     * 投递一条消息。
     *
     * @return 通道返回的原始响应，便于落库排查
     */
    SendOutcome send(Long userId, String templateCode, Map<String, Object> payload);

    /** 发送结果 */
    record SendOutcome(boolean success, String request, String response, String error) {

        public static SendOutcome ok(String request, String response) {
            return new SendOutcome(true, request, response, null);
        }

        public static SendOutcome fail(String request, String response, String error) {
            return new SendOutcome(false, request, response, error);
        }
    }
}
