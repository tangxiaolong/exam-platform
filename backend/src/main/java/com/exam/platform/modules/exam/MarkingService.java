package com.exam.platform.modules.exam;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.exam.platform.common.exception.BizException;
import com.exam.platform.common.security.UserContext;
import com.exam.platform.common.util.Entities;
import com.exam.platform.modules.exam.dto.ExamResultView;
import com.exam.platform.modules.exam.dto.MarkingItemView;
import com.exam.platform.modules.exam.dto.ResultItem;
import com.exam.platform.modules.exam.entity.ExamAttempt;
import com.exam.platform.modules.exam.entity.ExamMarking;
import com.exam.platform.modules.exam.entity.ExamResult;
import com.exam.platform.modules.exam.entity.ExamResultHistory;
import com.exam.platform.modules.exam.mapper.ExamAttemptMapper;
import com.exam.platform.modules.exam.mapper.ExamMarkingMapper;
import com.exam.platform.modules.exam.mapper.ExamResultHistoryMapper;
import com.exam.platform.modules.question.entity.QbQuestion;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.IntStream;

/**
 * 主观题人工阅卷与成绩更正。
 *
 * <p>状态流转：交卷后含主观题 → attempt / result 均为 REVIEWING → 全部主观题评分完成
 * → GRADED → 到达成绩发布时间后 PUBLISHED。发布后再更正分数需填写原因，并写入
 * {@code exam_result_history} 保留历史版本。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MarkingService {

    private final ExamMarkingMapper markingMapper;
    private final ExamAttemptMapper attemptMapper;
    private final ExamResultHistoryMapper historyMapper;
    private final ExamAttemptService attemptService;
    private final GradingService gradingService;

    /** 待阅卷的主观题清单（含已评分项，便于逐题修改）。 */
    public List<MarkingItemView> list(Long attemptId) {
        ExamResult result = requireResult(attemptId);
        return gradingService.parseItems(result).stream()
                .filter(item -> QbQuestion.TYPE_ESSAY.equals(item.type()))
                .map(this::toView)
                .toList();
    }

    /** 给某道主观题打分；同一题重复打分覆盖上一次结果（阅卷过程中允许调整）。 */
    @Transactional
    public MarkingItemView mark(Long attemptId, Long questionId, Integer score, String remark) {
        ExamResult result = requireReviewing(attemptId);
        List<ResultItem> items = gradingService.parseItems(result);
        int index = indexOf(items, questionId);
        ResultItem item = items.get(index);
        if (!QbQuestion.TYPE_ESSAY.equals(item.type())) {
            throw new BizException("EXAM_MARKING_NOT_ESSAY", "只有主观题需要人工阅卷");
        }
        int fullScore = item.score() == null ? 0 : item.score();
        int safeScore = score == null ? 0 : score;
        if (safeScore < 0 || safeScore > fullScore) {
            throw new BizException("EXAM_MARKING_SCORE_INVALID", "得分需在 0 ~ " + fullScore + " 之间");
        }

        LocalDateTime now = LocalDateTime.now();
        ExamMarking marking = findMarking(attemptId, questionId);
        if (marking == null) {
            marking = new ExamMarking();
            marking.setAttemptId(attemptId);
            marking.setQuestionId(questionId);
            marking.setScore(safeScore);
            marking.setFullScore(fullScore);
            marking.setRemark(remark);
            marking.setMarkerId(UserContext.userId());
            marking.setMarkedAt(now);
            Entities.forInsert(marking);
            try {
                markingMapper.insert(marking);
            } catch (DuplicateKeyException e) {
                // 并发重复打分：以先写入的为准，随后覆盖为当前值
                marking = findMarking(attemptId, questionId);
            }
        }
        if (marking.getId() != null) {
            marking.setScore(safeScore);
            marking.setFullScore(fullScore);
            marking.setRemark(remark);
            marking.setMarkerId(UserContext.userId());
            marking.setMarkedAt(now);
            markingMapper.updateById(marking);
        }

        items.set(index, new ResultItem(item.questionId(), item.type(), item.stem(), item.options(),
                item.standardAnswer(), item.yourAnswer(), false, item.score(), safeScore,
                item.analysis(), remark, true));
        gradingService.updateDetail(result, items, result.getRevision() == null ? 1 : result.getRevision());
        return toView(items.get(index));
    }

    /** 阅卷完成：所有主观题已评分才允许结束，随后按成绩发布策略对考生可见。 */
    @Transactional
    public ExamResultView finish(Long attemptId) {
        ExamResult result = requireReviewing(attemptId);
        List<ResultItem> essays = gradingService.parseItems(result).stream()
                .filter(item -> QbQuestion.TYPE_ESSAY.equals(item.type()))
                .toList();
        if (essays.isEmpty()) {
            throw new BizException("EXAM_MARKING_NOTHING", "本场考试没有主观题，无需阅卷");
        }
        long pending = essays.stream().filter(item -> !item.marked()).count();
        if (pending > 0) {
            throw new BizException("EXAM_MARKING_INCOMPLETE", "还有 " + pending + " 道主观题未评分");
        }

        gradingService.updateStatus(result, ExamResult.STATUS_GRADED);
        ExamAttempt update = new ExamAttempt();
        update.setId(attemptId);
        update.setStatus(ExamAttempt.STATUS_GRADED);
        attemptMapper.updateById(update);
        ExamAttempt attempt = attemptService.getAttempt(attemptId);
        return attemptService.result(attemptId, attempt.getUserId());
    }

    /** 成绩更正：已发布成绩修改分数必须填写原因，并保留历史版本。 */
    @Transactional
    public ExamResultView correct(Long attemptId, Long questionId, Integer score, String reason) {
        if (reason == null || reason.isBlank()) {
            throw BizException.badRequest("成绩更正必须填写原因");
        }
        ExamResult result = requireResult(attemptId);
        if (!ExamResult.STATUS_GRADED.equals(result.getStatus())
                && !ExamResult.STATUS_PUBLISHED.equals(result.getStatus())) {
            throw new BizException("EXAM_RESULT_NOT_GRADED", "成绩尚未生成，不能更正");
        }
        List<ResultItem> items = gradingService.parseItems(result);
        int index = indexOf(items, questionId);
        ResultItem item = items.get(index);
        int fullScore = item.score() == null ? 0 : item.score();
        int safeScore = score == null ? 0 : score;
        if (safeScore < 0 || safeScore > fullScore) {
            throw new BizException("EXAM_MARKING_SCORE_INVALID", "得分需在 0 ~ " + fullScore + " 之间");
        }

        ExamMarking marking = findMarking(attemptId, questionId);
        if (marking != null) {
            marking.setScore(safeScore);
            marking.setRemark(reason);
            marking.setMarkerId(UserContext.userId());
            marking.setMarkedAt(LocalDateTime.now());
            markingMapper.updateById(marking);
        }

        items.set(index, new ResultItem(item.questionId(), item.type(), item.stem(), item.options(),
                item.standardAnswer(), item.yourAnswer(), item.correct(), item.score(), safeScore,
                item.analysis(), reason, true));
        int revision = (result.getRevision() == null ? 1 : result.getRevision()) + 1;
        gradingService.updateDetail(result, items, revision);
        gradingService.writeHistory(result, ExamResultHistory.OP_CORRECT, reason);
        log.info("成绩更正 attemptId={} questionId={} score={} revision={} reason={}",
                attemptId, questionId, safeScore, revision, reason);
        return attemptService.resultForAdmin(attemptId);
    }

    public List<ExamResultHistory> history(Long attemptId) {
        return historyMapper.selectList(Wrappers.<ExamResultHistory>lambdaQuery()
                .eq(ExamResultHistory::getAttemptId, attemptId)
                .orderByAsc(ExamResultHistory::getRevision)
                .orderByAsc(ExamResultHistory::getId));
    }

    private MarkingItemView toView(ResultItem item) {
        return new MarkingItemView(item.questionId(), item.type(), item.stem(), item.standardAnswer(),
                item.yourAnswer(), item.score(), item.gotScore(), item.remark(), item.marked());
    }

    private int indexOf(List<ResultItem> items, Long questionId) {
        return IntStream.range(0, items.size())
                .filter(i -> items.get(i).questionId().equals(questionId))
                .findFirst()
                .orElseThrow(() -> new BizException("EXAM_QUESTION_NOT_IN_PAPER", "题目不属于本场试卷"));
    }

    private ExamMarking findMarking(Long attemptId, Long questionId) {
        return markingMapper.selectOne(Wrappers.<ExamMarking>lambdaQuery()
                .eq(ExamMarking::getAttemptId, attemptId)
                .eq(ExamMarking::getQuestionId, questionId));
    }

    private ExamResult requireResult(Long attemptId) {
        attemptService.getAttempt(attemptId);
        ExamResult result = gradingService.findByAttempt(attemptId);
        if (result == null) {
            throw new BizException("EXAM_RESULT_MISSING", "成绩尚未生成");
        }
        return result;
    }

    private ExamResult requireReviewing(Long attemptId) {
        ExamResult result = requireResult(attemptId);
        if (!ExamResult.STATUS_REVIEWING.equals(result.getStatus())) {
            throw new BizException("EXAM_MARKING_NOT_ALLOWED", "当前成绩状态不接受阅卷：" + result.getStatus());
        }
        return result;
    }
}
