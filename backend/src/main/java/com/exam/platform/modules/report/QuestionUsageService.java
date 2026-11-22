package com.exam.platform.modules.report;

import com.exam.platform.common.security.UserContext;
import com.exam.platform.common.util.Json;
import com.exam.platform.modules.exam.dto.ResultItem;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 题库使用分析（跨场次）。
 *
 * <p>口径：扫描最近 N 天内已判分/已发布答卷的 {@code detail_json}，按题目汇总
 * 「出现次数」（被多少份答卷抽中）与「答对次数」。正确率最低的题排最前——
 * 长期低正确率的题就是该优化或淘汰的题。</p>
 *
 * <p>扫描量有硬上限（最多 5000 份答卷）：detail_json 解析是 CPU 密集操作，
 * 不能让它在历史数据上无限增长。</p>
 */
@Service
@RequiredArgsConstructor
public class QuestionUsageService {

    private static final int MAX_SCAN = 5000;
    private static final int MAX_ROWS = 200;

    private final JdbcTemplate jdbcTemplate;

    public List<QuestionUsageView> analyze(int days, Long subjectId, int limit) {
        int windowDays = Math.max(1, Math.min(days, 365));
        int size = Math.max(1, Math.min(limit, MAX_ROWS));
        LocalDateTime since = LocalDateTime.now().minusDays(windowDays);

        List<String> details = jdbcTemplate.queryForList(
                "SELECT detail_json FROM exam_result "
                        + "WHERE tenant_id = ? AND deleted = 0 "
                        + "AND status IN ('GRADED', 'PUBLISHED') "
                        + "AND created_at >= ? AND detail_json IS NOT NULL "
                        + "ORDER BY id DESC LIMIT " + MAX_SCAN,
                String.class, UserContext.tenantId(), since);

        Map<Long, int[]> stats = new HashMap<>();
        for (String detail : details) {
            for (ResultItem item : Json.readList(detail, ResultItem.class)) {
                if (item.questionId() == null) {
                    continue;
                }
                int[] stat = stats.computeIfAbsent(item.questionId(), k -> new int[2]);
                stat[0]++;
                if (item.correct()) {
                    stat[1]++;
                }
            }
        }
        if (stats.isEmpty()) {
            return List.of();
        }

        // 一次 IN 查询补齐题干/题型/科目；id 全部来自本地解析出的 Long，拼入 SQL 无注入面
        String inClause = stats.keySet().stream()
                .map(String::valueOf)
                .collect(Collectors.joining(","));
        Map<Long, String[]> meta = new HashMap<>();
        Map<Long, Long> subjectOf = new HashMap<>();
        jdbcTemplate.query(
                "SELECT id, stem, type, subject_id FROM qb_question "
                        + "WHERE tenant_id = ? AND deleted = 0 AND id IN (" + inClause + ")",
                rs -> {
                    long id = rs.getLong("id");
                    meta.put(id, new String[]{rs.getString("stem"), rs.getString("type")});
                    subjectOf.put(id, rs.getLong("subject_id"));
                },
                UserContext.tenantId());

        List<QuestionUsageView> result = new ArrayList<>();
        for (Map.Entry<Long, int[]> entry : stats.entrySet()) {
            Long questionId = entry.getKey();
            if (subjectId != null && !subjectId.equals(subjectOf.get(questionId))) {
                continue;
            }
            int appeared = entry.getValue()[0];
            int correct = entry.getValue()[1];
            BigDecimal accuracy = BigDecimal.valueOf(correct).multiply(BigDecimal.valueOf(100))
                    .divide(BigDecimal.valueOf(appeared), 1, RoundingMode.HALF_UP);
            String[] m = meta.get(questionId);
            result.add(new QuestionUsageView(questionId,
                    m == null ? "" : m[0], m == null ? "" : m[1],
                    subjectOf.get(questionId), appeared, correct, accuracy));
        }
        result.sort(Comparator.comparing(QuestionUsageView::accuracy));
        return result.size() > size ? result.subList(0, size) : result;
    }
}
