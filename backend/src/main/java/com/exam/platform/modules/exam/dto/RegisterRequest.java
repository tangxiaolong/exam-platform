package com.exam.platform.modules.exam.dto;

/** 报名入参：requestId 用于幂等。 */
public record RegisterRequest(String requestId, String contact) {
}
