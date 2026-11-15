package com.exam.platform.modules.course;

import com.exam.platform.common.api.PageQuery;
import com.exam.platform.common.api.PageResult;
import com.exam.platform.common.api.Result;
import com.exam.platform.common.security.UserContext;
import com.exam.platform.modules.course.dto.CourseDetailView;
import com.exam.platform.modules.course.dto.LessonView;
import com.exam.platform.modules.course.entity.CourseInfo;
import com.exam.platform.modules.course.entity.CourseProgress;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 考生端：课程浏览、课时观看与学习进度上报。 */
@RestController
@RequestMapping("/v1/courses")
@RequiredArgsConstructor
public class CourseController {

    private final CourseService courseService;

    @GetMapping
    public Result<PageResult<CourseInfo>> page(@RequestParam(required = false) Long projectId,
                                              @RequestParam(required = false) String keyword,
                                              PageQuery pageQuery) {
        return Result.ok(courseService.pagePublished(projectId, keyword, pageQuery));
    }

    /** 课程详情：未开通付费课程时付费课时内容不下发。 */
    @GetMapping("/{id}")
    public Result<CourseDetailView> detail(@PathVariable Long id) {
        return Result.ok(courseService.detail(id, UserContext.userId()));
    }

    @GetMapping("/{id}/lessons/{lessonId}")
    public Result<LessonView> lesson(@PathVariable Long id, @PathVariable Long lessonId) {
        return Result.ok(courseService.lesson(id, lessonId, UserContext.userId()));
    }

    /** 上报学习进度（断点续学依靠该接口累积）。 */
    @PostMapping("/{id}/lessons/{lessonId}/progress")
    public Result<CourseProgress> report(@PathVariable Long id,
                                         @PathVariable Long lessonId,
                                         @RequestParam(defaultValue = "0") Integer progressSeconds,
                                         @RequestParam(defaultValue = "false") boolean finished) {
        return Result.ok(courseService.reportProgress(id, lessonId, UserContext.userId(),
                progressSeconds, finished));
    }
}
