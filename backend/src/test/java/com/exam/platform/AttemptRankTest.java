package com.exam.platform;

import com.exam.platform.support.ApiTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 考生本人名次。
 *
 * <p>同一场次三份成绩：本人 90、他人 90、他人 80。断言名次并列语义（1,1,3 式），
 * 以及「非本人答卷返回空对象」——名次接口不能成为探测他人答卷的入口。</p>
 */
@DisplayName("考生本人名次")
class AttemptRankTest extends ApiTestSupport {

    @Test
    @DisplayName("同分并列，非本人返回空对象")
    void rankShouldTieAndBeOwnOnly() {
        String suffix = String.valueOf(System.nanoTime() % 1000000);
        String nickA = "名次考生A" + suffix;
        String tokenA = wechatToken("rank-a-" + suffix, nickA);
        long userA = jdbcTemplate.queryForObject(
                "SELECT id FROM sys_user WHERE tenant_id = 1 AND nickname = ? ORDER BY id DESC LIMIT 1",
                Long.class, nickA);

        long sessionId = 960000L + (System.nanoTime() % 100000);
        long attemptA = insertAttempt(sessionId, userA);
        insertResult(sessionId, attemptA, userA, "90.00");
        insertResult(sessionId, 960001L, 888888L, "90.00");
        insertResult(sessionId, 960002L, 999999L, "80.00");

        var rank = data("/attempts/" + attemptA + "/rank", tokenA);
        assertThat(rank.path("rank").asInt()).as("同分并列第 1").isEqualTo(1);
        assertThat(rank.path("participantCount").asInt()).isEqualTo(3);

        var other = data("/attempts/960002/rank", tokenA);
        assertThat(other.path("rank").isMissingNode())
                .as("别人的答卷不可查，返回空对象而非报错").isTrue();
    }

    @Test
    @DisplayName("阅卷队列支持按场次过滤")
    void markingQueueShouldFilterBySession() {
        String admin = adminToken();
        long s1 = 970000L + (System.nanoTime() % 50000);
        long s2 = s1 + 1;
        insertReviewingAttempt(s1, 100001L);
        insertReviewingAttempt(s1, 100002L);
        insertReviewingAttempt(s2, 100003L);

        var filtered = data("/admin/marking/queue?status=REVIEWING&sessionId=" + s1 + "&pageNo=1&pageSize=10", admin);
        assertThat(filtered.path("total").asLong()).as("只统计本场次的 2 份").isEqualTo(2);

        var all = data("/admin/marking/queue?status=REVIEWING&pageNo=1&pageSize=10", admin);
        assertThat(all.path("total").asLong()).as("不传场次则包含全部").isGreaterThanOrEqualTo(3);
    }

    private void insertReviewingAttempt(long sessionId, long userId) {
        jdbcTemplate.update(
                "INSERT INTO exam_attempt (tenant_id, session_id, registration_id, user_id, paper_snapshot_id, "
                        + "attempt_no, status, submitted_at, scoring_version, answer_version, "
                        + "created_at, updated_at, deleted) "
                        + "VALUES (1, ?, 1, ?, 1, 1, 'REVIEWING', NOW(), 1, 0, NOW(), NOW(), 0)",
                sessionId, userId);
    }

    private long insertAttempt(long sessionId, long userId) {
        jdbcTemplate.update(
                "INSERT INTO exam_attempt (tenant_id, session_id, registration_id, user_id, paper_snapshot_id, "
                        + "attempt_no, status, submitted_at, scoring_version, answer_version, "
                        + "created_at, updated_at, deleted) "
                        + "VALUES (1, ?, 1, ?, 1, 1, 'GRADED', NOW(), 1, 0, NOW(), NOW(), 0)",
                sessionId, userId);
        return jdbcTemplate.queryForObject("SELECT MAX(id) FROM exam_attempt", Long.class);
    }

    private void insertResult(long sessionId, long attemptId, long userId, String score) {
        jdbcTemplate.update(
                "INSERT INTO exam_result (tenant_id, attempt_id, session_id, user_id, total_score, full_score, "
                        + "correct_count, question_count, scoring_version, status, revision, detail_json, "
                        + "created_at, updated_at, deleted) "
                        + "VALUES (1, ?, ?, ?, ?, 100, 0, 0, 1, 'PUBLISHED', 1, '[]', NOW(), NOW(), 0)",
                attemptId, sessionId, userId, score);
    }
}
