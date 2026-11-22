package com.exam.platform.modules.question;

import com.exam.platform.common.api.PageQuery;
import com.exam.platform.common.api.PageResult;
import com.exam.platform.common.api.Result;
import com.exam.platform.common.exception.BizException;
import com.exam.platform.modules.question.imports.ImportReport;
import com.exam.platform.modules.question.imports.QuestionImportService;
import com.exam.platform.modules.question.imports.QuestionImportTemplate;
import com.exam.platform.modules.question.model.QuestionDetailView;
import com.exam.platform.modules.question.model.QuestionBatchStatusRequest;
import com.exam.platform.modules.question.model.QuestionBatchSubjectRequest;
import com.exam.platform.modules.question.model.QuestionSaveRequest;
import com.exam.platform.modules.question.model.QuestionView;
import com.exam.platform.modules.system.support.Audited;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** 题目接口：前台只返回题面，管理端返回完整内容。 */
@RestController
@RequestMapping("/v1")
@RequiredArgsConstructor
public class QuestionController {

    private final QuestionService questionService;
    private final QuestionImportService questionImportService;

    @GetMapping("/questions")
    public Result<PageResult<QuestionView>> practiceQuestions(@RequestParam(required = false) Long subjectId,
                                                              @RequestParam(required = false) String type,
                                                              @RequestParam(required = false) String keyword,
                                                              PageQuery pageQuery) {
        return Result.ok(questionService.pageForPractice(subjectId, type, keyword, pageQuery));
    }

    @GetMapping("/questions/{id}")
    public Result<QuestionView> practiceQuestion(@PathVariable Long id) {
        return Result.ok(questionService.view(id));
    }

    @GetMapping("/admin/questions")
    public Result<PageResult<QuestionDetailView>> adminQuestions(@RequestParam(required = false) Long subjectId,
                                                                 @RequestParam(required = false) String type,
                                                                 @RequestParam(required = false) String keyword,
                                                                 @RequestParam(required = false) String status,
                                                                 @RequestParam(required = false) String source,
                                                                 PageQuery pageQuery) {
        return Result.ok(questionService.pageForAdmin(subjectId, type, keyword, status, source, pageQuery));
    }

    @GetMapping("/admin/questions/{id}")
    public Result<QuestionDetailView> adminQuestion(@PathVariable Long id) {
        return Result.ok(questionService.detail(id));
    }

    @PostMapping("/admin/questions")
    @Audited(action = "QUESTION_CREATE", target = "'qb_question:' + #result.data.id()",
            detail = "#result.data.stem()")
    public Result<QuestionDetailView> create(@RequestBody QuestionSaveRequest request) {
        var saved = questionService.save(request);
        return Result.ok(questionService.detail(saved.getId()));
    }

    @PutMapping("/admin/questions/{id}")
    @Audited(action = "QUESTION_UPDATE", target = "'qb_question:' + #id", detail = "#result.data.stem()")
    public Result<QuestionDetailView> update(@PathVariable Long id, @RequestBody QuestionSaveRequest request) {
        questionService.update(id, request);
        return Result.ok(questionService.detail(id));
    }

    @PutMapping("/admin/questions/{id}/status")
    @Audited(action = "QUESTION_STATUS", target = "'qb_question:' + #id", detail = "#status")
    public Result<Void> changeStatus(@PathVariable Long id, @RequestParam String status) {
        questionService.changeStatus(id, status);
        return Result.ok();
    }

    /** 批量启停：逐条沿用单条校验路径（知识点开关等规则不会被批量绕开），一次上限 200 条。 */
    @PutMapping("/admin/questions/batch-status")
    @Audited(action = "QUESTION_BATCH_STATUS", target = "'qb_question_batch'",
            detail = "'count=' + #request.ids().size() + ', status=' + #request.status()")
    public Result<Integer> batchChangeStatus(@RequestBody QuestionBatchStatusRequest request) {
        return Result.ok(questionService.batchChangeStatus(request.ids(), request.status()));
    }

    /** 批量移动科目：一次性把一批题挂到另一科目下；卷快照已冻结，历史卷面不受影响。 */
    @PutMapping("/admin/questions/batch-subject")
    @Audited(action = "QUESTION_BATCH_SUBJECT", target = "'qb_question_batch'",
            detail = "'count=' + #request.ids().size() + ', subjectId=' + #request.subjectId()")
    public Result<Integer> batchChangeSubject(@RequestBody QuestionBatchSubjectRequest request) {
        return Result.ok(questionService.batchChangeSubject(request.ids(), request.subjectId()));
    }

    @DeleteMapping("/admin/questions/{id}")
    @Audited(action = "QUESTION_DELETE", target = "'qb_question:' + #id")
    public Result<Void> delete(@PathVariable Long id) {
        questionService.delete(id);
        return Result.ok();
    }

    /**
     * 题目批量导入（csv / xlsx）。
     *
     * <p>逐行校验，整批成功才落库；返回每一行的成功/失败明细，
     * 对齐验收要求「导入成功/失败行均有结果报告」。</p>
     */
    @PostMapping("/admin/questions/imports")
    @Audited(action = "QUESTION_IMPORT", target = "'qb_question:batch'",
            detail = "'rows=' + #result.data.totalRows() + ' success=' + #result.data.successCount()"
                    + " + ' failed=' + #result.data.failureCount()"
                    + " + ' committed=' + #result.data.committed()")
    public Result<ImportReport> importQuestions(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BizException("QUESTION_IMPORT_EMPTY", "请选择要导入的文件");
        }
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException e) {
            throw new BizException("QUESTION_IMPORT_READ_FAILED", "读取导入文件失败");
        }
        return Result.ok(questionImportService.importQuestions(file.getOriginalFilename(), content));
    }

    /**
     * 下载导入模板。
     *
     * <p>模板里的三行示例用的是当前租户真实科目编码，运营下载后可以原样导一遍先确认链路通，
     * 再把自己的题目贴进去——比给一份「表头正确但示例跑不通」的空模板有用得多。</p>
     */
    @GetMapping("/admin/questions/imports/template")
    public ResponseEntity<Resource> importTemplate() {
        byte[] content = QuestionImportTemplate.csv(questionImportService.firstSubjectCode());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"question-import-template.csv\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(new ByteArrayResource(content));
    }
}
