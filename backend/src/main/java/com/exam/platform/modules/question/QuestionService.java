package com.exam.platform.modules.question;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.exam.platform.common.api.PageQuery;
import com.exam.platform.common.api.PageResult;
import com.exam.platform.common.exception.BizException;
import com.exam.platform.common.util.Entities;
import com.exam.platform.common.util.Json;
import com.exam.platform.modules.content.ContentService;
import com.exam.platform.modules.question.entity.QbQuestion;
import com.exam.platform.modules.question.mapper.QbQuestionMapper;
import com.exam.platform.modules.question.model.OptionItem;
import com.exam.platform.modules.question.model.QuestionDetailView;
import com.exam.platform.modules.question.model.QuestionSaveRequest;
import com.exam.platform.modules.question.model.QuestionView;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Set;

/** 题库服务。 */
@Service
@RequiredArgsConstructor
public class QuestionService {

    /** 读配置：用于判断「是否强制题目标注知识点」。 */
    private final com.exam.platform.modules.system.NoticeService noticeService;

    /** 校验知识点归属：知识点表没有对应的 Mapper，直接走 SQL 更省事。 */
    private final org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;


    private static final Set<String> TYPES =
            Set.of(QbQuestion.TYPE_SINGLE, QbQuestion.TYPE_MULTIPLE, QbQuestion.TYPE_JUDGE,
                    QbQuestion.TYPE_ESSAY);

    private final QbQuestionMapper questionMapper;
    private final ContentService contentService;

    public PageResult<QuestionView> pageForPractice(Long subjectId, String type, String keyword,
                                                    PageQuery pageQuery) {
        Page<QbQuestion> page = questionMapper.selectPage(pageQuery.toPage(), Wrappers.<QbQuestion>lambdaQuery()
                .eq(QbQuestion::getStatus, "ENABLED")
                .eq(subjectId != null, QbQuestion::getSubjectId, subjectId)
                .eq(type != null && !type.isBlank(), QbQuestion::getType, type)
                .like(keyword != null && !keyword.isBlank(), QbQuestion::getStem, keyword)
                .orderByAsc(QbQuestion::getId));
        return PageResult.of(page.getRecords().stream().map(QuestionService::toView).toList(),
                page.getTotal(), page.getCurrent(), page.getSize());
    }

    public PageResult<QuestionDetailView> pageForAdmin(Long subjectId, String type, String keyword,
                                                       String status, String source, PageQuery pageQuery) {
        Page<QbQuestion> page = questionMapper.selectPage(pageQuery.toPage(), Wrappers.<QbQuestion>lambdaQuery()
                .eq(subjectId != null, QbQuestion::getSubjectId, subjectId)
                .eq(type != null && !type.isBlank(), QbQuestion::getType, type)
                .eq(status != null && !status.isBlank(), QbQuestion::getStatus, status)
                .like(keyword != null && !keyword.isBlank(), QbQuestion::getStem, keyword)
                .like(source != null && !source.isBlank(), QbQuestion::getSource, source)
                .orderByDesc(QbQuestion::getId));
        return PageResult.of(page.getRecords().stream().map(QuestionService::toDetail).toList(),
                page.getTotal(), page.getCurrent(), page.getSize());
    }

    /** 按知识点顺序取启用题（章节练习用；无题时由调用方给统一提示）。 */
    public List<QbQuestion> listSequentialByKnowledgePoint(Long subjectId, Long knowledgePointId, int limit) {
        return questionMapper.selectList(Wrappers.<QbQuestion>lambdaQuery()
                .eq(QbQuestion::getSubjectId, subjectId)
                .eq(QbQuestion::getKnowledgePointId, knowledgePointId)
                .eq(QbQuestion::getStatus, "ENABLED")
                .orderByAsc(QbQuestion::getId)
                .last("LIMIT " + limit));
    }

    public QbQuestion getQuestion(Long id) {
        QbQuestion question = questionMapper.selectById(id);
        if (question == null) {
            throw BizException.notFound("题目");
        }
        return question;
    }

    public QuestionView view(Long id) {
        return toView(getQuestion(id));
    }

    public QuestionDetailView detail(Long id) {
        return toDetail(getQuestion(id));
    }

    public List<QbQuestion> listByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return questionMapper.selectBatchIds(ids);
    }

    /**
     * 从候选集里随机取 {@code limit} 条，返回顺序也是随机的。
     *
     * <p>不用 {@code ORDER BY RAND()}：那条语句要求数据库把整个候选集算一遍随机数再排序，
     * 题库到十万级时每次组卷都要全表排一次。这里改成「只查主键 → 内存打乱 → 按主键批量取」，
     * 第一段查询走索引且只返回 id 列，代价随题量线性增长而不是排序式的。</p>
     */
    private List<QbQuestion> pickRandom(
            com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<QbQuestion> wrapper,
            int limit) {
        List<Object> objs = questionMapper.selectObjs(wrapper.select(QbQuestion::getId));
        List<Long> ids = new java.util.ArrayList<>(objs.size());
        for (Object obj : objs) {
            ids.add(((Number) obj).longValue());
        }
        if (ids.isEmpty()) {
            return List.of();
        }
        java.util.Collections.shuffle(ids);
        if (ids.size() > limit) {
            ids = ids.subList(0, limit);
        }
        java.util.Map<Long, QbQuestion> byId = new java.util.HashMap<>();
        for (QbQuestion q : questionMapper.selectBatchIds(ids)) {
            byId.put(q.getId(), q);
        }
        // 按打乱后的 id 顺序回填，否则 selectBatchIds 会按主键升序返回，
        // 「随机」只剩内容随机，题目顺序仍然固定
        List<QbQuestion> picked = new java.util.ArrayList<>(ids.size());
        for (Long id : ids) {
            QbQuestion q = byId.get(id);
            if (q != null) {
                picked.add(q);
            }
        }
        return picked;
    }

    /** 随机取题（练习随机模式、试卷快速选题用）。 */
    public List<QbQuestion> randomQuestions(Long subjectId, int limit) {
        var query = Wrappers.<QbQuestion>lambdaQuery()
                .eq(QbQuestion::getStatus, "ENABLED")
                .eq(subjectId != null, QbQuestion::getSubjectId, subjectId);
        return pickRandom(query, Math.max(1, Math.min(limit, 200)));
    }

    /** 规则组卷：按题型 / 难度随机抽题，排除已选题避免同一份试卷重复。 */
    public List<QbQuestion> randomByRule(Long subjectId, String type, String difficulty,
                                        Collection<Long> excludeIds, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        var query = Wrappers.<QbQuestion>lambdaQuery()
                .eq(QbQuestion::getStatus, "ENABLED")
                .eq(subjectId != null, QbQuestion::getSubjectId, subjectId)
                .eq(type != null && !type.isBlank(), QbQuestion::getType, type)
                .eq(difficulty != null && !difficulty.isBlank(), QbQuestion::getDifficulty, difficulty)
                .notIn(excludeIds != null && !excludeIds.isEmpty(), QbQuestion::getId, excludeIds);
        return pickRandom(query, Math.min(limit, 500));
    }

    /** 统计规则可选题量，用于组卷时的可读性错误提示。 */
    /**
     * 按「题型 + 难度 + 知识点」随机抽题。
     *
     * <p>{@code knowledgePointId} 非空时只抽该知识点下已标注的题目，
     * 未标注知识点的存量题目会被排除。这是刻意的——把「未标注」当成
     * 「匹配任意知识点」会让知识点覆盖度失真，而覆盖度正是这个功能唯一的意义。</p>
     */
    public List<QbQuestion> randomByRule(Long subjectId, String type, String difficulty,
                                         Long knowledgePointId, Collection<Long> excludeIds, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        var query = Wrappers.<QbQuestion>lambdaQuery()
                .eq(QbQuestion::getStatus, "ENABLED")
                .eq(subjectId != null, QbQuestion::getSubjectId, subjectId)
                .eq(type != null && !type.isBlank(), QbQuestion::getType, type)
                .eq(difficulty != null && !difficulty.isBlank(), QbQuestion::getDifficulty, difficulty)
                .eq(knowledgePointId != null, QbQuestion::getKnowledgePointId, knowledgePointId)
                .notIn(excludeIds != null && !excludeIds.isEmpty(), QbQuestion::getId, excludeIds);
        return pickRandom(query, Math.min(limit, 500));
    }

    public long countAvailable(Long subjectId, String type, String difficulty, Long knowledgePointId,
                               Collection<Long> excludeIds) {
        return questionMapper.selectCount(Wrappers.<QbQuestion>lambdaQuery()
                .eq(QbQuestion::getStatus, "ENABLED")
                .eq(subjectId != null, QbQuestion::getSubjectId, subjectId)
                .eq(type != null && !type.isBlank(), QbQuestion::getType, type)
                .eq(difficulty != null && !difficulty.isBlank(), QbQuestion::getDifficulty, difficulty)
                .eq(knowledgePointId != null, QbQuestion::getKnowledgePointId, knowledgePointId)
                .notIn(excludeIds != null && !excludeIds.isEmpty(), QbQuestion::getId, excludeIds));
    }

    public long countAvailable(Long subjectId, String type, String difficulty, Collection<Long> excludeIds) {
        return questionMapper.selectCount(Wrappers.<QbQuestion>lambdaQuery()
                .eq(QbQuestion::getStatus, "ENABLED")
                .eq(subjectId != null, QbQuestion::getSubjectId, subjectId)
                .eq(type != null && !type.isBlank(), QbQuestion::getType, type)
                .eq(difficulty != null && !difficulty.isBlank(), QbQuestion::getDifficulty, difficulty)
                .notIn(excludeIds != null && !excludeIds.isEmpty(), QbQuestion::getId, excludeIds));
    }

    /** 顺序取题（按题目 ID 升序）。 */
    public List<QbQuestion> listSequential(Long subjectId, int limit) {
        return questionMapper.selectList(Wrappers.<QbQuestion>lambdaQuery()
                .eq(QbQuestion::getStatus, "ENABLED")
                .eq(subjectId != null, QbQuestion::getSubjectId, subjectId)
                .orderByAsc(QbQuestion::getId)
                .last("LIMIT " + Math.max(1, Math.min(limit, 200))));
    }

    @Transactional
    public QbQuestion save(QuestionSaveRequest request) {
        validate(request);
        contentService.getSubject(request.subjectId());
        QbQuestion question = request.toEntity();
        question.setOptionsJson(Json.write(request.options() == null ? List.of() : request.options()));
        question.setAnswerJson(Json.write(request.answer() == null ? List.of() : request.answer()));
        question.setVersion(1);
        Entities.forInsert(question);
        questionMapper.insert(question);
        return question;
    }

    /** 修改题目会递增版本；已被试卷快照引用的历史内容不受影响。 */
    @Transactional
    public QbQuestion update(Long id, QuestionSaveRequest request) {
        validate(request);
        QbQuestion exist = getQuestion(id);
        QbQuestion update = request.toEntity();
        update.setOptionsJson(Json.write(request.options() == null ? List.of() : request.options()));
        update.setAnswerJson(Json.write(request.answer() == null ? List.of() : request.answer()));
        update.setVersion(exist.getVersion() == null ? 2 : exist.getVersion() + 1);
        Entities.forUpdate(update, id);
        questionMapper.updateById(update);
        return getQuestion(id);
    }

    @Transactional
    public void delete(Long id) {
        getQuestion(id);
        questionMapper.deleteById(id);
    }

    /**
     * 批量移动科目：一次上限 200 条，目标科目必须存在。
     * 已发布场次的卷面快照在发布时冻结了科目归属，移动只影响之后的组卷与练习。
     */
    @Transactional
    public int batchChangeSubject(List<Long> ids, Long subjectId) {
        if (ids == null || ids.isEmpty()) {
            throw BizException.badRequest("题目ID不能为空");
        }
        if (ids.size() > 200) {
            throw BizException.badRequest("一次最多移动 200 道题目");
        }
        if (subjectId == null) {
            throw BizException.badRequest("目标科目不能为空");
        }
        contentService.getSubject(subjectId);
        int updated = 0;
        for (Long id : ids) {
            QbQuestion question = questionMapper.selectById(id);
            if (question == null || subjectId.equals(question.getSubjectId())) {
                continue;
            }
            question.setSubjectId(subjectId);
            questionMapper.updateById(question);
            updated++;
        }
        return updated;
    }

    /**
     * 批量启停：逐条走 {@link #changeStatus} 的同一条校验路径（知识点开关等规则不会被批量绕开），
     * 一次上限 200 条——批量操作是给「一批题」用的，不是给「整个题库」用的。
     */
    @Transactional
    public int batchChangeStatus(List<Long> ids, String status) {
        if (ids == null || ids.isEmpty()) {
            throw BizException.badRequest("请选择要操作的题目");
        }
        if (ids.size() > 200) {
            throw BizException.badRequest("一次最多批量操作 200 条");
        }
        ids.forEach(id -> changeStatus(id, status));
        return ids.size();
    }

    @Transactional
    public void changeStatus(Long id, String status) {
        QbQuestion exist = getQuestion(id);
        if ("ENABLED".equals(status) && exist.getKnowledgePointId() == null) {
            // 开关打开时禁止启用未标注知识点的题目：否则「按知识点组卷」永远抽不到它，
            // 而运营只会看到「题库可用 0 道」，想不到是标注问题
            boolean required = Boolean.parseBoolean(
                    noticeService.getConfig("question.knowledge-point.required", "false"));
            if (required) {
                throw BizException.badRequest("当前要求题目必须标注知识点，请先标注后再启用");
            }
        }
        QbQuestion update = new QbQuestion();
        update.setId(id);
        update.setStatus(status);
        questionMapper.updateById(update);
    }

    private void validate(QuestionSaveRequest request) {
        if (request.knowledgePointId() != null) {
            // 挂错科目的知识点不会报错，只会让这道题在任何知识点范围里都抽不到，
            // 而报错指向「该知识点可用 0 道」，几乎不可能联想到是科目挂错了
            java.util.List<Long> owners = jdbcTemplate.queryForList(
                    "SELECT subject_id FROM exam_knowledge_point "
                            + "WHERE tenant_id = ? AND id = ? AND deleted = 0",
                    Long.class, com.exam.platform.common.security.UserContext.tenantId(),
                    request.knowledgePointId());
            if (owners.isEmpty()) {
                throw BizException.badRequest(
                        "知识点不存在：" + request.knowledgePointId());
            }
            if (request.subjectId() != null && !request.subjectId().equals(owners.get(0))) {
                throw BizException.badRequest(
                        "知识点不属于该科目，绑定后组卷永远抽不到这道题");
            }
        }
        if (request.type() == null || !TYPES.contains(request.type())) {
            throw BizException.badRequest("题型不合法，支持 SINGLE/MULTIPLE/JUDGE/ESSAY");
        }
        if (request.stem() == null || request.stem().isBlank()) {
            throw BizException.badRequest("题干不能为空");
        }
        if (QbQuestion.TYPE_ESSAY.equals(request.type())) {
            // 主观题由人工阅卷，参考答案仅为阅卷参考，可留空
            return;
        }
        if (request.answer() == null || request.answer().isEmpty()) {
            throw BizException.badRequest("标准答案不能为空");
        }
        if (QbQuestion.TYPE_SINGLE.equals(request.type()) && request.answer().size() != 1) {
            throw BizException.badRequest("单选题答案只能有一个");
        }
        if (QbQuestion.TYPE_JUDGE.equals(request.type()) && request.answer().size() != 1) {
            throw BizException.badRequest("判断题答案只能有一个");
        }
    }

    public static QuestionView toView(QbQuestion question) {
        return new QuestionView(question.getId(), question.getSubjectId(), question.getType(),
                question.getStem(), Json.readList(question.getOptionsJson(), OptionItem.class),
                question.getDifficulty(), question.getTags());
    }

    public static QuestionDetailView toDetail(QbQuestion question) {
        return new QuestionDetailView(question.getId(), question.getSubjectId(), question.getType(),
                question.getStem(), Json.readList(question.getOptionsJson(), OptionItem.class),
                Json.readList(question.getAnswerJson(), String.class), question.getAnalysis(),
                question.getDifficulty(), question.getTags(), question.getStatus(), question.getVersion(),
                question.getSource());
    }
}
