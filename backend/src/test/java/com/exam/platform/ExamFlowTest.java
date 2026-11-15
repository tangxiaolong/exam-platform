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

@DisplayName("考试闭环：报名 → 开考 → 保存答案 → 恢复 → 交卷 → 判分")
class ExamFlowTest extends ApiTestSupport {

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    @Test
    @DisplayName("全流程幂等、旧版本答案不覆盖新答案、判分与快照一致")
    void fullExamFlow() {
        String admin = adminToken();
        long projectId = firstProjectId(admin);
        long subjectId = data("/projects/" + projectId + "/subjects", admin).get(0).path("id").asLong();

        long singleId = createQuestion(admin, subjectId, "SINGLE", List.of("A", "B"), List.of("A"));
        long multipleId = createQuestion(admin, subjectId, "MULTIPLE", List.of("A", "B", "C"), List.of("A", "C"));
        long paperId = createPaper(admin, projectId, subjectId, singleId, multipleId, 20);
        long sessionId = createAndPublishSession(admin, projectId, paperId);

        String student = wechatToken("exam-flow-student", "考试流程考生");

        // 1. 报名幂等
        JsonNode registration = data(HttpMethod.POST, "/exam-sessions/" + sessionId + "/registrations",
                student, Map.of("requestId", "flow-register"));
        JsonNode registrationAgain = data(HttpMethod.POST, "/exam-sessions/" + sessionId + "/registrations",
                student, Map.of("requestId", "flow-register"));
        assertThat(registrationAgain.path("registrationId").asLong())
                .isEqualTo(registration.path("registrationId").asLong());
        assertThat(registration.path("ticketNo").asText()).isNotBlank();

        // 2. 开考幂等：重复开始返回同一个 attempt，且开始/截止时间以服务端为准
        JsonNode attempt = data(HttpMethod.POST, "/exam-sessions/" + sessionId + "/attempts", student, null);
        JsonNode attemptAgain = data(HttpMethod.POST, "/exam-sessions/" + sessionId + "/attempts", student, null);
        long attemptId = attempt.path("attemptId").asLong();
        assertThat(attemptAgain.path("attemptId").asLong()).isEqualTo(attemptId);
        assertThat(attempt.path("startedAt").asText()).isNotBlank();
        assertThat(attempt.path("deadlineAt").asText()).isNotBlank();
        assertThat(attempt.path("questions").size()).isEqualTo(2);
        // 考试中绝不返回标准答案与解析
        assertThat(attempt.path("questions").get(0).has("answer")).isFalse();
        assertThat(attempt.path("questions").get(0).has("analysis")).isFalse();

        // 3. 答案版本控制：连续保存递增版本，迟到的旧版本请求不得覆盖
        JsonNode firstSave = data(HttpMethod.PUT,
                "/attempts/" + attemptId + "/answers/" + singleId, student,
                Map.of("answer", List.of("A"), "clientVersion", 0));
        assertThat(firstSave.path("saved").asBoolean()).isTrue();
        assertThat(firstSave.path("serverVersion").asInt()).isEqualTo(1);

        data(HttpMethod.PUT, "/attempts/" + attemptId + "/answers/" + singleId, student,
                Map.of("answer", List.of("A")));
        JsonNode thirdSave = data(HttpMethod.PUT,
                "/attempts/" + attemptId + "/answers/" + singleId, student,
                Map.of("answer", List.of("A")));
        assertThat(thirdSave.path("serverVersion").asInt()).isEqualTo(3);

        JsonNode staleSave = data(HttpMethod.PUT,
                "/attempts/" + attemptId + "/answers/" + singleId, student,
                Map.of("answer", List.of("B"), "clientVersion", 1));
        assertThat(staleSave.path("saved").asBoolean()).as("旧版本请求应被拒绝").isFalse();
        assertThat(staleSave.path("serverVersion").asInt()).isEqualTo(3);

        // 4. 恢复考试：以服务端已保存答案为准
        data(HttpMethod.PUT, "/attempts/" + attemptId + "/answers/" + multipleId, student,
                Map.of("answer", List.of("C", "A")));
        JsonNode resume = data("/attempts/" + attemptId + "/resume", student);
        assertThat(resume.path("status").asText()).isEqualTo("IN_PROGRESS");
        assertThat(resume.path("answers").size()).isEqualTo(2);
        assertThat(answersOf(resume, singleId)).containsExactly("A");

        // 5. 交卷：客观题按快照判分，重复交卷不重复判分
        JsonNode submit = data(HttpMethod.POST, "/attempts/" + attemptId + "/submit", student,
                Map.of("requestId", "flow-submit", "reason", "USER_SUBMIT"));
        assertThat(submit.path("totalScore").asDouble()).isEqualTo(20.0);
        assertThat(submit.path("fullScore").asInt()).isEqualTo(20);
        assertThat(submit.path("correctCount").asInt()).isEqualTo(2);
        assertThat(submit.path("questionCount").asInt()).isEqualTo(2);
        assertThat(submit.path("items").size()).isEqualTo(2);

        JsonNode submitAgain = data(HttpMethod.POST, "/attempts/" + attemptId + "/submit", student,
                Map.of("requestId", "flow-submit", "reason", "USER_SUBMIT"));
        assertThat(submitAgain.path("totalScore").asDouble()).isEqualTo(20.0);

        // 6. 交卷后禁止继续作答
        String code = errorCode(HttpMethod.PUT,
                "/attempts/" + attemptId + "/answers/" + singleId, student, Map.of("answer", List.of("B")));
        assertThat(code).isEqualTo("EXAM_NOT_IN_PROGRESS");

        // 7. 成绩可追溯到快照版本与逐题明细
        JsonNode result = data("/attempts/" + attemptId + "/result", student);
        assertThat(result.path("status").asText()).isEqualTo("PUBLISHED");
        assertThat(result.path("scoringVersion").asInt()).isEqualTo(1);
        assertThat(result.path("items").get(0).path("correct").asBoolean()).isTrue();
        assertThat(result.path("items").get(0).path("standardAnswer").size()).isPositive();
    }

    @Test
    @DisplayName("未报名不能开始考试；重复发布场次被拒绝")
    void guardRules() {
        String admin = adminToken();
        long projectId = firstProjectId(admin);
        long subjectId = data("/projects/" + projectId + "/subjects", admin).get(0).path("id").asLong();
        long singleId = createQuestion(admin, subjectId, "SINGLE", List.of("A", "B"), List.of("A"));
        long judgeId = createQuestion(admin, subjectId, "JUDGE", List.of("A", "B"), List.of("B"));
        long paperId = createPaper(admin, projectId, subjectId, singleId, judgeId, 10);
        long sessionId = createAndPublishSession(admin, projectId, paperId);

        String outsider = wechatToken("exam-flow-outsider", "未报名考生");
        String notRegistered = errorCode(HttpMethod.POST,
                "/exam-sessions/" + sessionId + "/attempts", outsider, null);
        assertThat(notRegistered).isEqualTo("EXAM_NOT_REGISTERED");

        String republish = errorCode(HttpMethod.POST, "/admin/sessions/" + sessionId + "/publish", admin, null);
        assertThat(republish).isEqualTo("EXAM_SESSION_PUBLISHED");
    }

    @Test
    @DisplayName("到期后拒绝保存答案，查询成绩触发自动收卷且只判分一次")
    void autoSubmitWhenDeadlinePassed() {
        String admin = adminToken();
        long projectId = firstProjectId(admin);
        long subjectId = data("/projects/" + projectId + "/subjects", admin).get(0).path("id").asLong();
        long singleId = createQuestion(admin, subjectId, "SINGLE", List.of("A", "B"), List.of("A"));
        long judgeId = createQuestion(admin, subjectId, "JUDGE", List.of("A", "B"), List.of("B"));
        long paperId = createPaper(admin, projectId, subjectId, singleId, judgeId, 10);
        // 答题时长 0 分钟 → 开考即到期，用于确定性地覆盖超时分支
        long sessionId = createOrGetSession(admin, projectId, paperId, 0);
        data(HttpMethod.POST, "/admin/sessions/" + sessionId + "/publish", admin, null);

        String student = wechatToken("exam-flow-timeout", "超时考生");
        data(HttpMethod.POST, "/exam-sessions/" + sessionId + "/registrations", student, Map.of());
        long attemptId = data(HttpMethod.POST, "/exam-sessions/" + sessionId + "/attempts", student, null)
                .path("attemptId").asLong();

        String code = errorCode(HttpMethod.PUT,
                "/attempts/" + attemptId + "/answers/" + singleId, student, Map.of("answer", List.of("A")));
        assertThat(code).isEqualTo("EXAM_DEADLINE_PASSED");

        JsonNode result = data("/attempts/" + attemptId + "/result", student);
        assertThat(result.path("totalScore").asDouble()).as("未作答不得分").isZero();
        assertThat(result.path("items").size()).isEqualTo(2);
        assertThat(result.path("items").get(0).path("correct").asBoolean()).isFalse();

        JsonNode again = data("/attempts/" + attemptId + "/result", student);
        assertThat(again.path("totalScore").asDouble()).isZero();
        assertThat(again.path("correctCount").asInt()).isZero();
    }

    private List<String> answersOf(JsonNode resume, long questionId) {
        for (JsonNode answer : resume.path("answers")) {
            if (answer.path("questionId").asLong() == questionId) {
                return objectMapper.convertValue(answer.path("answer"),
                        objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
            }
        }
        return List.of();
    }

    private long createQuestion(String admin, long subjectId, String type,
                                List<String> optionKeys, List<String> answer) {
        List<Map<String, Object>> options = optionKeys.stream()
                .map(key -> Map.<String, Object>of("key", key, "text", "选项 " + key))
                .toList();
        JsonNode created = data(HttpMethod.POST, "/admin/questions", admin, Map.of(
                "subjectId", subjectId,
                "type", type,
                "stem", "自动化测试题目（" + type + "）",
                "options", options,
                "answer", answer,
                "analysis", "自动化测试解析",
                "difficulty", "NORMAL"));
        return created.path("id").asLong();
    }

    private long createPaper(String admin, long projectId, long subjectId,
                             long question1, long question2, int scorePerQuestion) {
        List<Map<String, Object>> questions = List.of(
                Map.<String, Object>of("questionId", question1, "score", scorePerQuestion / 2),
                Map.<String, Object>of("questionId", question2, "score", scorePerQuestion / 2));
        JsonNode created = data(HttpMethod.POST, "/admin/papers", admin, Map.of(
                "projectId", projectId,
                "subjectId", subjectId,
                "name", "自动化测试试卷",
                "mode", "FIXED",
                "questions", questions));
        return created.path("id").asLong();
    }

    private long createAndPublishSession(String admin, long projectId, long paperId) {
        long sessionId = createOrGetSession(admin, projectId, paperId);
        data(HttpMethod.POST, "/admin/sessions/" + sessionId + "/publish", admin, null);
        return sessionId;
    }

    private long createOrGetSession(String admin, long projectId, long paperId) {
        return createOrGetSession(admin, projectId, paperId, 30);
    }

    private long createOrGetSession(String admin, long projectId, long paperId, int durationMinutes) {
        LocalDateTime now = LocalDateTime.now();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("projectId", projectId);
        body.put("paperId", paperId);
        body.put("name", "自动化测试场次");
        body.put("registerStartAt", now.minusHours(1).format(TIME_FORMAT));
        body.put("registerEndAt", now.plusHours(1).format(TIME_FORMAT));
        body.put("startAt", now.format(TIME_FORMAT));
        body.put("endAt", now.plusHours(2).format(TIME_FORMAT));
        body.put("durationMinutes", durationMinutes);
        body.put("allowEnterBeforeMinutes", 60);
        body.put("quota", 50);
        body.put("noticeContent", "自动化测试须知");
        JsonNode created = data(HttpMethod.POST, "/admin/sessions", admin, body);
        return created.path("id").asLong();
    }
}
