package com.exam.platform.common.upload;

import com.exam.platform.common.exception.BizException;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 上传文件类型校验：白名单扩展名 + 文件头魔数比对。
 *
 * <p>只看扩展名会被「改后缀」绕过，因此对二进制格式强制校验魔数；
 * 扩展名与真实内容不一致时直接拒绝，而不是按扩展名放行。</p>
 */
public final class FileTypeValidator {

    private static final Set<String> IMAGE_EXTENSIONS = Set.of("jpg", "jpeg", "png");
    private static final Set<String> DOCUMENT_EXTENSIONS = Set.of("pdf", "csv", "xlsx");

    /** 各扩展名对应的合法魔数（按前缀字节匹配）。 */
    private static final Map<String, byte[][]> MAGIC = Map.of(
            "jpg", new byte[][]{{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}},
            "jpeg", new byte[][]{{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}},
            "png", new byte[][]{{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A}},
            "pdf", new byte[][]{{'%', 'P', 'D', 'F'}},
            // xlsx 本质是 zip 包
            "xlsx", new byte[][]{{'P', 'K', 0x03, 0x04}, {'P', 'K', 0x05, 0x06}}
    );

    private FileTypeValidator() {
    }

    /** 解析逗号分隔的白名单，统一小写，去重保序。 */
    public static Set<String> parseWhitelist(String raw) {
        Set<String> allowed = new LinkedHashSet<>();
        if (raw == null || raw.isBlank()) {
            return allowed;
        }
        Arrays.stream(raw.split(","))
                .map(String::trim)
                .map(item -> item.toLowerCase(Locale.ROOT))
                .filter(item -> !item.isEmpty())
                .forEach(allowed::add);
        return allowed;
    }

    /**
     * 校验通过后返回规范化的小写扩展名。
     *
     * @throws BizException 白名单外、扩展名缺失、或内容与扩展名不符
     */
    public static String validate(String originalFileName, byte[] content, Set<String> whitelist) {
        String extension = extensionOf(originalFileName);
        if (extension.isEmpty()) {
            throw new BizException("UPLOAD_EXTENSION_MISSING", "文件名缺少扩展名");
        }
        if (!whitelist.contains(extension)) {
            throw new BizException("UPLOAD_TYPE_NOT_ALLOWED",
                    "不支持的文件类型：" + extension + "，允许 " + String.join("/", whitelist));
        }
        if (content == null || content.length == 0) {
            throw new BizException("UPLOAD_EMPTY_FILE", "上传内容为空");
        }
        if (IMAGE_EXTENSIONS.contains(extension) || DOCUMENT_EXTENSIONS.contains(extension)) {
            assertMagic(extension, content);
        }
        if ("csv".equals(extension)) {
            assertLooksLikeText(content);
        }
        return extension;
    }

    public static String extensionOf(String fileName) {
        if (fileName == null) {
            return "";
        }
        // 只取最后一段，避免 a.png/../b.exe 这类伪造
        String name = fileName.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return "";
        }
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static void assertMagic(String extension, byte[] content) {
        byte[][] candidates = MAGIC.get(extension);
        if (candidates == null) {
            return;
        }
        for (byte[] prefix : candidates) {
            if (content.length >= prefix.length && startsWith(content, prefix)) {
                return;
            }
        }
        throw new BizException("UPLOAD_CONTENT_MISMATCH",
                "文件内容与扩展名 ." + extension + " 不符，请上传真实文件");
    }

    private static boolean startsWith(byte[] content, byte[] prefix) {
        for (int i = 0; i < prefix.length; i++) {
            if (content[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * CSV 无魔数，退化为「必须是合法文本」：不允许出现 NUL，且控制字符占比不能过高。
     *
     * <p>注意必须按无符号字节比较：UTF-8 中文的字节在 {@code byte} 里是负数，
     * 直接用 {@code b < 0x09} 会把正常中文文件判成二进制。</p>
     */
    private static void assertLooksLikeText(byte[] content) {
        int control = 0;
        for (byte b : content) {
            int value = b & 0xFF;
            if (value == 0) {
                throw new BizException("UPLOAD_CONTENT_MISMATCH", "CSV 文件包含二进制内容");
            }
            // 允许 \t(0x09) \n(0x0A) \r(0x0D)，其余 C0 控制字符计入
            boolean printableControl = value == 0x09 || value == 0x0A || value == 0x0D;
            if (value < 0x20 && !printableControl) {
                control++;
            }
        }
        if (control > content.length / 10) {
            throw new BizException("UPLOAD_CONTENT_MISMATCH", "CSV 文件不是有效文本");
        }
    }
}
