package com.exam.platform.modules.learning;

import com.exam.platform.common.api.Result;
import com.exam.platform.common.security.UserContext;
import com.exam.platform.modules.learning.dto.CreatePracticeRequest;
import com.exam.platform.modules.learning.dto.PracticeAnswerRequest;
import com.exam.platform.modules.learning.dto.PracticeAnswerResult;
import com.exam.platform.modules.learning.dto.PracticeStartResponse;
import com.exam.platform.modules.learning.dto.WrongQuestionView;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 学习中心接口。 */
@RestController
@RequestMapping("/v1")
@RequiredArgsConstructor
public class LearningController {

    private final LearningService learningService;

    @PostMapping("/practice/sessions")
    public Result<PracticeStartResponse> create(@RequestBody CreatePracticeRequest request) {
        return Result.ok(learningService.create(UserContext.userId(), request));
    }

    @GetMapping("/practice/sessions/{id}")
    public Result<PracticeStartResponse> detail(@PathVariable Long id) {
        return Result.ok(learningService.get(id, UserContext.userId()));
    }

    /** 学习记录：本人练习会话分页（小程序「学习记录」页用）。 */
    @GetMapping("/practice/sessions")
    public Result<com.exam.platform.common.api.PageResult<java.util.Map<String, Object>>> history(
            com.exam.platform.common.api.PageQuery pageQuery) {
        return Result.ok(learningService.history(UserContext.userId(), pageQuery));
    }

    /** 最近一次练习会话：断点续练入口（PRD 4.3），无记录时 data 为 null。 */
    @GetMapping("/practice/sessions/recent")
    public Result<PracticeStartResponse> recent(@RequestParam(required = false) Long subjectId) {
        return Result.ok(learningService.recent(UserContext.userId(), subjectId));
    }

    @PostMapping("/practice/sessions/{id}/answers")
    public Result<PracticeAnswerResult> answer(@PathVariable Long id,
                                               @RequestParam Long questionId,
                                               @RequestBody PracticeAnswerRequest request) {
        return Result.ok(learningService.answer(id, questionId, UserContext.userId(), request));
    }

    @GetMapping("/wrong-questions")
    public Result<List<WrongQuestionView>> wrongQuestions(
            @RequestParam(required = false) Long subjectId,
            @RequestParam(required = false) String source) {
        return Result.ok(learningService.wrongQuestions(UserContext.userId(), subjectId, source));
    }
}
