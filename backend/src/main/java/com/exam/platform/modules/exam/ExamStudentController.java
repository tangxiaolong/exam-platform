package com.exam.platform.modules.exam;

import com.exam.platform.common.api.Result;
import com.exam.platform.common.security.UserContext;
import com.exam.platform.modules.exam.dto.AttemptStartResponse;
import com.exam.platform.modules.exam.dto.AttemptView;
import com.exam.platform.modules.exam.dto.ExamResultView;
import com.exam.platform.modules.exam.dto.RegisterRequest;
import com.exam.platform.modules.exam.dto.RegistrationView;
import com.exam.platform.modules.exam.dto.SaveAnswerRequest;
import com.exam.platform.modules.exam.dto.SaveAnswerResponse;
import com.exam.platform.modules.exam.dto.SubmitRequest;
import com.exam.platform.modules.exam.entity.ExamSession;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 考生端考试接口。 */
@RestController
@RequestMapping("/v1")
@RequiredArgsConstructor
public class ExamStudentController {

    private final ExamSessionService sessionService;
    private final ExamAttemptService attemptService;

    @GetMapping("/exam-sessions")
    public Result<List<ExamSession>> sessions(@RequestParam(required = false) Long projectId) {
        return Result.ok(sessionService.listParticipable(projectId));
    }

    @GetMapping("/exam-sessions/{id}")
    public Result<ExamSession> session(@PathVariable Long id) {
        return Result.ok(sessionService.getSession(id));
    }

    @GetMapping("/exam-sessions/{id}/registration")
    public Result<RegistrationView> myRegistration(@PathVariable Long id) {
        return Result.ok(sessionService.myRegistration(id, UserContext.userId()));
    }

    @PostMapping("/exam-sessions/{id}/registrations")
    public Result<RegistrationView> register(@PathVariable Long id,
                                            @RequestBody(required = false) RegisterRequest request) {
        return Result.ok(sessionService.register(id, UserContext.userId(), request));
    }

    /** 开始考试：重复调用返回同一份考试记录。 */
    @PostMapping("/exam-sessions/{id}/attempts")
    public Result<AttemptStartResponse> start(@PathVariable Long id) {
        return Result.ok(attemptService.start(id, UserContext.userId()));
    }

    /** 恢复考试：以服务端已保存答案为准。 */
    @GetMapping("/attempts/{attemptId}/resume")
    public Result<AttemptStartResponse> resume(@PathVariable Long attemptId) {
        return Result.ok(attemptService.resume(attemptId, UserContext.userId()));
    }

    /** 保存答案：幂等 + 版本控制，不返回标准答案。 */
    @PutMapping("/attempts/{attemptId}/answers/{questionId}")
    public Result<SaveAnswerResponse> saveAnswer(@PathVariable Long attemptId,
                                                 @PathVariable Long questionId,
                                                 @RequestBody SaveAnswerRequest request) {
        return Result.ok(attemptService.saveAnswer(attemptId, questionId, UserContext.userId(), request));
    }

    @PostMapping("/attempts/{attemptId}/submit")
    public Result<ExamResultView> submit(@PathVariable Long attemptId,
                                         @RequestBody(required = false) SubmitRequest request) {
        String reason = request == null ? null : request.reason();
        return Result.ok(attemptService.submit(attemptId, UserContext.userId(), reason));
    }

    @GetMapping("/attempts/{attemptId}/result")
    public Result<ExamResultView> result(@PathVariable Long attemptId) {
        return Result.ok(attemptService.result(attemptId, UserContext.userId()));
    }

    @GetMapping("/attempts")
    public Result<List<AttemptView>> myAttempts() {
        return Result.ok(attemptService.myAttempts(UserContext.userId()));
    }
}
