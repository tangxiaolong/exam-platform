package com.exam.platform.modules.exam;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.exam.platform.common.api.PageQuery;
import com.exam.platform.common.api.PageResult;
import com.exam.platform.common.exception.BizException;
import com.exam.platform.common.util.Entities;
import com.exam.platform.common.util.Json;
import com.exam.platform.modules.exam.dto.AttemptStartResponse;
import com.exam.platform.modules.exam.dto.AttemptView;
import com.exam.platform.modules.exam.dto.ExamQuestionView;
import com.exam.platform.modules.exam.dto.ExamResultView;
import com.exam.platform.modules.exam.dto.ResultItem;
import com.exam.platform.modules.exam.dto.SaveAnswerRequest;
import com.exam.platform.modules.exam.dto.SaveAnswerResponse;
import com.exam.platform.modules.exam.dto.SavedAnswer;
import com.exam.platform.modules.exam.entity.ExamAnswer;
import com.exam.platform.modules.exam.entity.ExamAttempt;
import com.exam.platform.modules.exam.entity.ExamRegistration;
import com.exam.platform.modules.exam.entity.ExamResult;
import com.exam.platform.modules.exam.entity.ExamSession;
import com.exam.platform.modules.exam.mapper.ExamAnswerMapper;
import com.exam.platform.modules.exam.mapper.ExamAttemptMapper;
import com.exam.platform.modules.exam.mapper.ExamSessionMapper;
import com.exam.platform.modules.paper.PaperService;
import com.exam.platform.modules.paper.model.SnapshotContent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 考试作答引擎。
 *
 * <p>关键约束：</p>
 * <ul>
 *   <li>开始/截止时间以服务端为准，客户端计时只用于展示；</li>
 *   <li>答案保存以 (attempt_id, question_id) 为唯一维度，并带版本号防止旧请求覆盖新答案；</li>
 *   <li>交卷使用带状态条件的原子更新，重复请求返回既有成绩，不会重复判分。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExamAttemptService {

    private final ExamAttemptMapper attemptMapper;
    private final ExamAnswerMapper answerMapper;
    private final ExamSessionMapper sessionMapper;
    private final ExamSessionService sessionService;
    private final GradingService gradingService;
    private final WrongBookService wrongBookService;
    private final PaperService paperService;

    /** 开始考试：重复调用返回同一个有效 attempt。 */
    @Transactional
    public AttemptStartResponse start(Long sessionId, Long userId) {
        ExamSession session = sessionService.getSession(sessionId);
        ExamRegistration registration = sessionService.findRegistration(sessionId, userId);
        if (registration == null) {
            throw new BizException("EXAM_NOT_REGISTERED", "请先报名该场次");
        }

        ExamAttempt exist = findAttempt(sessionId, userId);
        if (exist != null) {
            return buildStartResponse(exist, session);
        }

        LocalDateTime now = LocalDateTime.now();
        assertEnterable(session, now);

        int duration = session.getDurationMinutes() == null ? 60 : session.getDurationMinutes();
        LocalDateTime deadline = now.plusMinutes(duration);
        if (session.getEndAt() != null && deadline.isAfter(session.getEndAt())) {
            deadline = session.getEndAt();
        }

        ExamAttempt attempt = new ExamAttempt();
        attempt.setSessionId(sessionId);
        attempt.setRegistrationId(registration.getId());
        attempt.setUserId(userId);
        attempt.setPaperSnapshotId(session.getSnapshotId());
        attempt.setAttemptNo(1);
        attempt.setStatus(ExamAttempt.STATUS_IN_PROGRESS);
        attempt.setStartedAt(now);
        attempt.setDeadlineAt(deadline);
        attempt.setScoringVersion(1);
        attempt.setAnswerVersion(0);
        Entities.forInsert(attempt);
        try {
            attemptMapper.insert(attempt);
        } catch (DuplicateKeyException e) {
            ExamAttempt concurrent = findAttempt(sessionId, userId);
            if (concurrent == null) {
                throw e;
            }
            return buildStartResponse(concurrent, session);
        }
        return buildStartResponse(attempt, session);
    }

    /** 恢复考试：以服务端已保存答案为准。 */
    @Transactional
    public AttemptStartResponse resume(Long attemptId, Long userId) {
        ExamAttempt attempt = requireOwned(attemptId, userId);
        ExamSession session = sessionService.getSession(attempt.getSessionId());
        if (ExamAttempt.STATUS_IN_PROGRESS.equals(attempt.getStatus()) && isDeadlinePassed(attempt)) {
            doSubmit(attempt, ExamAttempt.REASON_AUTO);
            attempt = attemptMapper.selectById(attemptId);
        }
        return buildStartResponse(attempt, session);
    }

    /** 保存答案（幂等 + 版本控制）。 */
    @Transactional
    public SaveAnswerResponse saveAnswer(Long attemptId, Long questionId, Long userId, SaveAnswerRequest request) {
        ExamAttempt attempt = requireOwned(attemptId, userId);
        if (!ExamAttempt.STATUS_IN_PROGRESS.equals(attempt.getStatus())) {
            throw new BizException("EXAM_NOT_IN_PROGRESS", "考试已结束，不能继续作答");
        }
        LocalDateTime now = LocalDateTime.now();
        if (isDeadlinePassed(attempt)) {
            // 不在此处交卷：后续抛出的业务异常会回滚同一事务，导致“已交卷”丢失。
            // 交卷由超时定时任务兜底，考生查询成绩时也会触发一次收卷。
            throw new BizException("EXAM_DEADLINE_PASSED", "已到考试截止时间，系统将自动交卷");
        }

        assertQuestionInPaper(attempt, questionId);
        String answerJson = Json.write(request.answer() == null ? List.of() : request.answer());

        ExamAnswer existing = findAnswer(attemptId, questionId);
        if (existing == null) {
            ExamAnswer answer = new ExamAnswer();
            answer.setAttemptId(attemptId);
            answer.setQuestionId(questionId);
            answer.setAnswerJson(answerJson);
            answer.setAnswerVersion(1);
            answer.setIsMarked(Boolean.TRUE.equals(request.marked()) ? 1 : 0);
            answer.setSavedAt(now);
            Entities.forInsert(answer);
            try {
                answerMapper.insert(answer);
            } catch (DuplicateKeyException e) {
                existing = findAnswer(attemptId, questionId);
                if (existing == null) {
                    throw e;
                }
                return updateExistingAnswer(attempt, existing, answerJson, request, now);
            }
            touchAttemptVersion(attempt, 1);
            return new SaveAnswerResponse(true, 1, now);
        }
        return updateExistingAnswer(attempt, existing, answerJson, request, now);
    }

    private SaveAnswerResponse updateExistingAnswer(ExamAttempt attempt, ExamAnswer existing, String answerJson,
                                                    SaveAnswerRequest request, LocalDateTime now) {
        if (request.clientVersion() != null && request.clientVersion() < existing.getAnswerVersion()) {
            // 旧请求迟到的场景：保留服务端更新的答案
            return new SaveAnswerResponse(false, existing.getAnswerVersion(), now);
        }
        ExamAnswer update = new ExamAnswer();
        update.setId(existing.getId());
        update.setAnswerJson(answerJson);
        update.setAnswerVersion(existing.getAnswerVersion() + 1);
        update.setIsMarked(Boolean.TRUE.equals(request.marked()) ? 1 : 0);
        update.setSavedAt(now);
        update.setUpdatedAt(now);
        int rows = answerMapper.updateById(update);
        if (rows == 0) {
            ExamAnswer latest = answerMapper.selectById(existing.getId());
            int latestVersion = latest == null ? existing.getAnswerVersion() : latest.getAnswerVersion();
            return new SaveAnswerResponse(false, latestVersion, now);
        }
        touchAttemptVersion(attempt, update.getAnswerVersion());
        return new SaveAnswerResponse(true, update.getAnswerVersion(), now);
    }

    /** 主动交卷。 */
    @Transactional
    public ExamResultView submit(Long attemptId, Long userId, String reason) {
        ExamAttempt attempt = requireOwned(attemptId, userId);
        // 客户端只能在“主动交卷”语义内提交，自动交卷由超时任务触发
        return doSubmit(attempt, ExamAttempt.REASON_USER);
    }
    /** 管理员强制收卷 / 定时任务自动交卷。 */
    @Transactional
    public ExamResultView doSubmit(ExamAttempt attempt, String reason) {
        // 只有本次真的完成了「进行中 → 已交卷」的状态迁移，才算一次新的交卷；
        // 重复交卷会返回既有成绩，此时不能重复往错题本里累加次数
        boolean justSubmitted = false;
        ExamSession session = sessionService.getSession(attempt.getSessionId());
        if (ExamAttempt.STATUS_IN_PROGRESS.equals(attempt.getStatus())) {
            LocalDateTime now = LocalDateTime.now();
            int rows = attemptMapper.update(null, Wrappers.<ExamAttempt>lambdaUpdate()
                    .eq(ExamAttempt::getId, attempt.getId())
                    .eq(ExamAttempt::getStatus, ExamAttempt.STATUS_IN_PROGRESS)
                    .set(ExamAttempt::getStatus, ExamAttempt.STATUS_SUBMITTED)
                    .set(ExamAttempt::getSubmittedAt, now)
                    .set(ExamAttempt::getSubmitReason, reason)
                    .set(ExamAttempt::getUpdatedAt, now));
            if (rows > 0) {
                justSubmitted = true;
                attempt.setStatus(ExamAttempt.STATUS_SUBMITTED);
                attempt.setSubmittedAt(now);
                attempt.setSubmitReason(reason);
            } else {
                attempt = attemptMapper.selectById(attempt.getId());
            }
        } else if (!ExamAttempt.STATUS_SUBMITTED.equals(attempt.getStatus())
                && !ExamAttempt.STATUS_GRADED.equals(attempt.getStatus())) {
            throw new BizException("EXAM_STATUS_INVALID", "当前考试状态不支持交卷：" + attempt.getStatus());
        }
        ExamResult result = gradingService.gradeIfAbsent(attempt);
        if (justSubmitted) {
            // 错题本跟着成绩一起就绪：考生交完卷就能复习，不必等成绩发布再手动挑题
            wrongBookService.recordWrong(attempt.getUserId(), attempt.getId(),
                    gradingService.parseItems(result));
        }
        return buildResultView(attempt, result, session);
    }

    /** 查询成绩：成绩未发布时只返回状态，不返回题目明细。 */
    @Transactional
    public ExamResultView result(Long attemptId, Long userId) {
        ExamAttempt attempt = requireOwned(attemptId, userId);
        ExamSession session = sessionService.getSession(attempt.getSessionId());
        if (ExamAttempt.STATUS_IN_PROGRESS.equals(attempt.getStatus())) {
            if (isDeadlinePassed(attempt)) {
                return doSubmit(attempt, ExamAttempt.REASON_AUTO);
            }
            throw new BizException("EXAM_NOT_SUBMITTED", "考试尚未交卷");
        }
        ExamResult result = gradingService.findByAttempt(attemptId);
        if (result == null) {
            result = gradingService.gradeIfAbsent(attempt);
        }
        return buildResultView(attempt, result, session);
    }

    public List<AttemptView> myAttempts(Long userId) {
        List<ExamAttempt> attempts = attemptMapper.selectList(Wrappers.<ExamAttempt>lambdaQuery()
                .eq(ExamAttempt::getUserId, userId)
                .orderByDesc(ExamAttempt::getId));
        List<AttemptView> views = new ArrayList<>();
        for (ExamAttempt attempt : attempts) {
            ExamSession session = sessionMapper.selectById(attempt.getSessionId());
            views.add(new AttemptView(attempt.getId(), attempt.getSessionId(),
                    session == null ? null : session.getName(), attempt.getStatus(),
                    attempt.getStartedAt(), attempt.getDeadlineAt(), attempt.getSubmittedAt(),
                    attempt.getTotalScore()));
        }
        return views;
    }

    public List<AttemptView> listBySession(Long sessionId) {
        ExamSession session = sessionService.getSession(sessionId);
        List<ExamAttempt> attempts = attemptMapper.selectList(Wrappers.<ExamAttempt>lambdaQuery()
                .eq(ExamAttempt::getSessionId, sessionId)
                .orderByDesc(ExamAttempt::getId));
        List<AttemptView> views = new ArrayList<>();
        for (ExamAttempt attempt : attempts) {
            views.add(new AttemptView(attempt.getId(), sessionId, session.getName(), attempt.getStatus(),
                    attempt.getStartedAt(), attempt.getDeadlineAt(), attempt.getSubmittedAt(),
                    attempt.getTotalScore()));
        }
        return views;
    }

    /** 到期未交卷的自动交卷，由定时任务驱动，单次批处理避免长时间锁表。 */
    @Transactional
    public int autoSubmitExpired(int limit) {
        List<ExamAttempt> expired = attemptMapper.selectList(Wrappers.<ExamAttempt>lambdaQuery()
                .eq(ExamAttempt::getStatus, ExamAttempt.STATUS_IN_PROGRESS)
                .isNotNull(ExamAttempt::getDeadlineAt)
                .lt(ExamAttempt::getDeadlineAt, LocalDateTime.now())
                .last("LIMIT " + Math.max(1, limit)));
        int count = 0;
        for (ExamAttempt attempt : expired) {
            try {
                doSubmit(attempt, ExamAttempt.REASON_AUTO);
                count++;
            } catch (Exception e) {
                log.warn("自动交卷失败 attemptId={}", attempt.getId(), e);
            }
        }
        return count;
    }

    public ExamAttempt getAttempt(Long attemptId) {
        ExamAttempt attempt = attemptMapper.selectById(attemptId);
        if (attempt == null) {
            throw BizException.notFound("考试记录");
        }
        return attempt;
    }

    private ExamAttempt requireOwned(Long attemptId, Long userId) {
        ExamAttempt attempt = getAttempt(attemptId);
        if (!attempt.getUserId().equals(userId)) {
            throw BizException.forbidden("不能操作他人的考试记录");
        }
        return attempt;
    }

    private ExamAttempt findAttempt(Long sessionId, Long userId) {
        return attemptMapper.selectOne(Wrappers.<ExamAttempt>lambdaQuery()
                .eq(ExamAttempt::getSessionId, sessionId)
                .eq(ExamAttempt::getUserId, userId)
                .orderByDesc(ExamAttempt::getId)
                .last("LIMIT 1"));
    }

    private ExamAnswer findAnswer(Long attemptId, Long questionId) {
        return answerMapper.selectOne(Wrappers.<ExamAnswer>lambdaQuery()
                .eq(ExamAnswer::getAttemptId, attemptId)
                .eq(ExamAnswer::getQuestionId, questionId));
    }

    private void touchAttemptVersion(ExamAttempt attempt, int version) {
        ExamAttempt update = new ExamAttempt();
        update.setId(attempt.getId());
        update.setAnswerVersion(version);
        attemptMapper.updateById(update);
        attempt.setAnswerVersion(version);
    }

    private boolean isDeadlinePassed(ExamAttempt attempt) {
        return attempt.getDeadlineAt() != null && LocalDateTime.now().isAfter(attempt.getDeadlineAt());
    }

    private void assertEnterable(ExamSession session, LocalDateTime now) {
        if (!ExamSession.STATUS_PUBLISHED.equals(session.getStatus())) {
            throw new BizException("EXAM_SESSION_NOT_OPEN", "场次尚未发布");
        }
        if (session.getSnapshotId() == null) {
            throw new BizException("EXAM_SNAPSHOT_MISSING", "场次缺少试卷快照");
        }
        if (session.getEndAt() != null && now.isAfter(session.getEndAt())) {
            throw new BizException("EXAM_SESSION_ENDED", "场次已结束");
        }
        int allowBefore = session.getAllowEnterBeforeMinutes() == null ? 0 : session.getAllowEnterBeforeMinutes();
        LocalDateTime earliest = session.getStartAt().minusMinutes(allowBefore);
        if (now.isBefore(earliest)) {
            throw new BizException("EXAM_NOT_STARTED", "考试尚未开始，可在开考前 "
                    + allowBefore + " 分钟进入");
        }
        int lateLimit = session.getLateLimitMinutes() == null ? 0 : session.getLateLimitMinutes();
        if (lateLimit > 0 && now.isAfter(session.getStartAt().plusMinutes(lateLimit))) {
            throw new BizException("EXAM_LATE_LIMIT", "已超过最晚入场时间");
        }
    }

    private void assertQuestionInPaper(ExamAttempt attempt, Long questionId) {
        SnapshotContent content = paperService.parseSnapshot(attempt.getPaperSnapshotId());
        boolean inPaper = content.questions().stream()
                .anyMatch(q -> q.questionId().equals(questionId));
        if (!inPaper) {
            throw new BizException("EXAM_QUESTION_NOT_IN_PAPER", "题目不属于本场试卷");
        }
    }

    private AttemptStartResponse buildStartResponse(ExamAttempt attempt, ExamSession session) {
        SnapshotContent content = paperService.parseSnapshot(attempt.getPaperSnapshotId());
        List<ExamQuestionView> questions = content.questions().stream()
                .map(q -> new ExamQuestionView(q.questionId(), q.type(), q.stem(), q.options(),
                        q.score(), q.difficulty()))
                .toList();
        List<SavedAnswer> savedAnswers = answerMapper.selectList(Wrappers.<ExamAnswer>lambdaQuery()
                        .eq(ExamAnswer::getAttemptId, attempt.getId()))
                .stream()
                .map(a -> new SavedAnswer(a.getQuestionId(),
                        Json.readList(a.getAnswerJson(), String.class), a.getAnswerVersion()))
                .toList();
        return new AttemptStartResponse(attempt.getId(), session.getId(), session.getName(),
                attempt.getStatus(), LocalDateTime.now(), attempt.getStartedAt(), attempt.getDeadlineAt(),
                session.getDurationMinutes(), session.getNoticeVersion(), session.getNoticeContent(),
                attempt.getAnswerVersion(), questions, savedAnswers);
    }

    private ExamResultView buildResultView(ExamAttempt attempt, ExamResult result, ExamSession session) {
        return buildResultView(attempt, result, session, false);
    }

    /** 管理端查看成绩：始终返回逐题明细，便于阅卷与更正。 */
    public ExamResultView resultForAdmin(Long attemptId) {
        ExamAttempt attempt = getAttempt(attemptId);
        ExamSession session = sessionService.getSession(attempt.getSessionId());
        ExamResult result = gradingService.findByAttempt(attemptId);
        if (result == null) {
            result = gradingService.gradeIfAbsent(attempt);
        }
        return buildResultView(attempt, result, session, true);
    }

    /** 阅卷队列：按 attempt 状态分页（如 REVIEWING 待阅卷）。 */
    public PageResult<AttemptView> pageByStatus(String status, PageQuery pageQuery) {
        return pageByStatus(status, null, pageQuery);
    }

    /** 阅卷队列按状态 + 可选场次过滤：多场次并行阅卷时可聚焦单个场次。 */
    public PageResult<AttemptView> pageByStatus(String status, Long sessionId, PageQuery pageQuery) {
        Page<ExamAttempt> page = attemptMapper.selectPage(pageQuery.toPage(), Wrappers.<ExamAttempt>lambdaQuery()
                .eq(status != null && !status.isBlank(), ExamAttempt::getStatus, status)
                .eq(sessionId != null, ExamAttempt::getSessionId, sessionId)
                .orderByDesc(ExamAttempt::getId));
        List<AttemptView> views = new ArrayList<>();
        for (ExamAttempt attempt : page.getRecords()) {
            ExamSession session = sessionMapper.selectById(attempt.getSessionId());
            views.add(new AttemptView(attempt.getId(), attempt.getSessionId(),
                    session == null ? null : session.getName(), attempt.getStatus(),
                    attempt.getStartedAt(), attempt.getDeadlineAt(), attempt.getSubmittedAt(),
                    attempt.getTotalScore()));
        }
        return PageResult.of(views, page.getTotal(), page.getCurrent(), page.getSize());
    }

    private ExamResultView buildResultView(ExamAttempt attempt, ExamResult result, ExamSession session,
                                           boolean forceItems) {
        LocalDateTime publishAt = session.getResultPublishAt();
        boolean publishable = publishAt == null || !LocalDateTime.now().isBefore(publishAt);
        if (ExamResult.STATUS_GRADED.equals(result.getStatus()) && publishable) {
            result = gradingService.markPublished(result);
        }
        List<ResultItem> items = forceItems || ExamResult.STATUS_PUBLISHED.equals(result.getStatus())
                ? gradingService.parseItems(result) : List.of();
        return new ExamResultView(result.getAttemptId(), result.getSessionId(), session.getName(),
                result.getTotalScore(), result.getFullScore(), result.getCorrectCount(),
                result.getQuestionCount(), result.getStatus(), result.getScoringVersion(),
                attempt.getSubmittedAt(), result.getPublishedAt(), items);
    }
}
