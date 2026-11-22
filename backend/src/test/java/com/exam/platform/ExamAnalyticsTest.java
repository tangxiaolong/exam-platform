package com.exam.platform;

import com.exam.platform.support.ApiTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 场次成绩分析。
 *
 * <p>直接往 exam_result 插成绩行，不走完整考试流程：分析只看成绩表的
 * 汇总数据，造一条真实答卷（报名→开考→作答→判分）对验证这个接口是纯开销。</p>
 */
@DisplayName("场次成绩分析")
class ExamAnalyticsTest extends ApiTestSupport {

    @Test
    @DisplayName("汇总：人数 / 均分 / 最高最低 / 及格率 / 分布")
    void analyticsShouldSummarizeScores() {
        String admin = adminToken();
        long sessionId = 900000L + (System.nanoTime() % 100000);
        insertResult(sessionId, 1L, "85.00", 100);
        insertResult(sessionId, 2L, "95.00", 100);
        insertResult(sessionId, 3L, "55.00", 100);
        insertResult(sessionId, 4L, "75.00", 100);

        var r = data("/admin/sessions/" + sessionId + "/analytics", admin);

        assertThat(r.path("participantCount").asInt()).isEqualTo(4);
        assertThat(new BigDecimal(r.path("maxScore").asText())).isEqualByComparingTo("95");
        assertThat(new BigDecimal(r.path("minScore").asText())).isEqualByComparingTo("55");
        assertThat(new BigDecimal(r.path("avgScore").asText())).isEqualByComparingTo("77.5");
        assertThat(new BigDecimal(r.path("passRate").asText()))
                .as("4 人里 3 人 ≥60%，及格率 75%").isEqualByComparingTo("75");
        assertThat(r.path("distribution").get(0).path("count").asInt())
                .as("55 分落在 0-59% 档").isEqualTo(1);
        assertThat(r.path("distribution").get(4).path("count").asInt())
                .as("95 分落在 90-100% 档").isEqualTo(1);
    }

    @Test
    @DisplayName("没有成绩的场次返回全零，不报错")
    void emptySessionShouldReturnZeros() {
        String admin = adminToken();
        long sessionId = 910000L + (System.nanoTime() % 100000);

        var r = data("/admin/sessions/" + sessionId + "/analytics", admin);

        assertThat(r.path("participantCount").asInt()).isEqualTo(0);
        assertThat(new BigDecimal(r.path("avgScore").asText())).isEqualByComparingTo("0");
        assertThat(r.path("distribution").size()).as("五个分数段恒返回，前端不用另做空态").isEqualTo(5);
    }

    @Test
    @DisplayName("逐题正确率：按正确率升序，最差的题排最前")
    void questionAccuracyShouldRankWorstFirst() {
        String admin = adminToken();
        long sessionId = 920000L + (System.nanoTime() % 100000);
        // 两份答卷：题 1 两人都对（100%），题 2 一对一错（50%）
        insertResultWithDetail(sessionId, 1L, true, true);
        insertResultWithDetail(sessionId, 2L, true, false);

        var rows = data("/admin/sessions/" + sessionId + "/question-accuracy", admin);

        assertThat(rows.size()).isEqualTo(2);
        assertThat(rows.get(0).path("questionId").asLong())
                .as("正确率最低的题排最前").isEqualTo(2);
        assertThat(new BigDecimal(rows.get(0).path("accuracy").asText()))
                .as("题 2 两人作答一人答对").isEqualByComparingTo("50");
        assertThat(rows.get(0).path("answered").asInt()).isEqualTo(2);
        assertThat(rows.get(0).path("correct").asInt()).isEqualTo(1);
        assertThat(new BigDecimal(rows.get(1).path("accuracy").asText()))
                .as("题 1 两人全对").isEqualByComparingTo("100");
    }

    @Test
    @DisplayName("成绩排行：同分并列、后续跳号")
    void leaderboardShouldRankWithTies() {
        String admin = adminToken();
        long sessionId = 950000L + (System.nanoTime() % 100000);
        insertResult(sessionId, 1L, "90.00", 100);
        insertResult(sessionId, 2L, "90.00", 100);
        insertResult(sessionId, 3L, "80.00", 100);

        var rows = data("/admin/sessions/" + sessionId + "/leaderboard", admin);

        assertThat(rows.size()).isEqualTo(3);
        assertThat(rows.get(0).path("rank").asInt()).isEqualTo(1);
        assertThat(rows.get(1).path("rank").asInt())
                .as("同分并列第 1").isEqualTo(1);
        assertThat(rows.get(2).path("rank").asInt())
                .as("并列后跳号到第 3").isEqualTo(3);
        assertThat(new BigDecimal(rows.get(0).path("totalScore").asText()))
                .as("最高分在首位").isEqualByComparingTo("90");
    }

    private void insertResult(long sessionId, long attemptId, String score, int fullScore) {
        jdbcTemplate.update(
                "INSERT INTO exam_result (tenant_id, attempt_id, session_id, user_id, total_score, "
                        + "full_score, correct_count, question_count, scoring_version, status, revision, "
                        + "created_at, updated_at, deleted) "
                        + "VALUES (1, ?, ?, 1, ?, ?, 0, 0, 1, 'PUBLISHED', 1, NOW(), NOW(), 0)",
                attemptId, sessionId, score, fullScore);
    }

    private void insertResultWithDetail(long sessionId, long attemptId, boolean q1Correct, boolean q2Correct) {
        jdbcTemplate.update(
                "INSERT INTO exam_result (tenant_id, attempt_id, session_id, user_id, total_score, "
                        + "full_score, correct_count, question_count, scoring_version, status, revision, "
                        + "detail_json, created_at, updated_at, deleted) "
                        + "VALUES (1, ?, ?, 1, 10.00, 20, 0, 0, 1, 'PUBLISHED', 1, ?, NOW(), NOW(), 0)",
                attemptId, sessionId, detailJson(q1Correct, q2Correct));
    }

    private String detailJson(boolean q1Correct, boolean q2Correct) {
        return "[" + detailItem(1, "题干一", q1Correct) + "," + detailItem(2, "题干二", q2Correct) + "]";
    }

    private String detailItem(long questionId, String stem, boolean correct) {
        return "{\"questionId\":" + questionId
                + ",\"type\":\"SINGLE\""
                + ",\"stem\":\"" + stem + "\""
                + ",\"options\":[]"
                + ",\"standardAnswer\":[\"A\"]"
                + ",\"yourAnswer\":[\"" + (correct ? "A" : "B") + "\"]"
                + ",\"correct\":" + correct
                + ",\"score\":5"
                + ",\"gotScore\":" + (correct ? 5 : 0)
                + ",\"analysis\":\"\""
                + ",\"remark\":\"\""
                + ",\"marked\":false}";
    }
}
