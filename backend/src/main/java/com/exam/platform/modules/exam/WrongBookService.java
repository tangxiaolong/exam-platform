package com.exam.platform.modules.exam;

import com.exam.platform.common.security.UserContext;
import com.exam.platform.modules.exam.dto.ResultItem;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 错题本与收藏。
 *
 * <p>错题在交卷判分后自动写入，按（用户, 题目）聚合：
 * 同一题再错只累加次数并清空「已掌握」，而不是插一条新流水——
 * 错题本要回答的是「这道题我错了多少次」，不是「我哪天错了什么」。</p>
 *
 * <p>用 JdbcTemplate 而不是实体：错题本只有一个 upsert 与几个条件查询，
 * 列表还要 join 题库拿题干，手写 SQL 比多两张实体 + Mapper 更短也更清楚。
 * 租户条件显式写进 SQL（{@code tenant_id = ?}），不依赖插件。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WrongBookService {

    private static final int MAX_PAGE_SIZE = 100;

    private final JdbcTemplate jdbcTemplate;

    /**
     * 交卷后写入错题本，返回本次记录的错题数。
     *
     * <p>判分结果只在 {@code exam_result.detail_json} 里，这里直接消费
     * {@link GradingService#parseItems} 的产出，避免再解析一遍 JSON。</p>
     */
    @Transactional
    public int recordWrong(Long userId, Long attemptId, List<ResultItem> items) {
        if (items == null || items.isEmpty()) {
            return 0;
        }
        long tenantId = UserContext.tenantId();
        LocalDateTime now = LocalDateTime.now();
        int recorded = 0;
        for (ResultItem item : items) {
            if (item.correct() || item.questionId() == null) {
                continue;
            }
            int updated = jdbcTemplate.update(
                    "UPDATE exam_wrong_question SET wrong_count = wrong_count + 1, last_wrong_at = ?, "
                            + "last_attempt_id = ?, mastered = 0, updated_at = ? "
                            + "WHERE tenant_id = ? AND user_id = ? AND question_id = ? AND deleted = 0",
                    now, attemptId, now, tenantId, userId, item.questionId());
            if (updated == 0) {
                jdbcTemplate.update(
                        "INSERT INTO exam_wrong_question (tenant_id, user_id, question_id, wrong_count, "
                                + "last_attempt_id, last_wrong_at, mastered, created_at, updated_at, deleted) "
                                + "VALUES (?, ?, ?, 1, ?, ?, 0, ?, ?, 0)",
                        tenantId, userId, item.questionId(), attemptId, now, now, now);
            }
            recorded++;
        }
        log.info("错题本写入 userId={} attemptId={} count={}", userId, attemptId, recorded);
        return recorded;
    }

    public List<Map<String, Object>> pageWrong(Long userId, Boolean mastered, int pageNo, int pageSize) {
        StringBuilder sql = new StringBuilder(
                "SELECT w.question_id AS questionId, w.wrong_count AS wrongCount, "
                        + "w.last_wrong_at AS lastWrongAt, w.mastered AS mastered, "
                        + "q.stem AS stem, q.type AS type, q.score AS score "
                        + "FROM exam_wrong_question w "
                        + "LEFT JOIN qb_question q ON q.id = w.question_id "
                        + "AND q.tenant_id = w.tenant_id AND q.deleted = 0 "
                        + "WHERE w.tenant_id = ? AND w.user_id = ? AND w.deleted = 0");
        List<Object> args = new ArrayList<>();
        args.add(UserContext.tenantId());
        args.add(userId);
        if (mastered != null) {
            sql.append(" AND w.mastered = ?");
            args.add(mastered ? 1 : 0);
        }
        sql.append(" ORDER BY w.last_wrong_at DESC, w.id DESC LIMIT ? OFFSET ?");
        args.add(normalizePageSize(pageSize));
        args.add((Math.max(1, pageNo) - 1) * normalizePageSize(pageSize));
        return jdbcTemplate.queryForList(sql.toString(), args.toArray());
    }

    /**
     * 考试错题的题目 ID 列表，供统一错题视图合并使用。
     *
     * <p>{@code subjectId} 按题目所属科目过滤。用 JOIN 而非 LEFT JOIN：
     * 题目被删除的错题直接不出现——拿不到题干，返回一条空壳对考生没有意义。</p>
     */
    public List<Long> wrongQuestionIds(Long userId, Long subjectId) {
        StringBuilder sql = new StringBuilder(
                "SELECT w.question_id FROM exam_wrong_question w "
                        + "JOIN qb_question q ON q.id = w.question_id "
                        + "AND q.tenant_id = w.tenant_id AND q.deleted = 0 "
                        + "WHERE w.tenant_id = ? AND w.user_id = ? AND w.deleted = 0");
        List<Object> args = new ArrayList<>();
        args.add(UserContext.tenantId());
        args.add(userId);
        if (subjectId != null) {
            sql.append(" AND q.subject_id = ?");
            args.add(subjectId);
        }
        sql.append(" ORDER BY w.last_wrong_at DESC");
        return jdbcTemplate.queryForList(sql.toString(), Long.class, args.toArray());
    }

    public void markMastered(Long userId, Long questionId, boolean mastered) {
        int updated = jdbcTemplate.update(
                "UPDATE exam_wrong_question SET mastered = ?, updated_at = ? "
                        + "WHERE tenant_id = ? AND user_id = ? AND question_id = ? AND deleted = 0",
                mastered ? 1 : 0, LocalDateTime.now(), UserContext.tenantId(), userId, questionId);
        if (updated == 0) {
            // 不在错题本里的题不该被「标记掌握」：静默成功会让前端以为状态已保存
            throw new com.exam.platform.common.exception.BizException(
                    "WRONG_QUESTION_NOT_FOUND", "该题不在错题本中");
        }
    }

    public void removeWrong(Long userId, Long questionId) {
        jdbcTemplate.update(
                "UPDATE exam_wrong_question SET deleted = 1, updated_at = ? "
                        + "WHERE tenant_id = ? AND user_id = ? AND question_id = ? AND deleted = 0",
                LocalDateTime.now(), UserContext.tenantId(), userId, questionId);
    }

    /**
     * 手动加入错题本（成绩单上对任意题「一键加入」）。
     *
     * <p>答错的题在交卷判分后已自动写入；这里覆盖「答对了但想留着复习」的场景。
     * 幂等：已在错题本中不重复插入；软删过的记录复用，避免唯一索引把「删了再加」永久堵死。
     * {@code wrong_count} 记 0——手动加入不是答错，不该虚增「错过几次」。</p>
     */
    @Transactional
    public void addWrong(Long userId, Long questionId) {
        long tenantId = UserContext.tenantId();
        LocalDateTime now = LocalDateTime.now();
        int revived = jdbcTemplate.update(
                "UPDATE exam_wrong_question SET deleted = 0, mastered = 0, updated_at = ? "
                        + "WHERE tenant_id = ? AND user_id = ? AND question_id = ? AND deleted = 1",
                now, tenantId, userId, questionId);
        if (revived > 0) {
            return;
        }
        Integer exists = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM exam_wrong_question "
                        + "WHERE tenant_id = ? AND user_id = ? AND question_id = ? AND deleted = 0",
                Integer.class, tenantId, userId, questionId);
        if (exists != null && exists > 0) {
            return;
        }
        // last_wrong_at 是非空列，手动加入用当前时间占位，保证列表排序可用
        jdbcTemplate.update(
                "INSERT INTO exam_wrong_question (tenant_id, user_id, question_id, wrong_count, "
                        + "last_attempt_id, last_wrong_at, mastered, created_at, updated_at, deleted) "
                        + "VALUES (?, ?, ?, 0, NULL, ?, 0, ?, ?, 0)",
                tenantId, userId, questionId, now, now, now);
        log.info("手动加入错题本 userId={} questionId={}", userId, questionId);
    }

    // ---------------------------------------------------------------- 收藏

    public List<Map<String, Object>> pageFavorites(Long userId, int pageNo, int pageSize) {
        int size = normalizePageSize(pageSize);
        return jdbcTemplate.queryForList(
                "SELECT f.question_id AS questionId, f.remark AS remark, f.created_at AS createdAt, "
                        + "q.stem AS stem, q.type AS type, q.difficulty AS difficulty, "
                        + "q.answer_json AS answerJson, q.analysis AS analysis "
                        + "FROM user_favorite f "
                        + "LEFT JOIN qb_question q ON q.id = f.question_id "
                        + "AND q.tenant_id = f.tenant_id AND q.deleted = 0 "
                        + "WHERE f.tenant_id = ? AND f.user_id = ? AND f.deleted = 0 "
                        + "ORDER BY f.id DESC LIMIT ? OFFSET ?",
                UserContext.tenantId(), userId, size, (Math.max(1, pageNo) - 1) * size);
    }

    @Transactional
    public void addFavorite(Long userId, Long questionId, String remark) {
        long tenantId = UserContext.tenantId();
        Integer exists = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_favorite "
                        + "WHERE tenant_id = ? AND user_id = ? AND question_id = ? AND deleted = 0",
                Integer.class, tenantId, userId, questionId);
        if (exists != null && exists > 0) {
            return;
        }
        // 软删过的记录要复用，否则唯一索引会让「取消后再收藏」永久失败
        int revived = jdbcTemplate.update(
                "UPDATE user_favorite SET deleted = 0, remark = ?, updated_at = ? "
                        + "WHERE tenant_id = ? AND user_id = ? AND question_id = ? AND deleted = 1",
                remark, LocalDateTime.now(), tenantId, userId, questionId);
        if (revived > 0) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update(
                "INSERT INTO user_favorite (tenant_id, user_id, question_id, remark, "
                        + "created_at, updated_at, deleted) VALUES (?, ?, ?, ?, ?, ?, 0)",
                tenantId, userId, questionId, remark, now, now);
    }

    public void removeFavorite(Long userId, Long questionId) {
        jdbcTemplate.update(
                "UPDATE user_favorite SET deleted = 1, updated_at = ? "
                        + "WHERE tenant_id = ? AND user_id = ? AND question_id = ? AND deleted = 0",
                LocalDateTime.now(), UserContext.tenantId(), userId, questionId);
    }

    /** 已收藏题目 id 全集：练习页一次拉取、本地判断心形状态，不打 N 次接口。 */
    public List<Long> favoriteIds(Long userId) {
        return jdbcTemplate.queryForList(
                "SELECT question_id FROM user_favorite "
                        + "WHERE tenant_id = ? AND user_id = ? AND deleted = 0 ORDER BY id DESC",
                Long.class, UserContext.tenantId(), userId);
    }

    private int normalizePageSize(int pageSize) {
        if (pageSize <= 0) {
            return 20;
        }
        return Math.min(pageSize, MAX_PAGE_SIZE);
    }
}
