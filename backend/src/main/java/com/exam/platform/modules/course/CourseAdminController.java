package com.exam.platform.modules.course;

import com.exam.platform.common.api.PageQuery;
import com.exam.platform.common.api.PageResult;
import com.exam.platform.common.api.Result;
import com.exam.platform.modules.course.dto.CourseSaveRequest;
import com.exam.platform.modules.course.dto.LessonSaveRequest;
import com.exam.platform.modules.course.entity.CourseInfo;
import com.exam.platform.modules.course.entity.CourseLesson;
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

/** 运营后台：课程与课时维护。 */
@RestController
@RequestMapping("/v1/admin/courses")
@RequiredArgsConstructor
public class CourseAdminController {

    private final CourseService courseService;

    @GetMapping
    public Result<PageResult<CourseInfo>> page(@RequestParam(required = false) String keyword,
                                              @RequestParam(required = false) String status,
                                              @RequestParam(required = false) Long projectId,
                                              PageQuery pageQuery) {
        return Result.ok(courseService.page(keyword, status, projectId, pageQuery));
    }

    @GetMapping("/{id}")
    public Result<CourseInfo> detail(@PathVariable Long id) {
        return Result.ok(courseService.getCourse(id));
    }

    @PostMapping
    @Audited(action = "COURSE_CREATE", target = "'course_info:' + #result.data.id",
            detail = "#result.data.title")
    public Result<CourseInfo> create(@RequestBody CourseSaveRequest request) {
        return Result.ok(courseService.save(request));
    }

    @PutMapping("/{id}")
    @Audited(action = "COURSE_UPDATE", target = "'course_info:' + #id", detail = "#result.data.status")
    public Result<CourseInfo> update(@PathVariable Long id, @RequestBody CourseSaveRequest request) {
        return Result.ok(courseService.update(id, request));
    }

    @DeleteMapping("/{id}")
    @Audited(action = "COURSE_DELETE", target = "'course_info:' + #id")
    public Result<Void> delete(@PathVariable Long id) {
        courseService.delete(id);
        return Result.ok();
    }

    @GetMapping("/{id}/lessons")
    public Result<List<CourseLesson>> lessons(@PathVariable Long id) {
        return Result.ok(courseService.listLessons(id));
    }

    @PostMapping("/{id}/lessons")
    @Audited(action = "LESSON_CREATE", target = "'course_lesson:' + #result.data.id", detail = "#id")
    public Result<CourseLesson> createLesson(@PathVariable Long id, @RequestBody LessonSaveRequest request) {
        return Result.ok(courseService.saveLesson(id, request));
    }

    @PutMapping("/lessons/{lessonId}")
    @Audited(action = "LESSON_UPDATE", target = "'course_lesson:' + #lessonId",
            detail = "#result.data.title")
    public Result<CourseLesson> updateLesson(@PathVariable Long lessonId,
                                             @RequestBody LessonSaveRequest request) {
        return Result.ok(courseService.updateLesson(lessonId, request));
    }

    @DeleteMapping("/lessons/{lessonId}")
    @Audited(action = "LESSON_DELETE", target = "'course_lesson:' + #lessonId")
    public Result<Void> deleteLesson(@PathVariable Long lessonId) {
        courseService.deleteLesson(lessonId);
        return Result.ok();
    }
}
