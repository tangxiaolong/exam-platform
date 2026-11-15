package com.exam.platform.modules.message.dto;

/** 订阅授权上报。 */
public record SubscribeRequest(String templateCode, Integer times) {
}
