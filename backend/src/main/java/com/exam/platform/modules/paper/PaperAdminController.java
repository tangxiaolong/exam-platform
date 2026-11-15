package com.exam.platform.modules.paper;

import com.exam.platform.common.api.PageQuery;
import com.exam.platform.common.api.PageResult;
import com.exam.platform.common.api.Result;
import com.exam.platform.modules.paper.entity.ExamPaper;
import com.exam.platform.modules.paper.entity.ExamPaperRule;
import com.exam.platform.modules.paper.entity.ExamPaperSnapshot;
import com.exam.platform.modules.paper.model.PaperSaveRequest;
import com.exam.platform.modules.paper.model.SnapshotContent;
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

/** 运营后台：试卷与组卷快照。 */
@RestController
@RequestMapping("/v1/admin/papers")
@RequiredArgsConstructor
public class PaperAdminController {

    private final PaperService paperService;

    @GetMapping
    public Result<PageResult<ExamPaper>> page(@RequestParam(required = false) String keyword,
                                             PageQuery pageQuery) {
        return Result.ok(paperService.page(keyword, pageQuery));
    }

    @GetMapping("/{id}")
    public Result<ExamPaper> detail(@PathVariable Long id) {
        return Result.ok(paperService.getPaper(id));
    }

    @PostMapping
    @Audited(action = "PAPER_CREATE", target = "'exam_paper:' + #result.data.id",
            detail = "#result.data.name")
    public Result<ExamPaper> create(@RequestBody PaperSaveRequest request) {
        return Result.ok(paperService.save(request));
    }

    @PutMapping("/{id}")
    @Audited(action = "PAPER_UPDATE", target = "'exam_paper:' + #id", detail = "#result.data.name")
    public Result<ExamPaper> update(@PathVariable Long id, @RequestBody PaperSaveRequest request) {
        return Result.ok(paperService.update(id, request));
    }

    @DeleteMapping("/{id}")
    @Audited(action = "PAPER_DELETE", target = "'exam_paper:' + #id")
    public Result<Void> delete(@PathVariable Long id) {
        paperService.delete(id);
        return Result.ok();
    }

    /** 试组卷：按规则抽题但不落库，返回将要生成的试卷内容（只读，不审计）。 */
    @PostMapping("/{id}/preview")
    public Result<SnapshotContent> preview(@PathVariable Long id) {
        return Result.ok(paperService.preview(id));
    }

    @GetMapping("/{id}/rules")
    public Result<List<ExamPaperRule>> rules(@PathVariable Long id) {
        return Result.ok(paperService.listRules(id));
    }

    /** 生成不可变快照，返回快照 ID，供场次引用。 */
    @PostMapping("/{id}/snapshots")
    @Audited(action = "PAPER_SNAPSHOT", target = "'exam_paper:' + #id",
            detail = "'snapshot=' + #result.data.id")
    public Result<ExamPaperSnapshot> snapshot(@PathVariable Long id) {
        return Result.ok(paperService.createSnapshot(id));
    }

    @GetMapping("/snapshots/{snapshotId}")
    public Result<SnapshotContent> snapshotContent(@PathVariable Long snapshotId) {
        return Result.ok(paperService.parseSnapshot(snapshotId));
    }
}
