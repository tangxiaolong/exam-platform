package com.exam.platform.modules.content;

import com.exam.platform.common.api.PageQuery;
import com.exam.platform.common.api.PageResult;
import com.exam.platform.common.api.Result;
import com.exam.platform.modules.content.entity.ExamKnowledgePoint;
import com.exam.platform.modules.content.entity.ExamProject;
import com.exam.platform.modules.content.entity.ExamSubject;
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

/** 运营后台：项目 / 科目 / 知识点维护。 */
@RestController
@RequestMapping("/v1/admin")
@RequiredArgsConstructor
public class ContentAdminController {

    private final ContentService contentService;

    @GetMapping("/projects")
    public Result<PageResult<ExamProject>> pageProjects(@RequestParam(required = false) String keyword,
                                                        @RequestParam(required = false) String status,
                                                        PageQuery pageQuery) {
        return Result.ok(contentService.pageProjects(keyword, status, pageQuery, false));
    }

    @GetMapping("/projects/{id}")
    public Result<ExamProject> project(@PathVariable Long id) {
        return Result.ok(contentService.getProject(id));
    }

    @PostMapping("/projects")
    @Audited(action = "PROJECT_CREATE", target = "'exam_project:' + #result.data.id",
            detail = "#result.data.name")
    public Result<ExamProject> createProject(@RequestBody ExamProject project) {
        return Result.ok(contentService.saveProject(project));
    }

    @PutMapping("/projects/{id}")
    @Audited(action = "PROJECT_UPDATE", target = "'exam_project:' + #id", detail = "#result.data.name")
    public Result<ExamProject> updateProject(@PathVariable Long id, @RequestBody ExamProject project) {
        return Result.ok(contentService.updateProject(id, project));
    }

    @DeleteMapping("/projects/{id}")
    @Audited(action = "PROJECT_DELETE", target = "'exam_project:' + #id")
    public Result<Void> deleteProject(@PathVariable Long id) {
        contentService.deleteProject(id);
        return Result.ok();
    }

    @GetMapping("/projects/{id}/subjects")
    public Result<List<ExamSubject>> subjects(@PathVariable Long id) {
        return Result.ok(contentService.listSubjects(id, false));
    }

    @PostMapping("/subjects")
    @Audited(action = "SUBJECT_CREATE", target = "'exam_subject:' + #result.data.id",
            detail = "#result.data.name")
    public Result<ExamSubject> createSubject(@RequestBody ExamSubject subject) {
        return Result.ok(contentService.saveSubject(subject));
    }

    @PutMapping("/subjects/{id}")
    @Audited(action = "SUBJECT_UPDATE", target = "'exam_subject:' + #id", detail = "#result.data.name")
    public Result<ExamSubject> updateSubject(@PathVariable Long id, @RequestBody ExamSubject subject) {
        return Result.ok(contentService.updateSubject(id, subject));
    }

    @DeleteMapping("/subjects/{id}")
    @Audited(action = "SUBJECT_DELETE", target = "'exam_subject:' + #id")
    public Result<Void> deleteSubject(@PathVariable Long id) {
        contentService.deleteSubject(id);
        return Result.ok();
    }

    @GetMapping("/subjects/{id}/knowledge-points")
    public Result<List<ExamKnowledgePoint>> knowledgePoints(@PathVariable Long id) {
        return Result.ok(contentService.listKnowledgePoints(id));
    }

    @PostMapping("/knowledge-points")
    @Audited(action = "KNOWLEDGE_POINT_CREATE", target = "'exam_knowledge_point:' + #result.data.id",
            detail = "#result.data.name")
    public Result<ExamKnowledgePoint> createKnowledgePoint(@RequestBody ExamKnowledgePoint point) {
        return Result.ok(contentService.saveKnowledgePoint(point));
    }

    @PutMapping("/knowledge-points/{id}")
    @Audited(action = "KNOWLEDGE_POINT_UPDATE", target = "'exam_knowledge_point:' + #id",
            detail = "#result.data.name")
    public Result<ExamKnowledgePoint> updateKnowledgePoint(@PathVariable Long id,
                                                           @RequestBody ExamKnowledgePoint point) {
        return Result.ok(contentService.updateKnowledgePoint(id, point));
    }

    @DeleteMapping("/knowledge-points/{id}")
    @Audited(action = "KNOWLEDGE_POINT_DELETE", target = "'exam_knowledge_point:' + #id")
    public Result<Void> deleteKnowledgePoint(@PathVariable Long id) {
        contentService.deleteKnowledgePoint(id);
        return Result.ok();
    }
}
