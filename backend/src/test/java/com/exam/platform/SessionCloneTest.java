package com.exam.platform;

import com.exam.platform.support.ApiTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 场次复制：沿用源场次的规则（试卷/时长/迟到/名额/须知），只换名称与时间窗，
 * 生成为新草稿。源场次直接插入数据库（克隆路径不要求源场次可编辑）。
 */
@DisplayName("场次复制")
class SessionCloneTest extends ApiTestSupport {

    @Test
    @DisplayName("复制沿用规则并生成新草稿")
    void cloneShouldCopyRulesAndCreateDraft() {
        String admin = adminToken();
        long projectId = firstProjectId(admin);
        long subjectId = firstSubjectId(admin, projectId);
        createQuestion(admin, subjectId, "复制测试题-1");
        createQuestion(admin, subjectId, "复制测试题-2");
        long paperId = data(HttpMethod.POST, "/admin/papers", admin, Map.of(
                "projectId", projectId,
                "subjectId", subjectId,
                "name", "复制用试卷",
                "mode", "RULE",
                "rules", List.of(Map.of("type", "SINGLE", "count", 2, "score", 5))))
                .path("id").asLong();

        long sessionId = insertSession(projectId, paperId);

        LocalDateTime start = LocalDateTime.now().plusDays(8);
        LocalDateTime end = start.plusHours(2);
        LocalDateTime registerStart = LocalDateTime.now().plusDays(1);
        LocalDateTime registerEnd = start.minusMinutes(10);

        var cloned = data(HttpMethod.POST, "/admin/sessions/" + sessionId + "/clone", admin, Map.of(
                "name", "复制出的场次",
                "registerStartAt", registerStart.toString(),
                "registerEndAt", registerEnd.toString(),
                "startAt", start.toString(),
                "endAt", end.toString()));

        assertThat(cloned.path("id").asLong()).as("是新记录").isNotEqualTo(sessionId);
        assertThat(cloned.path("name").asText()).isEqualTo("复制出的场次");
        assertThat(cloned.path("projectId").asLong()).as("沿用项目").isEqualTo(projectId);
        assertThat(cloned.path("paperId").asLong()).as("沿用试卷").isEqualTo(paperId);
        assertThat(cloned.path("durationMinutes").asInt()).as("沿用时长").isEqualTo(120);
        assertThat(cloned.path("lateLimitMinutes").asInt()).as("沿用迟到限制").isEqualTo(15);
        assertThat(cloned.path("noticeVersion").asText()).as("沿用须知").isEqualTo("v9");
        assertThat(cloned.path("status").asText()).as("生成为草稿").isEqualTo("DRAFT");
    }

    private long insertSession(long projectId, long paperId) {
        jdbcTemplate.update(
                "INSERT INTO exam_session (tenant_id, project_id, paper_id, name, "
                        + "register_start_at, register_end_at, start_at, end_at, duration_minutes, "
                        + "allow_enter_before_minutes, late_limit_minutes, quota, notice_version, "
                        + "notice_content, status, created_at, updated_at, deleted) "
                        + "VALUES (1, ?, ?, '源场次', ?, ?, ?, ?, 120, 20, 15, 50, 'v9', '须知内容', "
                        + "'PUBLISHED', NOW(), NOW(), 0)",
                projectId, paperId,
                Timestamp.valueOf(LocalDateTime.now().minusDays(2)),
                Timestamp.valueOf(LocalDateTime.now().minusDays(1)),
                Timestamp.valueOf(LocalDateTime.now().minusDays(30)),
                Timestamp.valueOf(LocalDateTime.now().minusDays(20)));
        return jdbcTemplate.queryForObject("SELECT MAX(id) FROM exam_session", Long.class);
    }

    private long firstSubjectId(String admin, long projectId) {
        var subjects = data("/projects/" + projectId + "/subjects", admin);
        assertThat(subjects.size()).isPositive();
        return subjects.get(0).path("id").asLong();
    }

    private void createQuestion(String admin, long subjectId, String stem) {
        data(HttpMethod.POST, "/admin/questions", admin, Map.of(
                "subjectId", subjectId,
                "type", "SINGLE",
                "stem", stem,
                "options", List.of(Map.of("key", "A", "text", "选项A"),
                        Map.of("key", "B", "text", "选项B")),
                "answer", List.of("A"),
                "analysis", "解析",
                "difficulty", "EASY"));
    }
}
