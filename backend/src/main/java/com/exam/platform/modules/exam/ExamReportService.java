package com.exam.platform.modules.exam;

import com.exam.platform.common.exception.BizException;
import com.exam.platform.common.security.UserContext;
import com.exam.platform.modules.exam.dto.ScoreHistoryItem;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

/**
 * 场次成绩分析。
 *
 * <p>只统计已判分或已发布成绩的答卷（REVIEWING 的客观题分不完整，
 * 混进来会把平均分拉低、分布失真）。人数在几百到几千量级，
 * 一次查出全量在内存里计算，比在 SQL 里用 CASE WHEN 堆五条分档语句更直白。</p>
 */
@Service
@RequiredArgsConstructor
public class ExamReportService {

    /** 及格线取满分的 60%：没有单场配置时的通用约定，接口返回里会带上这个阈值。 */
    private static final int PASS_PERCENT = 60;

    private final JdbcTemplate jdbcTemplate;

    /**
     * 多场次成绩横向对比：每个请求场次一行（无成绩也返回，count=0），给运营看场次之间的波动。
     * 只统计 exam_result（与单场次分析同源），REVIEWING 未出分的答卷天然缺席。
     */
    public List<Map<String, Object>> compare(List<Long> sessionIds) {
        if (sessionIds == null || sessionIds.isEmpty() || sessionIds.size() > 10) {
            throw BizException.badRequest("对比场次需选择 1-10 个");
        }
        String placeholders = String.join(",", sessionIds.stream().map(id -> "?").toList());
        Object[] args = new Object[sessionIds.size() + 1];
        args[0] = UserContext.tenantId();
        for (int i = 0; i < sessionIds.size(); i++) {
            args[i + 1] = sessionIds.get(i);
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT s.id AS session_id, s.name AS session_name, "
                        + "COUNT(r.id) AS result_count, "
                        + "COALESCE(AVG(r.total_score), 0) AS avg_score, "
                        + "COALESCE(MAX(r.total_score), 0) AS max_score, "
                        + "COALESCE(MIN(r.total_score), 0) AS min_score, "
                        + "COALESCE(MAX(r.full_score), 0) AS full_score "
                        + "FROM exam_session s "
                        + "LEFT JOIN exam_result r ON r.session_id = s.id "
                        + "AND r.tenant_id = s.tenant_id AND r.deleted = 0 "
                        + "WHERE s.tenant_id = ? AND s.deleted = 0 "
                        + "AND s.id IN (" + placeholders + ") "
                        + "GROUP BY s.id, s.name ORDER BY s.id DESC",
                args);
        // H2 返回大写列名、MySQL 按别名返回——统一小写，前端消费不看数据库脸色
        List<Map<String, Object>> normalized = new java.util.ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> item = new java.util.LinkedHashMap<>();
            row.forEach((key, value) -> item.put(key.toLowerCase(java.util.Locale.ROOT), value));
            normalized.add(item);
        }
        return normalized;
    }

    public ExamReportView analytics(Long sessionId) {
        List<Map<String, Object>> rows = jdbcTemplate.query(
                "SELECT total_score, full_score FROM exam_result "
                        + "WHERE tenant_id = ? AND session_id = ? AND deleted = 0 "
                        + "AND status IN ('GRADED', 'PUBLISHED')",
                (rs, i) -> Map.of(
                        "score", (Object) rs.getBigDecimal("total_score"),
                        "full", (Object) rs.getInt("full_score")),
                UserContext.tenantId(), sessionId);

        int participants = rows.size();
        if (participants == 0) {
            return new ExamReportView(0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, 0, PASS_PERCENT, List.of(band("0-59%", 0), band("60-69%", 0),
                    band("70-79%", 0), band("80-89%", 0), band("90-100%", 0)));
        }

        BigDecimal sum = BigDecimal.ZERO;
        BigDecimal max = null;
        BigDecimal min = null;
        int fullScore = 0;
        int passed = 0;
        int[] bands = new int[5];

        for (Map<String, Object> row : rows) {
            BigDecimal score = (BigDecimal) row.get("score");
            int full = (Integer) row.get("full");
            fullScore = Math.max(fullScore, full);
            sum = sum.add(score);
            if (max == null || score.compareTo(max) > 0) {
                max = score;
            }
            if (min == null || score.compareTo(min) < 0) {
                min = score;
            }
            // 满分为 0 的异常数据直接归入最低档，避免除零
            int percent = full <= 0 ? 0 : score.multiply(BigDecimal.valueOf(100))
                    .divide(BigDecimal.valueOf(full), 0, RoundingMode.DOWN).intValue();
            if (percent >= PASS_PERCENT) {
                passed++;
            }
            bands[bandIndex(percent)]++;
        }

        BigDecimal avg = sum.divide(BigDecimal.valueOf(participants), 2, RoundingMode.HALF_UP);
        BigDecimal passRate = BigDecimal.valueOf(passed).multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(participants), 2, RoundingMode.HALF_UP);

        return new ExamReportView(participants, avg, max, min, passRate, fullScore, PASS_PERCENT,
                List.of(band("0-59%", bands[0]), band("60-69%", bands[1]),
                        band("70-79%", bands[2]), band("80-89%", bands[3]),
                        band("90-100%", bands[4])));
    }

    /**
     * 逐题正确率：解析 detail_json 里的逐题判分，统计每题被作答次数与答对次数。
     *
     * <p>按正确率升序返回——正确率最低的题排最前，「哪些题该回头讲」就是这个顺序。
     * 解析复用 {@code Json.readList}，与 GradingService 写入的是同一套 JSON 格式，不另起解析。</p>
     */
    public List<ExamReportView.QuestionAccuracy> questionAccuracy(Long sessionId) {
        List<String> details = jdbcTemplate.queryForList(
                "SELECT detail_json FROM exam_result "
                        + "WHERE tenant_id = ? AND session_id = ? AND deleted = 0 "
                        + "AND status IN ('GRADED', 'PUBLISHED') AND detail_json IS NOT NULL",
                String.class, UserContext.tenantId(), sessionId);

        Map<Long, int[]> stats = new java.util.HashMap<>();
        Map<Long, com.exam.platform.modules.exam.dto.ResultItem> samples = new java.util.HashMap<>();
        for (String detail : details) {
            for (com.exam.platform.modules.exam.dto.ResultItem item : com.exam.platform.common.util.Json
                    .readList(detail, com.exam.platform.modules.exam.dto.ResultItem.class)) {
                if (item.questionId() == null) {
                    continue;
                }
                int[] stat = stats.computeIfAbsent(item.questionId(), k -> new int[2]);
                stat[0]++;
                if (item.correct()) {
                    stat[1]++;
                }
                samples.putIfAbsent(item.questionId(), item);
            }
        }

        List<ExamReportView.QuestionAccuracy> result = new java.util.ArrayList<>();
        for (Map.Entry<Long, int[]> entry : stats.entrySet()) {
            int answered = entry.getValue()[0];
            int correct = entry.getValue()[1];
            com.exam.platform.modules.exam.dto.ResultItem sample = samples.get(entry.getKey());
            BigDecimal accuracy = answered == 0 ? BigDecimal.ZERO
                    : BigDecimal.valueOf(correct).multiply(BigDecimal.valueOf(100))
                            .divide(BigDecimal.valueOf(answered), 1, RoundingMode.HALF_UP);
            result.add(new ExamReportView.QuestionAccuracy(entry.getKey(),
                    sample == null ? "" : sample.stem(),
                    sample == null ? "" : sample.type(),
                    answered, correct, accuracy));
        }
        result.sort(java.util.Comparator.comparing(ExamReportView.QuestionAccuracy::accuracy));
        return result;
    }

    /**
     * 场次成绩排行：按总分降序，同分并列、后续跳号（1,1,3 式）。
     *
     * <p>分数相同时按行序（成绩行 id 升序）排位但名次相同——「同分同列」比
     * 「同分按提交先后给先后名次」更符合排行榜语义。</p>
     */
    public List<ExamReportView.LeaderboardEntry> leaderboard(Long sessionId, int limit) {
        int size = Math.max(1, Math.min(limit, 100));
        List<Map<String, Object>> rows = jdbcTemplate.query(
                "SELECT r.user_id, r.total_score, u.nickname, u.username "
                        + "FROM exam_result r LEFT JOIN sys_user u ON u.id = r.user_id AND u.deleted = 0 "
                        + "WHERE r.tenant_id = ? AND r.session_id = ? AND r.deleted = 0 "
                        + "AND r.status IN ('GRADED', 'PUBLISHED') "
                        + "ORDER BY r.total_score DESC, r.id ASC LIMIT " + size,
                (rs, i) -> Map.of(
                        "userId", (Object) rs.getLong("user_id"),
                        "score", (Object) rs.getBigDecimal("total_score"),
                        "nickname", (Object) strOf(rs.getString("nickname")),
                        "username", (Object) strOf(rs.getString("username"))),
                UserContext.tenantId(), sessionId);

        List<ExamReportView.LeaderboardEntry> result = new java.util.ArrayList<>();
        int rank = 0;
        int idx = 0;
        BigDecimal prev = null;
        for (Map<String, Object> row : rows) {
            idx++;
            BigDecimal score = (BigDecimal) row.get("score");
            if (prev == null || score.compareTo(prev) != 0) {
                rank = idx;
            }
            prev = score;
            String nickname = (String) row.get("nickname");
            String username = (String) row.get("username");
            String name = !nickname.isEmpty() ? nickname
                    : (!username.isEmpty() ? username : "用户#" + row.get("userId"));
            result.add(new ExamReportView.LeaderboardEntry(rank, (Long) row.get("userId"), name, score));
        }
        return result;
    }

    /**
     * 考生本人名次：同分并列（1,1,3 式）；未出分返回空对象。
     *
     * <p>以「比你分高的人数 + 1」计算名次——与排行榜的跳号语义一致，同分者共享名次。
     * 只能查本人：JOIN 答卷表校验 user_id = 当前登录人，查别人的答卷返回空对象而非报错泄露。</p>
     */
    public Map<String, Object> attemptRank(Long attemptId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT r.session_id, r.total_score FROM exam_result r "
                        + "JOIN exam_attempt a ON a.id = r.attempt_id AND a.deleted = 0 "
                        + "WHERE r.tenant_id = ? AND r.attempt_id = ? AND r.deleted = 0 "
                        + "AND r.status IN ('GRADED', 'PUBLISHED') AND a.user_id = ?",
                UserContext.tenantId(), attemptId, UserContext.userId());
        if (rows.isEmpty()) {
            return Map.of();
        }
        long sessionId = ((Number) rows.get(0).get("session_id")).longValue();
        BigDecimal myScore = (BigDecimal) rows.get(0).get("total_score");
        Integer higher = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM exam_result WHERE tenant_id = ? AND session_id = ? "
                        + "AND status IN ('GRADED', 'PUBLISHED') AND deleted = 0 AND total_score > ?",
                Integer.class, UserContext.tenantId(), sessionId, myScore);
        Integer total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM exam_result WHERE tenant_id = ? AND session_id = ? "
                        + "AND status IN ('GRADED', 'PUBLISHED') AND deleted = 0",
                Integer.class, UserContext.tenantId(), sessionId);
        int rank = (higher == null ? 0 : higher) + 1;
        return Map.of("rank", rank, "participantCount", total == null ? 0 : total);
    }

    /**
     * 本人成绩历史：只取已判分/已发布成绩，先按答卷 id 取最近 N 条，再反转成时间升序，
     * 便于成绩单按「从左到右」画趋势。
     *
     * <p>带上 fullScore，前端用 totalScore/fullScore 折算百分比后再比较——
     * 不同场次满分不同，直接比原始分会把 100 分制和 150 分制混在一起。</p>
     */
    public List<ScoreHistoryItem> scoreHistory(Long userId, int limit) {
        int size = Math.max(1, Math.min(limit, 20));
        List<ScoreHistoryItem> items = jdbcTemplate.query(
                "SELECT r.attempt_id AS attempt_id, r.session_id AS session_id, s.name AS session_name, "
                        + "r.total_score AS total_score, r.full_score AS full_score, "
                        + "a.submitted_at AS submitted_at, r.status AS status "
                        + "FROM exam_result r "
                        + "JOIN exam_attempt a ON a.id = r.attempt_id AND a.tenant_id = r.tenant_id AND a.deleted = 0 "
                        + "LEFT JOIN exam_session s ON s.id = r.session_id AND s.tenant_id = r.tenant_id AND s.deleted = 0 "
                        + "WHERE r.tenant_id = ? AND a.user_id = ? AND r.deleted = 0 "
                        + "AND r.status IN ('GRADED', 'PUBLISHED') "
                        + "ORDER BY r.id DESC LIMIT " + size,
                (rs, i) -> {
                    java.sql.Timestamp submitted = rs.getTimestamp("submitted_at");
                    return new ScoreHistoryItem(
                            rs.getLong("attempt_id"),
                            rs.getLong("session_id"),
                            rs.getString("session_name"),
                            rs.getBigDecimal("total_score"),
                            rs.getInt("full_score"),
                            submitted == null ? null : submitted.toLocalDateTime(),
                            rs.getString("status"));
                },
                UserContext.tenantId(), userId);
        // 查询按「最近在前」取数，反转成时间升序供前端画曲线
        java.util.Collections.reverse(items);
        return items;
    }

    private String strOf(String value) {
        return value == null ? "" : value;
    }

    private int bandIndex(int percent) {
        if (percent < 60) {
            return 0;
        }
        if (percent < 70) {
            return 1;
        }
        if (percent < 80) {
            return 2;
        }
        return percent < 90 ? 3 : 4;
    }

    private ExamReportView.Band band(String label, int count) {
        return new ExamReportView.Band(label, count);
    }
}
