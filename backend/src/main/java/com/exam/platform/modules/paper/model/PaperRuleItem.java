package com.exam.platform.modules.paper.model;

import java.util.Map;

/**
 * 规则组卷的一条规则：某题型抽 count 道，每题 score 分。
 *
 * <p>{@code difficulty} 与 {@code difficultyMix} 互斥：前者表示「只要这个难度」，
 * 后者表示「按配比在多个难度间分配」。同时给出时应用层直接拒绝——
 * 否则「规则说 HARD、配比又给 EASY:2」就变成未定义行为，只能靠猜。</p>
 *
 * <p>{@code difficultyMix} 的值是**权重**不是百分比，权重之和不要求等于 100，
 * 「2/5/3」与「20/50/30」表达同一件事。</p>
 *
 * <p>{@code knowledgePointId} 一条规则只表达一个知识点，要覆盖多个就配多条规则，
 * 这样每条规则都能独立报错（哪个知识点缺几道）。</p>
 */
public record PaperRuleItem(String type, Integer count, Integer score, String difficulty,
                            Long knowledgePointId, Map<String, Integer> difficultyMix) {

    /** 兼容既有调用方：最早的四参写法。 */
    public PaperRuleItem(String type, Integer count, Integer score, String difficulty) {
        this(type, count, score, difficulty, null, null);
    }

    /** 兼容只带知识点的写法。 */
    public PaperRuleItem(String type, Integer count, Integer score, String difficulty,
                         Long knowledgePointId) {
        this(type, count, score, difficulty, knowledgePointId, null);
    }
}
