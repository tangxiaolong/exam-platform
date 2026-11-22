package com.exam.platform.modules.content;

import com.exam.platform.common.api.Result;
import com.exam.platform.common.security.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 知识点题库覆盖率：每个知识点（含无题的）一行，题目数只统计启用中的题目。 */
@RestController
@RequestMapping("/v1/admin/knowledge-points")
@RequiredArgsConstructor
public class KnowledgeCoverageController {

    private final JdbcTemplate jdbcTemplate;

    @GetMapping("/coverage")
    public Result<List<Map<String, Object>>> coverage(@RequestParam Long subjectId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT kp.id AS knowledge_point_id, kp.name AS knowledge_point_name, "
                        + "COUNT(q.id) AS question_count "
                        + "FROM exam_knowledge_point kp "
                        + "LEFT JOIN qb_question q ON q.knowledge_point_id = kp.id "
                        + "AND q.tenant_id = kp.tenant_id AND q.deleted = 0 AND q.status = 'ENABLED' "
                        + "WHERE kp.tenant_id = ? AND kp.subject_id = ? AND kp.deleted = 0 "
                        + "GROUP BY kp.id, kp.name ORDER BY kp.id",
                UserContext.tenantId(), subjectId);
        // H2 大写列名、MySQL 按别名——统一小写，前端消费不看数据库脸色
        List<Map<String, Object>> normalized = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            row.forEach((key, value) -> item.put(key.toLowerCase(Locale.ROOT), value));
            normalized.add(item);
        }
        return Result.ok(normalized);
    }
}
