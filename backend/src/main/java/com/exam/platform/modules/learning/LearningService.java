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

    private static final int DEFAULT_LIMIT = 10;

    private final LearnPracticeMapper practiceMapper;
    private final LearnPracticeAnswerMapper answerMapper;
    private final QuestionService questionService;
    private final ContentService contentService;

    @Transactional
    public PracticeStartResponse create(Long userId, CreatePracticeRequest request) {
        contentService.getSubject(request.subjectId());
        String mode = request.mode() == null ? LearnPractice.MODE_SEQUENCE : request.mode();
        int limit = request.limit() == null ? DEFAULT_LIMIT : Math.max(1, Math.min(request.limit(), 50));

        List<QbQuestion> questions = switch (mode) {
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
        return new PracticeAnswerResult(correct, standard, question.getAnalysis(),
                practice.getAnsweredCount(), practice.getCorrectCount());
    }

    public List<WrongQuestionView> wrongQuestions(Long userId, Long subjectId) {
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
                    Json.readList(answer.getAnswerJson(), String.class), answer.getUpdatedAt()));
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
