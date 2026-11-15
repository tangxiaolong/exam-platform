package com.exam.platform.modules.exam;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.exam.platform.common.util.Json;
import com.exam.platform.common.util.Entities;
import com.exam.platform.common.security.LoginUser;
import com.exam.platform.common.security.UserContext;
import com.exam.platform.modules.exam.dto.ResultItem;
import com.exam.platform.modules.exam.entity.ExamAnswer;
import com.exam.platform.modules.exam.entity.ExamAttempt;
import com.exam.platform.modules.exam.entity.ExamResult;
import com.exam.platform.modules.exam.entity.ExamResultHistory;
import com.exam.platform.modules.exam.mapper.ExamAnswerMapper;
import com.exam.platform.modules.exam.mapper.ExamAttemptMapper;
import com.exam.platform.modules.exam.mapper.ExamResultHistoryMapper;
import com.exam.platform.modules.exam.mapper.ExamResultMapper;
import com.exam.platform.modules.message.MessageService;
import com.exam.platform.modules.message.MessageTemplates;
import com.exam.platform.modules.message.entity.MsgTask;
import com.exam.platform.modules.paper.PaperService;
import com.exam.platform.modules.paper.model.SnapshotContent;
import com.exam.platform.modules.paper.model.SnapshotQuestion;
import com.exam.platform.modules.question.entity.QbQuestion;
import com.exam.platform.modules.question.support.AnswerJudge;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 判分：以试卷快照中的标准答案为准，客观题自动判分。
 * 结果按 attempt 唯一，重复执行不会重复生成成绩。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GradingService {

    private final ExamResultMapper resultMapper;
    private final ExamAttemptMapper attemptMapper;
    private final ExamAnswerMapper answerMapper;
    private final PaperService paperService;
    private final ExamResultHistoryMapper historyMapper;
    private final MessageService messageService;

    @Transactional
    public ExamResult gradeIfAbsent(ExamAttempt attempt) {
        ExamResult existing = findByAttempt(attempt.getId());
        if (existing != null) {
            return existing;
        }
        SnapshotContent content = paperService.parseSnapshot(attempt.getPaperSnapshotId());
        Map<Long, List<String>> answers = loadAnswers(attempt.getId());

        List<ResultItem> items = new ArrayList<>();
        int correctCount = 0;
        int totalScore = 0;
        boolean hasEssay = false;
        for (SnapshotQuestion question : content.questions()) {
            List<String> given = answers.getOrDefault(question.questionId(), List.of());
            if (QbQuestion.TYPE_ESSAY.equals(question.type())) {
                // 主观题不入自动判分，等人工阅卷
                hasEssay = true;
                items.add(new ResultItem(question.questionId(), question.type(), question.stem(),
                        question.options(), question.answer(), given, false,
                        safeScore(question.score()), 0, question.analysis(), null, false));
                continue;
            }
            boolean correct = AnswerJudge.isCorrect(question.answer(), given);
            int gotScore = correct ? safeScore(question.score()) : 0;
            if (correct) {
                correctCount++;
                totalScore += gotScore;
            }
            items.add(new ResultItem(question.questionId(), question.type(), question.stem(),
                    question.options(), question.answer(), given, correct,
                    safeScore(question.score()), gotScore, question.analysis(), null, true));
        }

        String status = hasEssay ? ExamResult.STATUS_REVIEWING : ExamResult.STATUS_GRADED;
        ExamResult result = new ExamResult();
        result.setAttemptId(attempt.getId());
        result.setSessionId(attempt.getSessionId());
        result.setUserId(attempt.getUserId());
        result.setTotalScore(BigDecimal.valueOf(totalScore));
        result.setFullScore(content.totalScore());
        result.setCorrectCount(correctCount);
        result.setQuestionCount(items.size());
        result.setScoringVersion(attempt.getScoringVersion());
        result.setDetailJson(Json.write(items));
        result.setStatus(status);
        result.setRevision(1);
        resultMapper.insert(result);

        ExamAttempt update = new ExamAttempt();
        update.setId(attempt.getId());
        update.setStatus(hasEssay ? ExamAttempt.STATUS_REVIEWING : ExamAttempt.STATUS_GRADED);
        update.setTotalScore(BigDecimal.valueOf(totalScore));
        attemptMapper.updateById(update);
        return result;
    }

    public ExamResult findByAttempt(Long attemptId) {
        return resultMapper.selectOne(Wrappers.<ExamResult>lambdaQuery()
                .eq(ExamResult::getAttemptId, attemptId));
    }

    @Transactional
    public ExamResult markPublished(ExamResult result) {
        ExamResult update = new ExamResult();
        update.setId(result.getId());
        update.setStatus(ExamResult.STATUS_PUBLISHED);
        update.setPublishedAt(java.time.LocalDateTime.now());
        resultMapper.updateById(update);
        result.setStatus(ExamResult.STATUS_PUBLISHED);
        result.setPublishedAt(update.getPublishedAt());
        writeHistory(result, ExamResultHistory.OP_PUBLISH, null);
        notifyPublished(result);
        return result;
    }

    /** 成绩发布通知：以 attempt 为业务键幂等，重复调用不会重复推送。 */
    private void notifyPublished(ExamResult result) {
        try {
            messageService.publish(MsgTask.SCENE_RESULT, "result-" + result.getAttemptId(),
                    result.getUserId(), MessageTemplates.RESULT_PUBLISHED, Map.of(
                            "attemptId", result.getAttemptId(),
                            "totalScore", String.valueOf(result.getTotalScore()),
                            "fullScore", String.valueOf(result.getFullScore())));
        } catch (Exception e) {
            log.warn("成绩发布通知失败 attemptId={}", result.getAttemptId(), e);
        }
    }

    /** 记录成绩版本：发布与每次更正都会留痕。 */
    @Transactional
    public void writeHistory(ExamResult result, String operation, String remark) {
        ExamResultHistory history = new ExamResultHistory();
        history.setAttemptId(result.getAttemptId());
        history.setRevision(result.getRevision() == null ? 1 : result.getRevision());
        history.setOperation(operation);
        history.setTotalScore(result.getTotalScore());
        history.setDetailJson(result.getDetailJson());
        history.setRemark(remark);
        LoginUser current = UserContext.get();
        history.setOperatorId(current == null ? null : current.getUserId());
        Entities.forInsert(history);
        historyMapper.insert(history);
    }

    public List<ResultItem> parseItems(ExamResult result) {
        List<ResultItem> items = Json.readList(result.getDetailJson(), ResultItem.class);
        return items == null ? new ArrayList<>() : new ArrayList<>(items);
    }

    /** 重新计算总分：客观题自动得分 + 主观题人工得分。 */
    public int recalcTotal(List<ResultItem> items) {
        return items.stream().mapToInt(item -> item.gotScore() == null ? 0 : item.gotScore()).sum();
    }

    @Transactional
    public ExamResult updateDetail(ExamResult result, List<ResultItem> items, int revision) {
        ExamResult update = new ExamResult();
        update.setId(result.getId());
        update.setDetailJson(Json.write(items));
        update.setTotalScore(BigDecimal.valueOf(recalcTotal(items)));
        update.setRevision(revision);
        resultMapper.updateById(update);
        result.setDetailJson(update.getDetailJson());
        result.setTotalScore(update.getTotalScore());
        result.setRevision(revision);
        return result;
    }

    @Transactional
    public void updateStatus(ExamResult result, String status) {
        ExamResult update = new ExamResult();
        update.setId(result.getId());
        update.setStatus(status);
        resultMapper.updateById(update);
        result.setStatus(status);
    }

    private Map<Long, List<String>> loadAnswers(Long attemptId) {
        List<ExamAnswer> answers = answerMapper.selectList(Wrappers.<ExamAnswer>lambdaQuery()
                .eq(ExamAnswer::getAttemptId, attemptId));
        Map<Long, List<String>> map = new HashMap<>();
        for (ExamAnswer answer : answers) {
            List<String> values = Json.readList(answer.getAnswerJson(), String.class);
            map.put(answer.getQuestionId(), values == null ? List.of() : values);
        }
        return map;
    }

    private int safeScore(Integer score) {
        return score == null ? 0 : score;
    }
}
