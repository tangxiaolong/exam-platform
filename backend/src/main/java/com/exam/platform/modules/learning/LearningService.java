package com.exam.platform.modules.learning;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.exam.platform.common.exception.BizException;
import com.exam.platform.common.util.Entities;
import com.exam.platform.common.util.Json;
import com.exam.platform.modules.content.ContentService;
import com.exam.platform.modules.learning.dto.CreatePracticeRequest;
import com.exam.platform.modules.learning.dto.PracticeAnswerRequest;
import com.exam.platform.modules.learning.dto.PracticeAnswerResult;
import com.exam.platform.modules.learning.dto.PracticeStartResponse;
import com.exam.platform.modules.learning.dto.WrongQuestionView;
import com.exam.platform.modules.learning.entity.LearnPractice;
import com.exam.platform.modules.learning.entity.LearnPracticeAnswer;
import com.exam.platform.modules.learning.mapper.LearnPracticeAnswerMapper;
import com.exam.platform.modules.learning.mapper.LearnPracticeMapper;
import com.exam.platform.modules.question.QuestionService;
import com.exam.platform.modules.question.entity.QbQuestion;
import com.exam.platform.modules.question.model.QuestionView;
import com.exam.platform.modules.question.support.AnswerJudge;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 学习中心：练习、错题。 */
@Service
@RequiredArgsConstructor
public class LearningService {

    /** 考试错题的数据源：与练习错题不同表，由错题本服务负责写入与状态维护。 */
    private final com.exam.platform.modules.exam.WrongBookService wrongBookService;


    private static final int DEFAULT_LIMIT = 10;

    private final LearnPracticeMapper practiceMapper;
    private final LearnPracticeAnswerMapper answerMapper;
    private final QuestionService questionService;
    private final ContentService contentService;
    private final com.exam.platform.modules.system.NoticeService noticeService;

    @Transactional
    public PracticeStartResponse create(Long userId, CreatePracticeRequest request) {
        contentService.getSubject(request.subjectId());
        String mode = request.mode() == null ? LearnPractice.MODE_SEQUENCE : request.mode();
        int limit = request.limit() == null ? DEFAULT_LIMIT : Math.max(1, Math.min(request.limit(), 50));

        List<QbQuestion> questions = request.knowledgePointId() != null
                ? questionService.listSequentialByKnowledgePoint(request.subjectId(), request.knowledgePointId(), limit)
                : switch (mode) {
                    case LearnPractice.MODE_RANDOM -> questionService.randomQuestions(request.subjectId(), limit);
                    case LearnPractice.MODE_WRONG -> wrongQuestions(userId, request.subjectId()).stream()
                            .map(view -> questionService.getQuestion(view.question().id()))
                            .limit(limit)
                            .toList();
                    default -> questionService.listSequential(request.subjectId(), limit);
                };
        if (questions.isEmpty()) {
            throw new BizException("LEARN_NO_QUESTION", "该科目下暂无可用题目");
        }

        LearnPractice practice = new LearnPractice();
        practice.setUserId(userId);
        practice.setSubjectId(request.subjectId());
        practice.setMode(mode);
        practice.setQuestionIds(Json.write(questions.stream().map(QbQuestion::getId).toList()));
        practice.setTotalCount(questions.size());
        practice.setAnsweredCount(0);
        practice.setCorrectCount(0);
        practice.setStatus("IN_PROGRESS");
        practice.setStartedAt(LocalDateTime.now());
        Entities.forInsert(practice);
        practiceMapper.insert(practice);

        return toResponse(practice, questions);
    }

    public PracticeStartResponse get(Long practiceId, Long userId) {
        LearnPractice practice = requireOwned(practiceId, userId);
        List<Long> ids = Json.readList(practice.getQuestionIds(), Long.class);
        return toResponse(practice, questionService.listByIds(ids));
    }

    @Transactional
    public PracticeAnswerResult answer(Long practiceId, Long questionId, Long userId,
                                       PracticeAnswerRequest request) {
        LearnPractice practice = requireOwned(practiceId, userId);
        List<Long> ids = Json.readList(practice.getQuestionIds(), Long.class);
        if (!ids.contains(questionId)) {
            throw new BizException("LEARN_QUESTION_NOT_IN_SESSION", "题目不属于本次练习");
        }
        QbQuestion question = questionService.getQuestion(questionId);
        List<String> standard = Json.readList(question.getAnswerJson(), String.class);
        boolean correct = AnswerJudge.isCorrect(standard, request.answer());

        LearnPracticeAnswer existing = answerMapper.selectOne(Wrappers.<LearnPracticeAnswer>lambdaQuery()
                .eq(LearnPracticeAnswer::getPracticeId, practiceId)
                .eq(LearnPracticeAnswer::getQuestionId, questionId));
        if (existing == null) {
            LearnPracticeAnswer entity = new LearnPracticeAnswer();
            entity.setPracticeId(practiceId);
            entity.setUserId(userId);
            entity.setQuestionId(questionId);
            entity.setSubjectId(question.getSubjectId());
            entity.setAnswerJson(Json.write(request.answer()));
            entity.setCorrect(correct ? 1 : 0);
            Entities.forInsert(entity);
            try {
                answerMapper.insert(entity);
            } catch (DuplicateKeyException ignored) {
                // 并发重复提交，以先写入的结果为准
            }
        } else {
            existing.setAnswerJson(Json.write(request.answer()));
            existing.setCorrect(correct ? 1 : 0);
            answerMapper.updateById(existing);
        }

        refreshCounters(practice);
        // 解析与标准答案按业务配置展示：考试周可关闭（practice.analysis-visible=false），判分不受影响——
        // 关闭时只隐藏答案与解析，错题记录与统计照常落库（WBS 4.2「练习答案、解析按业务配置展示」）
        boolean analysisVisible = Boolean.parseBoolean(
                noticeService.getConfig("practice.analysis-visible", "true"));
        return new PracticeAnswerResult(correct,
                analysisVisible ? standard : List.of(),
                analysisVisible ? question.getAnalysis() : null,
                practice.getAnsweredCount(), practice.getCorrectCount());
    }

    /**
     * 最近一次练习会话：断点续练（PRD 4.3「恢复到最近一次有效记录」）。
     * 按科目过滤时取该科目最近一条；返回与创建练习同构的响应，前端可原样复用渲染。
     */
    public PracticeStartResponse recent(Long userId, Long subjectId) {
        LearnPractice practice = practiceMapper.selectOne(Wrappers.<LearnPractice>lambdaQuery()
                .eq(LearnPractice::getUserId, userId)
                .eq(subjectId != null, LearnPractice::getSubjectId, subjectId)
                .orderByDesc(LearnPractice::getId)
                .last("LIMIT 1"));
        return practice == null ? null : get(practice.getId(), userId);
    }

    /** 学习记录：本人练习会话分页（按开始顺序倒序），科目名逐行补全。 */
    public com.exam.platform.common.api.PageResult<java.util.Map<String, Object>> history(Long userId,
            com.exam.platform.common.api.PageQuery pageQuery) {
        var page = practiceMapper.selectPage(pageQuery.toPage(), Wrappers.<LearnPractice>lambdaQuery()
                .eq(LearnPractice::getUserId, userId)
                .orderByDesc(LearnPractice::getId));
        var list = new java.util.ArrayList<java.util.Map<String, Object>>();
        for (LearnPractice practice : page.getRecords()) {
            var item = new java.util.LinkedHashMap<String, Object>();
            item.put("practice_id", practice.getId());
            item.put("subject_id", practice.getSubjectId());
            try {
                item.put("subject_name", contentService.getSubject(practice.getSubjectId()).getName());
            } catch (Exception e) {
                item.put("subject_name", "");
            }
            item.put("mode", practice.getMode());
            item.put("status", practice.getStatus());
            item.put("total_count", practice.getTotalCount());
            item.put("answered_count", practice.getAnsweredCount());
            item.put("correct_count", practice.getCorrectCount());
            item.put("started_at", practice.getStartedAt());
            list.add(item);
        }
        return com.exam.platform.common.api.PageResult.of(list, page.getTotal(), page.getCurrent(), page.getSize());
    }

    public List<WrongQuestionView> wrongQuestions(Long userId, Long subjectId) {
        return wrongQuestions(userId, subjectId, null);
    }

    /**
     * 统一的错题列表：练习答错 + 考试答错。
     *
     * <p>{@code source} 为空表示两个来源都要。合并成一套是因为考生的心智只有
     * 「我错过的题」；分成两个入口的结果是两边都翻不到。</p>
     */
    public List<WrongQuestionView> wrongQuestions(Long userId, Long subjectId, String source) {
        String normalized = source == null || source.isBlank()
                ? "ALL" : source.toUpperCase(java.util.Locale.ROOT);
        // 同一次请求内按 subjectId 缓存科目名：科目数量很少，逐条查会变成 N+1
        Map<Long, String> subjectNames = new LinkedHashMap<>();
        List<WrongQuestionView> merged = new ArrayList<>();
        if (!WrongQuestionView.SOURCE_EXAM.equals(normalized)) {
            merged.addAll(practiceWrong(userId, subjectId, subjectNames));
        }
        if (!WrongQuestionView.SOURCE_PRACTICE.equals(normalized)) {
            for (Long questionId : wrongBookService.wrongQuestionIds(userId, subjectId)) {
                try {
                    QbQuestion question = questionService.getQuestion(questionId);
                    merged.add(new WrongQuestionView(QuestionService.toDetail(question), List.of(), null,
                            WrongQuestionView.SOURCE_EXAM,
                            subjectNameOf(question.getSubjectId(), subjectNames)));
                } catch (RuntimeException e) {
                    // 题目被下架/删除时跳过：错题本不该因为一道题消失就整页报错
                }
            }
        }
        return merged;
    }

    /** 科目名允许为空：科目被删时错题本身还应该能看，不该因为缺个名字就整条丢掉。 */
    private String subjectNameOf(Long subjectId, Map<Long, String> cache) {
        if (subjectId == null) {
            return null;
        }
        return cache.computeIfAbsent(subjectId, id -> {
            com.exam.platform.modules.content.entity.ExamSubject subject =
                    contentService.getSubject(id);
            return subject == null ? null : subject.getName();
        });
    }

    private List<WrongQuestionView> practiceWrong(Long userId, Long subjectId, Map<Long, String> subjectNames) {
        List<LearnPracticeAnswer> wrong = answerMapper.selectList(Wrappers.<LearnPracticeAnswer>lambdaQuery()
                .eq(LearnPracticeAnswer::getUserId, userId)
                .eq(LearnPracticeAnswer::getCorrect, 0)
                .eq(subjectId != null, LearnPracticeAnswer::getSubjectId, subjectId)
                .orderByDesc(LearnPracticeAnswer::getId));
        Map<Long, LearnPracticeAnswer> latest = new LinkedHashMap<>();
        for (LearnPracticeAnswer answer : wrong) {
            latest.putIfAbsent(answer.getQuestionId(), answer);
        }
        List<WrongQuestionView> views = new ArrayList<>();
        for (LearnPracticeAnswer answer : latest.values()) {
            QbQuestion question = questionService.getQuestion(answer.getQuestionId());
            views.add(new WrongQuestionView(QuestionService.toDetail(question),
                    Json.readList(answer.getAnswerJson(), String.class), answer.getUpdatedAt(),
                    WrongQuestionView.SOURCE_PRACTICE,
                    subjectNameOf(question.getSubjectId(), subjectNames)));
        }
        return views;
    }

    public List<LearnPractice> myPractices(Long userId) {
        return practiceMapper.selectList(Wrappers.<LearnPractice>lambdaQuery()
                .eq(LearnPractice::getUserId, userId)
                .orderByDesc(LearnPractice::getId));
    }

    private void refreshCounters(LearnPractice practice) {
        Long answered = answerMapper.selectCount(Wrappers.<LearnPracticeAnswer>lambdaQuery()
                .eq(LearnPracticeAnswer::getPracticeId, practice.getId()));
        Long correct = answerMapper.selectCount(Wrappers.<LearnPracticeAnswer>lambdaQuery()
                .eq(LearnPracticeAnswer::getPracticeId, practice.getId())
                .eq(LearnPracticeAnswer::getCorrect, 1));
        LearnPractice update = new LearnPractice();
        update.setId(practice.getId());
        update.setAnsweredCount(answered.intValue());
        update.setCorrectCount(correct.intValue());
        if (answered >= practice.getTotalCount()) {
            update.setStatus("FINISHED");
            update.setFinishedAt(LocalDateTime.now());
        }
        practiceMapper.updateById(update);
        practice.setAnsweredCount(answered.intValue());
        practice.setCorrectCount(correct.intValue());
    }

    private LearnPractice requireOwned(Long practiceId, Long userId) {
        LearnPractice practice = practiceMapper.selectById(practiceId);
        if (practice == null) {
            throw BizException.notFound("练习记录");
        }
        if (!practice.getUserId().equals(userId)) {
            throw BizException.forbidden("不能访问他人的练习记录");
        }
        return practice;
    }

    private PracticeStartResponse toResponse(LearnPractice practice, List<QbQuestion> questions) {
        List<QuestionView> views = questions.stream().map(QuestionService::toView).toList();
        return new PracticeStartResponse(practice.getId(), practice.getSubjectId(), practice.getMode(),
                practice.getStatus(), practice.getStartedAt(), practice.getTotalCount(),
                practice.getAnsweredCount(), practice.getCorrectCount(), views);
    }
}
