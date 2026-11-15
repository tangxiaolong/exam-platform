package com.exam.platform.modules.message.dto;

/**
 * 消息模板文案（运营维护）。
 *
 * @param title   标题，可含 {@code {占位符}}
 * @param content 正文，可含 {@code {占位符}}
 * @param enabled 是否启用；false 表示回退到代码里的兜底文案
 */
public record MessageTemplateRequest(String title, String content, Boolean enabled) {
}
