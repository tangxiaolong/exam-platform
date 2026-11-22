package com.exam.platform;

import com.exam.platform.modules.question.QuestionService;
import com.exam.platform.support.ApiTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 随机抽题的随机性本身。
 *
 * <p>改造掉 {@code ORDER BY RAND()} 之后，「抽得到题、数量对、排除生效」这些断言仍然全过，
 * 但如果洗牌失效，结果集会固定不变——现有测试一条都发现不了。
 * 这里用「连续抽 10 次，结果集不应只有一种」来兜住这类静默退化。</p>
 */
@DisplayName("随机抽题")
class RandomQuestionPickTest extends ApiTestSupport {

    @Autowired
    private QuestionService questionService;

    @Test
    @DisplayName("连续抽取应出现不同结果集")
    void picksShouldVary() {
        String admin = adminToken();
        Long subjectId = jdbcTemplate.queryForObject(
                "SELECT id FROM exam_subject WHERE deleted = 0 ORDER BY id LIMIT 1", Long.class);

        for (int i = 0; i < 20; i++) {
            data(HttpMethod.POST, "/admin/questions", admin, Map.of(
                    "subjectId", subjectId,
                    "type", "SINGLE",
                    "stem", "随机抽题测试题-" + i,
                    "options", List.of(Map.of("label", "A", "content", "选项A"),
                            Map.of("label", "B", "content", "选项B")),
                    "answer", List.of("A")));
        }

        Set<String> signatures = new HashSet<>();
        for (int i = 0; i < 10; i++) {
            String signature = questionService.randomQuestions(subjectId, 5).stream()
                    .map(q -> String.valueOf(q.getId()))
                    .sorted()
                    .collect(Collectors.joining(","));
            signatures.add(signature);
        }

        assertThat(signatures)
                .as("20 选 5 连抽 10 次只有一种结果集，说明打乱没有生效")
                .hasSizeGreaterThan(1);
    }
}
