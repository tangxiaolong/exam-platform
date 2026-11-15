package com.exam.platform.common.upload;

import com.exam.platform.common.api.Result;
import com.exam.platform.common.exception.BizException;
import com.exam.platform.modules.system.support.Audited;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 通用上传接口。
 *
 * <p>落盘前的完整校验链：白名单扩展名 → 大小上限 → 魔数比对 → 服务端重命名 → 路径穿越断言。
 * 下载走 query 参数而不是路径参数，避免 {@code ../} 在 URL 规范化阶段就变成另一个路径。</p>
 */
@RestController
@RequestMapping("/v1")
@RequiredArgsConstructor
public class UploadController {

    private final FileStorageService storageService;

    @PostMapping("/uploads")
    @Audited(action = "FILE_UPLOAD", target = "'upload:' + #result.data.storedName()",
            detail = "#result.data.originalName() + ' size=' + #result.data.size()")
    public Result<UploadView> upload(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BizException("UPLOAD_EMPTY_FILE", "请选择要上传的文件");
        }
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException e) {
            throw new BizException("UPLOAD_READ_FAILED", "读取上传文件失败");
        }
        FileStorageService.StoredFile stored = storageService.store(file.getOriginalFilename(), content);
        return Result.ok(new UploadView(stored.storedName(), stored.originalName(), stored.relativePath(),
                stored.size(), stored.extensionUpper()));
    }

    /** 下载已上传文件；越权路径由存储层拦截。 */
    @GetMapping("/uploads/content")
    public ResponseEntity<Resource> download(@RequestParam("path") String path) {
        byte[] content = storageService.read(path);
        String fileName = path.substring(path.lastIndexOf('/') + 1);
        String encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + fileName + "\"; filename*=UTF-8''" + encoded)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(new ByteArrayResource(content));
    }

    /** 上传结果。 */
    public record UploadView(String storedName, String originalName, String relativePath,
                             long size, String extension) {
    }
}
