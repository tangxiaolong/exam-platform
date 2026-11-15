package com.exam.platform.common.upload;

import com.exam.platform.common.exception.BizException;
import com.exam.platform.common.security.UserContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * 文件落盘服务。
 *
 * <p>安全要点：</p>
 * <ol>
 *   <li>不信任客户端文件名——落盘名由服务端生成（UUID + 校验过的扩展名），原名只作记录；</li>
 *   <li>不信任拼接后的路径——写盘前把最终路径规范化，再断言仍在根目录内，
 *       阻断 {@code ../}、绝对路径、软链接逃逸等路径穿越写法；</li>
 *   <li>按租户 + 日期分目录，天然限制单目录文件数，也便于按租户清理。</li>
 * </ol>
 */
@Slf4j
@Service
public class FileStorageService {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final UploadProperties properties;

    public FileStorageService(UploadProperties properties) {
        this.properties = properties;
    }

    /** 校验并保存，返回相对路径（相对上传根目录，不含根目录本身）。 */
    public StoredFile store(String originalFileName, byte[] content) {
        long maxBytes = Math.max(1L, properties.getMaxSizeBytes());
        if (content != null && content.length > maxBytes) {
            throw new BizException("UPLOAD_TOO_LARGE",
                    "文件超过大小上限 " + (maxBytes / 1024 / 1024) + "MB");
        }
        Set<String> whitelist = FileTypeValidator.parseWhitelist(properties.getAllowedExtensions());
        String extension = FileTypeValidator.validate(originalFileName, content, whitelist);

        Path root = rootDir();
        String relativeDir = UserContext.tenantId() + "/" + LocalDate.now().format(DAY);
        String storedName = UUID.randomUUID().toString().replace("-", "") + "." + extension;

        Path target = resolveInsideRoot(root, relativeDir + "/" + storedName);
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, content);
        } catch (IOException e) {
            throw new IllegalStateException("写入上传文件失败", e);
        }
        log.info("文件已保存 tenantId={} size={} name={}", UserContext.tenantId(), content.length, storedName);
        return new StoredFile(storedName, sanitizeForLog(originalFileName), relativeDir + "/" + storedName,
                content.length, extension);
    }

    /** 读取已保存文件；路径必须落在根目录内。 */
    public byte[] read(String relativePath) {
        Path root = rootDir();
        Path target = resolveInsideRoot(root, relativePath);
        if (!Files.exists(target)) {
            throw new BizException("UPLOAD_FILE_MISSING", "文件不存在或已被清理");
        }
        try {
            return Files.readAllBytes(target);
        } catch (IOException e) {
            throw new IllegalStateException("读取上传文件失败", e);
        }
    }

    /**
     * 把相对路径解析为根目录内的绝对路径。
     *
     * <p>先规范化再比对前缀：{@code root/../../etc/passwd} 规范化后就不在 root 下了。
     * 注意必须先 normalize 再判断，否则 {@code ../} 会被当成普通字符放过。</p>
     */
    Path resolveInsideRoot(Path root, String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            throw new BizException("UPLOAD_PATH_INVALID", "文件路径不能为空");
        }
        // 绝对路径或盘符路径一律拒绝，避免绕过根目录拼接
        if (relativePath.startsWith("/") || relativePath.startsWith("\\")
                || relativePath.matches("^[A-Za-z]:.*")) {
            throw new BizException("UPLOAD_PATH_INVALID", "文件路径不合法");
        }
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path target = normalizedRoot.resolve(relativePath).toAbsolutePath().normalize();
        if (!target.startsWith(normalizedRoot)) {
            throw new BizException("UPLOAD_PATH_INVALID", "文件路径越权");
        }
        return target;
    }

    Path rootDir() {
        return Paths.get(properties.getDir()).toAbsolutePath().normalize();
    }

    /** 原始文件名只用于记录，写日志前去掉换行等会污染日志的字符。 */
    private String sanitizeForLog(String name) {
        if (name == null) {
            return "";
        }
        String cleaned = name.replaceAll("[\\r\\n\\t]", "_");
        return cleaned.length() > 120 ? cleaned.substring(0, 120) : cleaned;
    }

    /** 落盘结果。 */
    public record StoredFile(String storedName, String originalName, String relativePath,
                             long size, String extension) {

        public String extensionUpper() {
            return extension == null ? "" : extension.toUpperCase(Locale.ROOT);
        }
    }
}
