package com.exam.platform.modules.report;

import com.exam.platform.common.api.PageQuery;
import com.exam.platform.common.api.PageResult;
import com.exam.platform.common.api.Result;
import com.exam.platform.modules.report.dto.ExportRequest;
import com.exam.platform.modules.report.dto.ExportTaskView;
import com.exam.platform.modules.system.support.Audited;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** 运营后台：异步报表导出。 */
@RestController
@RequestMapping("/v1/admin/exports")
@RequiredArgsConstructor
public class ExportController {

    private final ExportService exportService;

    /** 提交导出任务，立即返回任务 ID（前端轮询状态）。 */
    @PostMapping
    @Audited(action = "REPORT_EXPORT", target = "'export_task:' + #result.data.id()",
            detail = "#result.data.type() + ' taskNo=' + #result.data.taskNo()")
    public Result<ExportTaskView> submit(@RequestBody ExportRequest request) {
        return Result.ok(exportService.submit(request));
    }

    /** 我的导出任务（默认只返回本人提交的）。 */
    @GetMapping
    public Result<PageResult<ExportTaskView>> myTasks(PageQuery pageQuery) {
        return Result.ok(exportService.pageMy(pageQuery));
    }

    @GetMapping("/{id}")
    public Result<ExportTaskView> detail(@PathVariable Long id) {
        return Result.ok(exportService.get(id));
    }

    /** 下载导出文件（CSV，带 BOM 便于 Excel 直接打开）。 */
    @GetMapping("/{id}/download")
    @Audited(action = "REPORT_EXPORT_DOWNLOAD", target = "'export_task:' + #id", detail = "'download'")
    public ResponseEntity<Resource> download(@PathVariable Long id) {
        ExportService.ExportFile file = exportService.download(id);
        String encoded = URLEncoder.encode(file.fileName(), StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + file.fileName() + "\"; filename*=UTF-8''" + encoded)
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(new ByteArrayResource(file.content()));
    }
}
