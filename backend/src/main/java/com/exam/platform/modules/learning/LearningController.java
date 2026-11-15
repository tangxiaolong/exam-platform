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

    @PostMapping("/practice/sessions/{id}/answers")
    public Result<PracticeAnswerResult> answer(@PathVariable Long id,
                                               @RequestParam Long questionId,
                                               @RequestBody PracticeAnswerRequest request) {
        return Result.ok(learningService.answer(id, questionId, UserContext.userId(), request));
    }

    @GetMapping("/wrong-questions")
    public Result<List<WrongQuestionView>> wrongQuestions(@RequestParam(required = false) Long subjectId) {
        return Result.ok(learningService.wrongQuestions(UserContext.userId(), subjectId));
    }
}
