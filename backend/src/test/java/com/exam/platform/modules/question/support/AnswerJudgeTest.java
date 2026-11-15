package com.exam.platform.modules.question.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("客观题判分规则")
class AnswerJudgeTest {

    @Test
    @DisplayName("多选答案与选项顺序无关")
    void shouldIgnoreOrder() {
        assertThat(AnswerJudge.isCorrect(List.of("A", "C"), List.of("C", "A"))).isTrue();
    }

    @Test
    @DisplayName("答案比较忽略大小写与首尾空格")
    void shouldNormalizeCaseAndSpace() {
        assertThat(AnswerJudge.isCorrect(List.of("A", "C"), List.of(" a", "c "))).isTrue();
    }

    @Test
    @DisplayName("漏选不得分（首期规则：全对才得分）")
    void partialSelectionShouldBeWrong() {
        assertThat(AnswerJudge.isCorrect(List.of("A", "C"), List.of("A"))).isFalse();
    }

    @Test
    @DisplayName("多选不得分")
    void extraSelectionShouldBeWrong() {
        assertThat(AnswerJudge.isCorrect(List.of("A", "C"), List.of("A", "C", "D"))).isFalse();
    }

    @Test
    @DisplayName("未作答不得分")
    void emptyGivenShouldBeWrong() {
        assertThat(AnswerJudge.isCorrect(List.of("A"), List.of())).isFalse();
        assertThat(AnswerJudge.isCorrect(List.of("A"), null)).isFalse();
    }

    @Test
    @DisplayName("标准答案缺失时不得判为正确")
    void emptyStandardShouldNeverBeCorrect() {
        assertThat(AnswerJudge.isCorrect(List.of(), List.of())).isFalse();
        assertThat(AnswerJudge.isCorrect(null, List.of())).isFalse();
    }
}
