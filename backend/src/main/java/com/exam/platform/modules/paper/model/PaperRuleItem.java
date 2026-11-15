package com.exam.platform.modules.paper.model;

/** 规则组卷的一条规则：某题型抽 count 道，每题 score 分。 */
public record PaperRuleItem(String type, Integer count, Integer score, String difficulty) {
}
