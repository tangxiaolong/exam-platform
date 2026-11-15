package com.exam.platform.modules.exam.dto;

import java.util.List;

/** 保存答案入参：clientVersion 用于防止旧请求覆盖新答案。 */
public record SaveAnswerRequest(String requestId, List<String> answer, Integer clientVersion, Boolean marked) {
}
