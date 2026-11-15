package com.exam.platform.modules.exam;

import com.exam.platform.common.api.PageQuery;
import com.exam.platform.common.api.PageResult;
import com.exam.platform.common.api.Result;
import com.exam.platform.modules.exam.dto.AttemptView;
import com.exam.platform.modules.exam.dto.ExamResultView;
import com.exam.platform.modules.exam.dto.MarkingItemView;
import com.exam.platform.modules.exam.dto.SessionSaveRequest;
import com.exam.platform.modules.exam.entity.ExamRegistration;
import com.exam.platform.modules.exam.entity.ExamResultHistory;
import com.exam.platform.modules.exam.entity.ExamSession;
import com.exam.platform.modules.system.support.Audited;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 运营后台：场次发布、报名名单、考试监控（含强制收卷）。 */
@RestController
@RequestMapping("/v1/admin")
@RequiredArgsConstructor
public class ExamAdminController {

    private final ExamSessionService sessionService;
    private final ExamAttemptService attemptService;
    private final MarkingService markingService;

    @GetMapping("/sessions")
    public Result<PageResult<ExamSession>> page(@RequestParam(required = false) String keyword,
                                                @RequestParam(required = false) String status,
                                                PageQuery pageQuery) {
        return Result.ok(sessionService.pageForAdmin(keyword, status, pageQuery));
    }

    @GetMapping("/sessions/{id}")
    public Result<ExamSession> detail(@PathVariable Long id) {
        return Result.ok(sessionService.getSession(id));
    }

    @PostMapping("/sessions")
    @Audited(action = "SESSION_CREATE", target = "'exam_session:' + #result.data.id",
            detail = "#result.data.name")
    public Result<ExamSession> create(@RequestBody SessionSaveRequest request) {
        return Result.ok(sessionService.save(request));
    }

    @PutMapping("/sessions/{id}")
    @Audited(action = "SESSION_UPDATE", target = "'exam_session:' + #id", detail = "#result.data.name")
    public Result<ExamSession> update(@PathVariable Long id, @RequestBody SessionSaveRequest request) {
        return Result.ok(sessionService.update(id, request));
    }

    @DeleteMapping("/sessions/{id}")
    @Audited(action = "SESSION_DELETE", target = "'exam_session:' + #id")
    public Result<Void> delete(@PathVariable Long id) {
        sessionService.delete(id);
        return Result.ok();
    }

    /** 发布场次：生成不可变试卷快照。 */
    @PostMapping("/sessions/{id}/publish")
    @Audited(action = "SESSION_PUBLISH", target = "'exam_session:' + #id",
            detail = "'snapshot=' + #result.data.snapshotId")
    public Result<ExamSession> publish(@PathVariable Long id) {
        return Result.ok(sessionService.publish(id));
    }

    @PostMapping("/sessions/{id}/close")
    @Audited(action = "SESSION_CLOSE", target = "'exam_session:' + #id")
    public Result<Void> close(@PathVariable Long id) {
        sessionService.close(id);
        return Result.ok();
    }

    @GetMapping("/sessions/{id}/registrations")
    public Result<List<ExamRegistration>> registrations(@PathVariable Long id) {
        return Result.ok(sessionService.listRegistrations(id));
    }

    @GetMapping("/sessions/{id}/attempts")
    public Result<List<AttemptView>> attempts(@PathVariable Long id) {
        return Result.ok(attemptService.listBySession(id));
    }

    /** 强制收卷（异常处理），重复调用返回既有成绩。 */
    @PostMapping("/attempts/{attemptId}/force-submit")
    @Audited(action = "ATTEMPT_FORCE_SUBMIT", target = "'exam_attempt:' + #attemptId")
    public Result<ExamResultView> forceSubmit(@PathVariable Long attemptId) {
        return Result.ok(attemptService.doSubmit(attemptService.getAttempt(attemptId), "ADMIN_FORCE"));
    }

    // ------------------------------------------------------------ 人工阅卷

    /** 阅卷队列（默认 REVIEWING 待阅卷）。 */
    @GetMapping("/marking/queue")
    public Result<PageResult<AttemptView>> markingQueue(@RequestParam(defaultValue = "REVIEWING") String status,
                                                       PageQuery pageQuery) {
        return Result.ok(attemptService.pageByStatus(status, pageQuery));
    }

    @GetMapping("/attempts/{attemptId}/marking")
    public Result<List<MarkingItemView>> markingList(@PathVariable Long attemptId) {
        return Result.ok(markingService.list(attemptId));
    }

    /** 逐题打分，可反复调整直到“完成阅卷”。 */
    @PutMapping("/attempts/{attemptId}/marking/{questionId}")
    @Audited(action = "MARKING_SCORE", target = "'exam_attempt:' + #attemptId",
            detail = "'question=' + #questionId + ' score=' + #score")
    public Result<MarkingItemView> mark(@PathVariable Long attemptId,
                                        @PathVariable Long questionId,
                                        @RequestParam Integer score,
                                        @RequestParam(required = false) String remark) {
        return Result.ok(markingService.mark(attemptId, questionId, score, remark));
    }

    /** 结束阅卷：全部主观题评分完成后成绩生效，并按场次策略发布。 */
    @PostMapping("/attempts/{attemptId}/marking/finish")
    @Audited(action = "MARKING_FINISH", target = "'exam_attempt:' + #attemptId")
    public Result<ExamResultView> finishMarking(@PathVariable Long attemptId) {
        return Result.ok(markingService.finish(attemptId));
    }

    /** 成绩更正：必须填写原因，保留历史版本。 */
    @PostMapping("/attempts/{attemptId}/results/correct")
    @Audited(action = "RESULT_CORRECT", target = "'exam_attempt:' + #attemptId",
            detail = "'question=' + #questionId + ' score=' + #score + ' reason=' + #reason")
    public Result<ExamResultView> correctResult(@PathVariable Long attemptId,
                                                @RequestParam Long questionId,
                                                @RequestParam Integer score,
                                                @RequestParam String reason) {
        return Result.ok(markingService.correct(attemptId, questionId, score, reason));
    }

    @GetMapping("/attempts/{attemptId}/results/history")
    public Result<List<ExamResultHistory>> resultHistory(@PathVariable Long attemptId) {
        return Result.ok(markingService.history(attemptId));
    }

    @GetMapping("/attempts/{attemptId}/results")
    public Result<ExamResultView> attemptResult(@PathVariable Long attemptId) {
        return Result.ok(attemptService.resultForAdmin(attemptId));
    }
}
