package com.exam.platform.modules.content;

import com.exam.platform.common.api.PageQuery;
import com.exam.platform.common.api.PageResult;
import com.exam.platform.common.api.Result;
import com.exam.platform.modules.content.entity.ExamProject;
import com.exam.platform.modules.content.entity.ExamSubject;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 小程序侧公开内容：只返回已发布项目与启用科目。 */
@RestController
@RequestMapping("/v1")
@RequiredArgsConstructor
public class ContentController {

    private final ContentService contentService;

    @GetMapping("/projects")
    public Result<PageResult<ExamProject>> projects(@RequestParam(required = false) String keyword,
                                                    PageQuery pageQuery) {
        return Result.ok(contentService.pageProjects(keyword, null, pageQuery, true));
    }

    @GetMapping("/projects/{id}")
    public Result<ExamProject> project(@PathVariable Long id) {
        return Result.ok(contentService.getProject(id));
    }

    @GetMapping("/projects/{id}/subjects")
    public Result<List<ExamSubject>> subjects(@PathVariable Long id) {
        return Result.ok(contentService.listSubjects(id, true));
    }
}
