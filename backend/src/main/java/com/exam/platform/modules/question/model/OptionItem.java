package com.exam.platform.modules.question.model;

import com.fasterxml.jackson.annotation.JsonAlias;

/**
 * 题目选项。
 *
 * <p>管理端用的是 {@code key/text}；但基线 DDL 的列注释写的是
 * {@code [{"key":"A","content":"..."}]}，小程序与部分调用方也用 {@code label/content}。
 * 两种写法都接受——否则这些字段会被静默忽略，选项变成一串空对象，
 * 而题目本身「保存成功」，直到考生看到没有选项的题才发现。</p>
 */
public record OptionItem(
        @JsonAlias("label") String key,
        @JsonAlias("content") String text
) {
}
