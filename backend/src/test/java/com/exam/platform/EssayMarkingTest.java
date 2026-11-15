package com.exam.platform;

import com.exam.platform.support.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("主观题：交卷待阅卷 → 人工打分 → 发布 → 成绩更正留痕")
class EssayMarkingTest extends ApiTestSupport {

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    @Test
    @DisplayName("含主观题的考试进入 REVIEWING，评分完成后才出成绩")
    void essayShouldRequireManualMarking() {
        String admin = adminToken();
        long projectId = firstProjectId(admin);
        long subjectId = firstSubjectId(admin, projectId);

        long singleId = createSingleQuestion(admin, subjectId);
        long essayId = createEssayQuestion(admin, subjectId);
        long paperId = createPaper(admin, projectId, subjectId, singleId, essayId);
        long sessionId = createAndPublishSession(admin, projectId, paperId);

        String student = wechatToken("essay-student", "主观题考生");
        data(HttpMethod.POST, "/exam-sessions/" + sessionId + "/registrations", student, Map.of());
        JsonNode attempt = data(HttpMethod.POST, "/exam-sessions/" + sessionId + "/attempts", student, null);
        long attemptId = attempt.path("attemptId").asLong();
        assertThat(attempt.path("questions").size()).isEqualTo(2);
        assertThat(attempt.path("questions").get(1).path("type").asText()).isEqualTo("ESSAY");

        data(HttpMethod.PUT, "/attempts/" + attemptId + "/answers/" + singleId, student,
                Map.of("answer", List.of("A")));
        data(HttpMethod.PUT, "/attempts/" + attemptId + "/answers/" + essayId, student,
                Map.of("answer", List.of("我的作答：先范围后时间再成本")));

        // 交卷后进入待阅卷：客观题已判分，主观题不计分，明细对考生不可见
        JsonNode submitted = data(HttpMethod.POST, "/attempts/" + attemptId + "/submit", student,
                Map.of("reason", "USER_SUBMIT"));
        assertThat(submitted.path("status").asText()).isEqualTo("REVIEWING");
        assertThat(submitted.path("totalScore").asDouble()).as("只算客观题").isEqualTo(10.0);
        assertThat(submitted.path("fullScore").asInt()).isEqualTo(20);
        assertThat(submitted.path("items").size()).isZero();

        JsonNode studentResult = data("/attempts/" + attemptId + "/result", student);
        assertThat(studentResult.path("status").asText()).isEqualTo("REVIEWING");
        assertThat(studentResult.path("items").size()).isZero();

        // 阅卷队列包含该作答
        JsonNode queue = data("/admin/marking/queue?pageNo=1&pageSize=50", admin);
        assertThat(queue.path("total").asLong()).isPositive();

        // 阅卷列表只有主观题
        JsonNode markingItems = data("/admin/attempts/" + attemptId + "/marking", admin);
        assertThat(markingItems.size()).isEqualTo(1);
        assertThat(markingItems.get(0).path("questionId").asLong()).isEqualTo(essayId);
        assertThat(markingItems.get(0).path("fullScore").asInt()).isEqualTo(10);
        assertThat(markingItems.get(0).path("marked").asBoolean()).isFalse();

        // 未评分不得结束阅卷
        assertThat(errorCode(HttpMethod.POST, "/admin/attempts/" + attemptId + "/marking/finish", admin, null))
                .isEqualTo("EXAM_MARKING_INCOMPLETE");

        // 超分值校验
        assertThat(errorCode(HttpMethod.PUT,
                "/admin/attempts/" + attemptId + "/marking/" + essayId + "?score=99", admin, null))
                .isEqualTo("EXAM_MARKING_SCORE_INVALID");

        // 打分 8 分后结束阅卷 → 成绩生效并发布
        JsonNode marked = data(HttpMethod.PUT,
                "/admin/attempts/" + attemptId + "/marking/" + essayId + "?score=8&remark=要点基本齐全",
                admin, null);
        assertThat(marked.path("marked").asBoolean()).isTrue();
        assertThat(marked.path("score").asInt()).isEqualTo(8);

        JsonNode finished = data(HttpMethod.POST, "/admin/attempts/" + attemptId + "/marking/finish", admin, null);
        assertThat(finished.path("status").asText()).isEqualTo("PUBLISHED");
        assertThat(finished.path("totalScore").asDouble()).isEqualTo(18.0);
        assertThat(finished.path("items").size()).isEqualTo(2);

        JsonNode studentView = data("/attempts/" + attemptId + "/result", student);
        assertThat(studentView.path("totalScore").asDouble()).isEqualTo(18.0);
        assertThat(studentView.path("items").size()).isEqualTo(2);
        JsonNode essayItem = findItem(studentView, essayId);
        assertThat(essayItem.path("gotScore").asInt()).isEqualTo(8);
        assertThat(essayItem.path("remark").asText()).isEqualTo("要点基本齐全");

        // 成绩更正：必须填原因，更正后总分变化且保留历史版本
        assertThat(errorCode(HttpMethod.POST,
                "/admin/attempts/" + attemptId + "/results/correct?questionId=" + essayId + "&score=10&reason=",
                admin, null)).isEqualTo("COMMON_BAD_REQUEST");

        JsonNode corrected = data(HttpMethod.POST,
                "/admin/attempts/" + attemptId + "/results/correct?questionId=" + essayId
                        + "&score=10&reason="
                        + java.net.URLEncoder.encode("考生申诉，复核后调整", java.nio.charset.StandardCharsets.UTF_8),
                admin, null);
        assertThat(corrected.path("totalScore").asDouble()).isEqualTo(20.0);
        assertThat(findItem(corrected, essayId).path("gotScore").asInt()).isEqualTo(10);

        JsonNode history = data("/admin/attempts/" + attemptId + "/results/history", admin);
        assertThat(history.size()).isEqualTo(2);
        assertThat(history.get(0).path("operation").asText()).isEqualTo("PUBLISH");
        assertThat(history.get(1).path("operation").asText()).isEqualTo("CORRECT");
        assertThat(history.get(1).path("revision").asInt()).isEqualTo(2);
        assertThat(history.get(1).path("totalScore").asDouble()).isEqualTo(20.0);

        // 考生看到更正后的分数
        assertThat(data("/attempts/" + attemptId + "/result", student).path("totalScore").asDouble())
                .isEqualTo(20.0);
    }

    private JsonNode findItem(JsonNode result, long questionId) {
        for (JsonNode item : result.path("items")) {
            if (item.path("questionId").asLong() == questionId) {
                return item;
            }
        }
        throw new AssertionError("成绩明细中找不到题目 " + questionId);
    }

    private long firstSubjectId(String admin, long projectId) {
        return data("/projects/" + projectId + "/subjects", admin).get(0).path("id").asLong();
    }

    private long createSingleQuestion(String admin, long subjectId) {
        return data(HttpMethod.POST, "/admin/questions", admin, Map.of(
                "subjectId", subjectId,
                "type", "SINGLE",
                "stem", "主观题测试-客观题",
                "options", List.of(Map.of("key", "A", "text", "选项A"), Map.of("key", "B", "text", "选项B")),
                "answer", List.of("A"),
                "analysis", "解析",
                "difficulty", "NORMAL")).path("id").asLong();
    }

    private long createEssayQuestion(String admin, long subjectId) {
        return data(HttpMethod.POST, "/admin/questions", admin, Map.of(
                "subjectId", subjectId,
                "type", "ESSAY",
                "stem", "简述项目铁三角及其关系。",
                "options", List.of(),
                "answer", List.of("范围、时间、成本相互制约"),
                "analysis", "按要点给分",
                "difficulty", "NORMAL")).path("id").asLong();
    }

    private long createPaper(String admin, long projectId, long subjectId, long singleId, long essayId) {
        return data(HttpMethod.POST, "/admin/papers", admin, Map.of(
                "projectId", projectId,
                "subjectId", subjectId,
                "name", "主观题测试试卷",
                "mode", "FIXED",
                "questions", List.of(
                        Map.<String, Object>of("questionId", singleId, "score", 10),
                        Map.<String, Object>of("questionId", essayId, "score", 10)))).path("id").asLong();
    }

    private long createAndPublishSession(String admin, long projectId, long paperId) {
        LocalDateTime now = LocalDateTime.now();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("projectId", projectId);
        body.put("paperId", paperId);
        body.put("name", "主观题测试场次");
        body.put("registerStartAt", now.minusHours(1).format(TIME_FORMAT));
        body.put("registerEndAt", now.plusHours(1).format(TIME_FORMAT));
        body.put("startAt", now.format(TIME_FORMAT));
        body.put("endAt", now.plusHours(2).format(TIME_FORMAT));
        body.put("durationMinutes", 30);
        body.put("allowEnterBeforeMinutes", 60);
        body.put("quota", 20);
        long sessionId = data(HttpMethod.POST, "/admin/sessions", admin, body).path("id").asLong();
        data(HttpMethod.POST, "/admin/sessions/" + sessionId + "/publish", admin, null);
        return sessionId;
    }
}
