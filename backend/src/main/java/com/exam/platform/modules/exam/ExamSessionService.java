package com.exam.platform.modules.exam;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.exam.platform.common.api.PageQuery;
import com.exam.platform.common.api.PageResult;
import com.exam.platform.common.exception.BizException;
import com.exam.platform.common.util.BizNo;
import com.exam.platform.common.util.Entities;
import com.exam.platform.modules.content.ContentService;
import com.exam.platform.modules.exam.dto.RegisterRequest;
import com.exam.platform.modules.exam.dto.RegistrationView;
import com.exam.platform.modules.exam.dto.SessionSaveRequest;
import com.exam.platform.modules.exam.entity.ExamRegistration;
import com.exam.platform.modules.exam.entity.ExamSession;
import com.exam.platform.modules.exam.mapper.ExamRegistrationMapper;
import com.exam.platform.modules.exam.mapper.ExamSessionMapper;
import com.exam.platform.modules.paper.PaperService;
import com.exam.platform.modules.paper.entity.ExamPaperSnapshot;
import com.exam.platform.modules.message.MessageService;
import com.exam.platform.modules.message.MessageTemplates;
import com.exam.platform.modules.message.entity.MsgTask;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** 考试场次与报名。 */
@Service
@RequiredArgsConstructor
public class ExamSessionService {

    private final ExamSessionMapper sessionMapper;
    private final ExamRegistrationMapper registrationMapper;
    private final ContentService contentService;
    private final PaperService paperService;
    private final MessageService messageService;

    public PageResult<ExamSession> pageForAdmin(String keyword, String status, PageQuery pageQuery) {
        Page<ExamSession> page = sessionMapper.selectPage(pageQuery.toPage(), Wrappers.<ExamSession>lambdaQuery()
                .like(keyword != null && !keyword.isBlank(), ExamSession::getName, keyword)
                .eq(status != null && !status.isBlank(), ExamSession::getStatus, status)
                .orderByDesc(ExamSession::getStartAt));
        return PageResult.of(page.getRecords(), page.getTotal(), page.getCurrent(), page.getSize());
    }

    /** 小程序可报名/可参加的场次：已发布且尚未结束。 */
    public List<ExamSession> listParticipable(Long projectId) {
        return sessionMapper.selectList(Wrappers.<ExamSession>lambdaQuery()
                .eq(ExamSession::getStatus, ExamSession.STATUS_PUBLISHED)
                .gt(ExamSession::getEndAt, LocalDateTime.now())
                .eq(projectId != null, ExamSession::getProjectId, projectId)
                .orderByAsc(ExamSession::getStartAt));
    }

    public ExamSession getSession(Long id) {
        ExamSession session = sessionMapper.selectById(id);
        if (session == null) {
            throw BizException.notFound("考试场次");
        }
        return session;
    }

    @Transactional
    public ExamSession save(SessionSaveRequest request) {
        validate(request);
        ExamSession session = new ExamSession();
        apply(session, request);
        session.setStatus(ExamSession.STATUS_DRAFT);
        Entities.forInsert(session);
        sessionMapper.insert(session);
        return session;
    }

    @Transactional
    public ExamSession update(Long id, SessionSaveRequest request) {
        ExamSession exist = getSession(id);
        if (ExamSession.STATUS_PUBLISHED.equals(exist.getStatus())) {
            throw new BizException("EXAM_SESSION_LOCKED", "已发布场次不允许修改考试规则，请新建场次");
        }
        validate(request);
        ExamSession update = new ExamSession();
        apply(update, request);
        Entities.forUpdate(update, id);
        sessionMapper.updateById(update);
        return getSession(id);
    }

    @Transactional
    public void delete(Long id) {
        ExamSession session = getSession(id);
        if (ExamSession.STATUS_PUBLISHED.equals(session.getStatus())) {
            throw new BizException("EXAM_SESSION_LOCKED", "已发布场次不能删除，请先关闭");
        }
        sessionMapper.deleteById(id);
    }

    /** 发布场次：生成不可变试卷快照，此后考试只读快照。 */
    @Transactional
    public ExamSession publish(Long id) {
        ExamSession session = getSession(id);
        if (ExamSession.STATUS_PUBLISHED.equals(session.getStatus())) {
            throw new BizException("EXAM_SESSION_PUBLISHED", "场次已发布");
        }
        ExamPaperSnapshot snapshot = paperService.createSnapshot(session.getPaperId());
        ExamSession update = new ExamSession();
        update.setId(id);
        update.setSnapshotId(snapshot.getId());
        update.setStatus(ExamSession.STATUS_PUBLISHED);
        sessionMapper.updateById(update);
        return getSession(id);
    }

    @Transactional
    public void close(Long id) {
        getSession(id);
        ExamSession update = new ExamSession();
        update.setId(id);
        update.setStatus(ExamSession.STATUS_CLOSED);
        sessionMapper.updateById(update);
    }

    /** 报名：校验场次状态、时间窗口、名额与重复报名；重复请求返回既有记录。 */
    @Transactional
    public RegistrationView register(Long sessionId, Long userId, RegisterRequest request) {
        ExamSession session = getSession(sessionId);
        if (!ExamSession.STATUS_PUBLISHED.equals(session.getStatus())) {
            throw new BizException("EXAM_SESSION_NOT_OPEN", "场次未开放报名");
        }
        LocalDateTime now = LocalDateTime.now();
        if (session.getRegisterStartAt() != null && now.isBefore(session.getRegisterStartAt())) {
            throw new BizException("EXAM_REGISTER_NOT_STARTED", "报名尚未开始");
        }
        if (session.getRegisterEndAt() != null && now.isAfter(session.getRegisterEndAt())) {
            throw new BizException("EXAM_REGISTER_ENDED", "报名已结束");
        }
        if (session.getEndAt() != null && now.isAfter(session.getEndAt())) {
            throw new BizException("EXAM_SESSION_ENDED", "场次已结束");
        }

        ExamRegistration exist = findRegistration(sessionId, userId);
        if (exist != null) {
            return toView(exist, session);
        }

        if (session.getQuota() != null && session.getQuota() > 0) {
            Long count = registrationMapper.selectCount(Wrappers.<ExamRegistration>lambdaQuery()
                    .eq(ExamRegistration::getSessionId, sessionId)
                    .eq(ExamRegistration::getStatus, ExamRegistration.STATUS_CONFIRMED));
            if (count >= session.getQuota()) {
                throw new BizException("EXAM_QUOTA_FULL", "报名名额已满");
            }
        }

        ExamRegistration registration = new ExamRegistration();
        registration.setSessionId(sessionId);
        registration.setProjectId(session.getProjectId());
        registration.setUserId(userId);
        registration.setStatus(ExamRegistration.STATUS_CONFIRMED);
        registration.setTicketNo(BizNo.ticketNo());
        registration.setRequestId(request == null ? null : request.requestId());
        Entities.forInsert(registration);
        try {
            registrationMapper.insert(registration);
        } catch (DuplicateKeyException e) {
            // 并发重复报名：以唯一约束兜底，返回既有记录
            return toView(findRegistration(sessionId, userId), session);
        }

        // 报名成功通知：消息失败不影响报名结果（消息方法内部已兜底）
        messageService.publish(MsgTask.SCENE_REGISTER, "register-" + registration.getId(), userId,
                MessageTemplates.EXAM_REGISTER, Map.of(
                        "sessionName", session.getName(),
                        "ticketNo", registration.getTicketNo(),
                        "startAt", String.valueOf(session.getStartAt())));
        return toView(registration, session);
    }

    public ExamRegistration findRegistration(Long sessionId, Long userId) {
        return registrationMapper.selectOne(Wrappers.<ExamRegistration>lambdaQuery()
                .eq(ExamRegistration::getSessionId, sessionId)
                .eq(ExamRegistration::getUserId, userId));
    }

    public List<ExamRegistration> listRegistrations(Long sessionId) {
        return registrationMapper.selectList(Wrappers.<ExamRegistration>lambdaQuery()
                .eq(ExamRegistration::getSessionId, sessionId)
                .orderByDesc(ExamRegistration::getId));
    }

    public RegistrationView myRegistration(Long sessionId, Long userId) {
        ExamRegistration registration = findRegistration(sessionId, userId);
        return registration == null ? null : toView(registration, getSession(sessionId));
    }

    public RegistrationView toView(ExamRegistration registration, ExamSession session) {
        if (registration == null) {
            return null;
        }
        return new RegistrationView(registration.getId(), session.getId(), session.getName(),
                registration.getStatus(), registration.getTicketNo(),
                session.getStartAt(), session.getEndAt(), session.getDurationMinutes(),
                session.getNoticeVersion(), session.getNoticeContent());
    }

    private void apply(ExamSession session, SessionSaveRequest request) {
        session.setProjectId(request.projectId());
        session.setPaperId(request.paperId());
        session.setName(request.name());
        session.setRegisterStartAt(request.registerStartAt());
        session.setRegisterEndAt(request.registerEndAt());
        session.setStartAt(request.startAt());
        session.setEndAt(request.endAt());
        session.setDurationMinutes(request.durationMinutes() == null ? 60 : request.durationMinutes());
        session.setAllowEnterBeforeMinutes(request.allowEnterBeforeMinutes() == null
                ? 20 : request.allowEnterBeforeMinutes());
        session.setLateLimitMinutes(request.lateLimitMinutes() == null ? 0 : request.lateLimitMinutes());
        session.setResultPublishAt(request.resultPublishAt());
        session.setQuota(request.quota() == null ? 0 : request.quota());
        session.setNoticeVersion(request.noticeVersion() == null ? "v1" : request.noticeVersion());
        session.setNoticeContent(request.noticeContent());
    }

    private void validate(SessionSaveRequest request) {
        if (request.name() == null || request.name().isBlank()) {
            throw BizException.badRequest("场次名称不能为空");
        }
        if (request.projectId() == null) {
            throw BizException.badRequest("所属项目不能为空");
        }
        if (request.paperId() == null) {
            throw BizException.badRequest("试卷不能为空");
        }
        if (request.startAt() == null || request.endAt() == null) {
            throw BizException.badRequest("考试开始/结束时间不能为空");
        }
        if (!request.endAt().isAfter(request.startAt())) {
            throw BizException.badRequest("结束时间必须晚于开始时间");
        }
        if (request.registerStartAt() != null && request.registerEndAt() != null
                && request.registerEndAt().isBefore(request.registerStartAt())) {
            throw BizException.badRequest("报名结束时间不能早于报名开始时间");
        }
        contentService.getProject(request.projectId());
    }
}
