package com.exam.platform;

import com.exam.platform.support.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 错题本与收藏。
 *
 * <p>断言方式刻意不依赖「正确答案是什么」：规则组卷会从整个题库抽题，
 * 历史题目的答案不可预知。这里改成「以判分明细为准反推应出现在错题本里的题目集合」，
 * 断言的是系统行为，而不是测试自己构造的数据。</p>
 */
@DisplayName("错题本：交卷写入、幂等累加、掌握标记、收藏复用")
class WrongBookFlowTest extends ApiTestSupport {

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    @Test
    @DisplayName("交卷判分后，答错的题自动进入错题本并标注来源")
    void submittedWrongAnswersShouldEnterWrongBook() {
        String admin = adminToken();
        long projectId = firstProjectId(admin);
        long subjectId = firstSubjectId(admin, projectId);
        createQuestion(admin, subjectId);
        createQuestion(admin, subjectId);
        long sessionId = createAndPublishSession(admin, projectId,
                createRulePaper(admin, projectId, subjectId, 2));

        String student = wechatToken("wrong-book-student", "错题本考生");
        JsonNode attempt = startAttempt(student, sessionId);
        JsonNode result = submitWithAllWrong(student, attempt);

        Set<Long> wrongIds = wrongQuestionIdsIn(result);
        assertThat(wrongIds).as("规则组卷抽到的题全部答错").isNotEmpty();

        JsonNode examWrong = data("/wrong-questions?source=EXAM", student);
        Set<Long> inBook = new HashSet<>();
        for (JsonNode item : examWrong) {
            assertThat(item.path("source").asText()).isEqualTo("EXAM");
            // 科目名是前端做筛选的唯一依据：只有 subjectId 的话界面上只能显示「科目 3」
            assertThat(item.path("subjectName").asText())
                    .as("错题条目必须带科目名").isNotBlank();
            inBook.add(item.path("question").path("id").asLong());
        }
        assertThat(inBook).as("错题本必须与判分明细里的错题完全一致").isEqualTo(wrongIds);

        // 这位考生没做过练习，练习来源应为空——证明两源分开取、合并返回
        assertThat(data("/wrong-questions?source=PRACTICE", student).size()).isZero();
        assertThat(data("/wrong-questions", student).size()).isEqualTo(wrongIds.size());
    }

    @Test
    @DisplayName("重复交卷不会把错题次数再次累加")
    void repeatedSubmitShouldNotDoubleCount() {
        String admin = adminToken();
        long projectId = firstProjectId(admin);
        long subjectId = firstSubjectId(admin, projectId);
        createQuestion(admin, subjectId);
        createQuestion(admin, subjectId);
        long sessionId = createAndPublishSession(admin, projectId,
                createRulePaper(admin, projectId, subjectId, 1));

        String student = wechatToken("wrong-book-repeat", "重复交卷考生");
        JsonNode attempt = startAttempt(student, sessionId);
        long attemptId = attempt.path("attemptId").asLong();
        Set<Long> wrongIds = wrongQuestionIdsIn(submitWithAllWrong(student, attempt));
        assertThat(wrongIds).isNotEmpty();

        Long userId = jdbcTemplate.queryForObject(
                "SELECT id FROM sys_user WHERE tenant_id = 1 ORDER BY id DESC LIMIT 1", Long.class);
        List<Integer> countsBefore = jdbcTemplate.queryForList(
                "SELECT wrong_count FROM exam_wrong_question WHERE tenant_id = 1 AND user_id = ? "
                        + "ORDER BY question_id", Integer.class, userId);
        assertThat(countsBefore).isNotEmpty().allMatch(c -> c == 1);

        // 再交一次卷：返回既有成绩，错题次数不能变成 2
        data(HttpMethod.POST, "/attempts/" + attemptId + "/submit", student,
                Map.of("reason", "USER_SUBMIT"));
        List<Integer> countsAfter = jdbcTemplate.queryForList(
                "SELECT wrong_count FROM exam_wrong_question WHERE tenant_id = 1 AND user_id = ? "
                        + "ORDER BY question_id", Integer.class, userId);
        assertThat(countsAfter).as("重复交卷不能让错题次数虚增").isEqualTo(countsBefore);
    }

    @Test
    @DisplayName("掌握标记只对错题本里的题生效")
    void masteredShouldOnlyApplyToRecordedQuestions() {
        String admin = adminToken();
        long projectId = firstProjectId(admin);
        long subjectId = firstSubjectId(admin, projectId);
        createQuestion(admin, subjectId);
        long sessionId = createAndPublishSession(admin, projectId,
                createRulePaper(admin, projectId, subjectId, 1));

        String student = wechatToken("wrong-book-mastered", "掌握标记考生");
        JsonNode attempt = startAttempt(student, sessionId);
        Set<Long> wrongIds = wrongQuestionIdsIn(submitWithAllWrong(student, attempt));
        assertThat(wrongIds).isNotEmpty();
        long questionId = wrongIds.iterator().next();

        data(HttpMethod.POST, "/wrong-questions/" + questionId + "/mastered?mastered=true", student, null);
        assertThat(data("/wrong-questions?source=EXAM", student).size()).isEqualTo(wrongIds.size());

        // 不在错题本里的题标记掌握：必须报错，不能静默成功让前端以为保存了
        assertThat(errorCode(HttpMethod.POST,
                "/wrong-questions/99999999/mastered?mastered=true", student, null))
                .isEqualTo("WRONG_QUESTION_NOT_FOUND");
    }

    @Test
    @DisplayName("收藏取消后再收藏可以成功（软删记录复用）")
    void favoriteShouldSurviveRemoveAndReAdd() {
        String admin = adminToken();
        long projectId = firstProjectId(admin);
        long subjectId = firstSubjectId(admin, projectId);
        createQuestion(admin, subjectId);
        long sessionId = createAndPublishSession(admin, projectId,
                createRulePaper(admin, projectId, subjectId, 1));

        String student = wechatToken("wrong-book-favorite", "收藏考生");
        JsonNode attempt = startAttempt(student, sessionId);
        Set<Long> wrongIds = wrongQuestionIdsIn(submitWithAllWrong(student, attempt));
        assertThat(wrongIds).isNotEmpty();
        long questionId = wrongIds.iterator().next();

        data(HttpMethod.POST, "/favorites/" + questionId, student, null);
        assertThat(data("/favorites", student).size()).isEqualTo(1);

        // 重复收藏幂等
        data(HttpMethod.POST, "/favorites/" + questionId, student, null);
        assertThat(data("/favorites", student).size()).isEqualTo(1);

        data(HttpMethod.DELETE, "/favorites/" + questionId, student, null);
        assertThat(data("/favorites", student).size()).isZero();

        // 关键：软删后唯一索引仍在，不处理「复活」就会永久收藏失败
        data(HttpMethod.POST, "/favorites/" + questionId, student, null);
        assertThat(data("/favorites", student).size()).isEqualTo(1);
    }

    @Test
    @DisplayName("按科目筛选错题本：本学科有，其他学科没有")
    void wrongBookShouldFilterBySubject() {
        String admin = adminToken();
        long projectId = firstProjectId(admin);
        long subjectId = firstSubjectId(admin, projectId);
        createQuestion(admin, subjectId);
        long sessionId = createAndPublishSession(admin, projectId,
                createRulePaper(admin, projectId, subjectId, 1));

        String student = wechatToken("wrong-book-subject", "科目筛选考生");
        JsonNode attempt = startAttempt(student, sessionId);
        Set<Long> wrongIds = wrongQuestionIdsIn(submitWithAllWrong(student, attempt));
        assertThat(wrongIds).isNotEmpty();

        assertThat(data("/wrong-questions?subjectId=" + subjectId, student).size())
                .isEqualTo(wrongIds.size());

        // 不存在的科目：过滤生效时为空；若参数被忽略，这里会返回全部错题
        assertThat(data("/wrong-questions?subjectId=99999999", student).size()).isZero();
    }

    // ---------------------------------------------------------------- helpers

    private Set<Long> wrongQuestionIdsIn(JsonNode result) {
        Set<Long> ids = new HashSet<>();
        for (JsonNode item : result.path("items")) {
            if (!item.path("correct").asBoolean()) {
                ids.add(item.path("questionId").asLong());
            }
        }
        return ids;
    }

    /** 把整份卷子的题都答成 B（客观题），交卷并返回成绩。 */
    private JsonNode submitWithAllWrong(String student, JsonNode attempt) {
        long attemptId = attempt.path("attemptId").asLong();
        for (JsonNode question : attempt.path("questions")) {
            data(HttpMethod.PUT, "/attempts/" + attemptId + "/answers/"
                    + question.path("questionId").asLong(), student, Map.of("answer", List.of("B")));
        }
        return data(HttpMethod.POST, "/attempts/" + attemptId + "/submit", student,
                Map.of("reason", "USER_SUBMIT"));
    }

    /** 报名并开考，返回开考响应（含 attemptId 与题目列表；没有单独的答卷查询接口）。 */
    private JsonNode startAttempt(String student, long sessionId) {
        data(HttpMethod.POST, "/exam-sessions/" + sessionId + "/registrations", student, Map.of());
        return data(HttpMethod.POST, "/exam-sessions/" + sessionId + "/attempts", student, null);
    }

    private long firstSubjectId(String admin, long projectId) {
        JsonNode subjects = data("/projects/" + projectId + "/subjects", admin);
        assertThat(subjects.size()).isPositive();
        return subjects.get(0).path("id").asLong();
    }

    /** 正确答案固定为 A，考生统一答 B，保证是错题。 */
    private void createQuestion(String admin, long subjectId) {
        data(HttpMethod.POST, "/admin/questions", admin, Map.of(
                "subjectId", subjectId,
                "type", "SINGLE",
                "stem", "错题本测试题",
                "options", List.of(Map.of("key", "A", "text", "选项 A"),
                        Map.of("key", "B", "text", "选项 B")),
                "answer", List.of("A"),
                "analysis", "解析",
                "difficulty", "NORMAL"));
    }

    private long createRulePaper(String admin, long projectId, long subjectId, int count) {
        return data(HttpMethod.POST, "/admin/papers", admin, Map.of(
                "projectId", projectId,
                "subjectId", subjectId,
                "name", "错题本规则卷",
                "mode", "RULE",
                "rules", List.of(Map.of("type", "SINGLE", "count", count, "score", 10))))
                .path("id").asLong();
    }

    private long createAndPublishSession(String admin, long projectId, long paperId) {
        LocalDateTime now = LocalDateTime.now();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("projectId", projectId);
        body.put("paperId", paperId);
        body.put("name", "错题本场次");
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
