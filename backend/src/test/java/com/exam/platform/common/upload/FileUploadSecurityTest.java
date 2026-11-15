package com.exam.platform.common.upload;

import com.exam.platform.common.exception.BizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 上传安全：类型白名单、魔数比对、大小上限、路径穿越防护。 */
@DisplayName("文件上传：白名单 / 魔数 / 大小 / 路径穿越")
class FileUploadSecurityTest {

    private static final Set<String> WHITELIST = FileTypeValidator.parseWhitelist("jpg,png,pdf,csv,xlsx");

    private static final byte[] PNG = new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3};
    private static final byte[] JPG = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1, 2, 3};
    private static final byte[] PDF = "%PDF-1.7\nfake".getBytes(StandardCharsets.UTF_8);
    private static final byte[] ZIP = new byte[]{'P', 'K', 0x03, 0x04, 1, 2};
    private static final byte[] TEXT = "科目编码,题型\nPMP-BASE,SINGLE\n".getBytes(StandardCharsets.UTF_8);

    // ------------------------------------------------------------------ 类型白名单

    @Test
    @DisplayName("白名单外的扩展名被拒绝")
    void shouldRejectExtensionOutsideWhitelist() {
        assertThatThrownBy(() -> FileTypeValidator.validate("evil.exe", TEXT, WHITELIST))
                .isInstanceOf(BizException.class)
                .hasFieldOrPropertyWithValue("code", "UPLOAD_TYPE_NOT_ALLOWED");
    }

    @Test
    @DisplayName("缺少扩展名被拒绝")
    void shouldRejectMissingExtension() {
        assertThatThrownBy(() -> FileTypeValidator.validate("noext", TEXT, WHITELIST))
                .isInstanceOf(BizException.class)
                .hasFieldOrPropertyWithValue("code", "UPLOAD_EXTENSION_MISSING");
    }

    @Test
    @DisplayName("扩展名大小写不敏感，返回规范化小写")
    void shouldNormalizeExtensionCase() {
        assertThat(FileTypeValidator.validate("PHOTO.PNG", PNG, WHITELIST)).isEqualTo("png");
        assertThat(FileTypeValidator.validate("report.PDF", PDF, WHITELIST)).isEqualTo("pdf");
    }

    // ------------------------------------------------------------------ 魔数校验

    @Test
    @DisplayName("改后缀绕过会被魔数校验拦住")
    void shouldRejectContentMismatch() {
        // 扩展名是 png，内容却是 CSV 文本
        assertThatThrownBy(() -> FileTypeValidator.validate("fake.png", TEXT, WHITELIST))
                .isInstanceOf(BizException.class)
                .hasFieldOrPropertyWithValue("code", "UPLOAD_CONTENT_MISMATCH");
        // 扩展名是 pdf，内容却是 PNG
        assertThatThrownBy(() -> FileTypeValidator.validate("fake.pdf", PNG, WHITELIST))
                .isInstanceOf(BizException.class)
                .hasFieldOrPropertyWithValue("code", "UPLOAD_CONTENT_MISMATCH");
    }

    @Test
    @DisplayName("真实内容的图片 / PDF / xlsx 通过校验")
    void shouldAcceptGenuineContent() {
        assertThat(FileTypeValidator.validate("a.png", PNG, WHITELIST)).isEqualTo("png");
        assertThat(FileTypeValidator.validate("a.jpg", JPG, WHITELIST)).isEqualTo("jpg");
        assertThat(FileTypeValidator.validate("a.pdf", PDF, WHITELIST)).isEqualTo("pdf");
        assertThat(FileTypeValidator.validate("a.xlsx", ZIP, WHITELIST)).isEqualTo("xlsx");
        assertThat(FileTypeValidator.validate("a.csv", TEXT, WHITELIST)).isEqualTo("csv");
    }

    @Test
    @DisplayName("CSV 含二进制内容被拒绝")
    void shouldRejectBinaryCsv() {
        byte[] binary = new byte[]{'a', 0, 'b', 1, 2, 3};
        assertThatThrownBy(() -> FileTypeValidator.validate("a.csv", binary, WHITELIST))
                .isInstanceOf(BizException.class)
                .hasFieldOrPropertyWithValue("code", "UPLOAD_CONTENT_MISMATCH");
    }

    @Test
    @DisplayName("空文件被拒绝")
    void shouldRejectEmptyContent() {
        assertThatThrownBy(() -> FileTypeValidator.validate("a.png", new byte[0], WHITELIST))
                .isInstanceOf(BizException.class)
                .hasFieldOrPropertyWithValue("code", "UPLOAD_EMPTY_FILE");
    }

    // ------------------------------------------------------------------ 大小上限

    @Test
    @DisplayName("超过大小上限被拒绝")
    void shouldRejectOversizedFile() {
        UploadProperties properties = new UploadProperties();
        properties.setDir("target/test-uploads");
        properties.setMaxSizeBytes(16);
        FileStorageService service = new FileStorageService(properties);

        byte[] tooLarge = new byte[]{'%', 'P', 'D', 'F', 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13};
        assertThatThrownBy(() -> service.store("big.pdf", tooLarge))
                .isInstanceOf(BizException.class)
                .hasFieldOrPropertyWithValue("code", "UPLOAD_TOO_LARGE");
    }

    // ------------------------------------------------------------------ 路径穿越

    @Test
    @DisplayName("路径穿越、绝对路径、盘符路径一律拒绝")
    void shouldRejectPathTraversal() {
        UploadProperties properties = new UploadProperties();
        properties.setDir("target/test-uploads");
        FileStorageService service = new FileStorageService(properties);

        for (String evil : new String[]{
                "../../etc/passwd",
                "1/../../../etc/passwd",
                "/etc/passwd",
                "\\windows\\system32\\config",
                "C:/windows/system32/config"
        }) {
            assertThatThrownBy(() -> service.read(evil))
                    .as("应拒绝路径：%s", evil)
                    .isInstanceOf(BizException.class)
                    .hasFieldOrPropertyWithValue("code", "UPLOAD_PATH_INVALID");
        }
    }

    @Test
    @DisplayName("落盘名由服务端生成，不使用客户端文件名")
    void shouldRenameOnStore() {
        UploadProperties properties = new UploadProperties();
        properties.setDir("target/test-uploads");
        properties.setMaxSizeBytes(1024 * 1024);
        FileStorageService service = new FileStorageService(properties);

        FileStorageService.StoredFile stored = service.store("../../evil name.png", PNG);
        assertThat(stored.storedName()).matches("[0-9a-f]{32}\\.png");
        assertThat(stored.relativePath()).doesNotContain("..");
        assertThat(stored.originalName()).isEqualTo("../../evil name.png");

        // 落盘后可读回，且内容一致
        assertThat(service.read(stored.relativePath())).isEqualTo(PNG);
    }
}
