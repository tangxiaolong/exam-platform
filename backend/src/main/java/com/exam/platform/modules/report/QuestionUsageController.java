package com.exam.platform.modules.report;

import com.exam.platform.common.api.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 运营后台：题库使用分析。 */
@RestController
@RequestMapping("/v1/admin")
@RequiredArgsConstructor
public class QuestionUsageController {

    private final QuestionUsageService usageService;

    /**
     * 跨场次题库使用分析：时间窗内被抽中的次数、答对次数、正确率（低者在前）。
     *
     * @param days      回溯天数，默认 90，最大 365
     * @param subjectId 可选科目过滤
     * @param limit     返回条数，默认 100，最大 200
     */
    @GetMapping("/question-usage")
    public Result<List<QuestionUsageView>> analyze(
            @RequestParam(defaultValue = "90") int days,
            @RequestParam(required = false) Long subjectId,
            @RequestParam(defaultValue = "100") int limit) {
        return Result.ok(usageService.analyze(days, subjectId, limit));
    }
}
