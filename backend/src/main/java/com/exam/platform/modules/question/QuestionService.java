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
                                                       String status, PageQuery pageQuery) {
        Page<QbQuestion> page = questionMapper.selectPage(pageQuery.toPage(), Wrappers.<QbQuestion>lambdaQuery()
                .eq(subjectId != null, QbQuestion::getSubjectId, subjectId)
                .eq(type != null && !type.isBlank(), QbQuestion::getType, type)
                .eq(status != null && !status.isBlank(), QbQuestion::getStatus, status)
                .like(keyword != null && !keyword.isBlank(), QbQuestion::getStem, keyword)
                .orderByDesc(QbQuestion::getId));
        return PageResult.of(page.getRecords().stream().map(QuestionService::toDetail).toList(),
                page.getTotal(), page.getCurrent(), page.getSize());
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

    /** 随机取题（练习随机模式、试卷快速选题用）。 */
    public List<QbQuestion> randomQuestions(Long subjectId, int limit) {
        return questionMapper.selectList(Wrappers.<QbQuestion>lambdaQuery()
                .eq(QbQuestion::getStatus, "ENABLED")
                .eq(subjectId != null, QbQuestion::getSubjectId, subjectId)
                .last("ORDER BY RAND() LIMIT " + Math.max(1, Math.min(limit, 200))));
    }

    /** 规则组卷：按题型 / 难度随机抽题，排除已选题避免同一份试卷重复。 */
    public List<QbQuestion> randomByRule(Long subjectId, String type, String difficulty,
                                        Collection<Long> excludeIds, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        return questionMapper.selectList(Wrappers.<QbQuestion>lambdaQuery()
                .eq(QbQuestion::getStatus, "ENABLED")
                .eq(subjectId != null, QbQuestion::getSubjectId, subjectId)
                .eq(type != null && !type.isBlank(), QbQuestion::getType, type)
                .eq(difficulty != null && !difficulty.isBlank(), QbQuestion::getDifficulty, difficulty)
                .notIn(excludeIds != null && !excludeIds.isEmpty(), QbQuestion::getId, excludeIds)
                .last("ORDER BY RAND() LIMIT " + Math.min(limit, 500)));
    }

    /** 统计规则可选题量，用于组卷时的可读性错误提示。 */
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

    @Transactional
    public void changeStatus(Long id, String status) {
        getQuestion(id);
        QbQuestion update = new QbQuestion();
        update.setId(id);
        update.setStatus(status);
        questionMapper.updateById(update);
    }

    private void validate(QuestionSaveRequest request) {
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
                question.getDifficulty(), question.getTags(), question.getStatus(), question.getVersion());
    }
}
