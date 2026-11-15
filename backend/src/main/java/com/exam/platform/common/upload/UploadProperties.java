package com.exam.platform.common.upload;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 上传配置：类型白名单、大小上限、落盘目录。 */
@Component
@ConfigurationProperties(prefix = "app.upload")
public class UploadProperties {

    /** 落盘根目录，所有产物必须落在该目录内。 */
    private String dir = "./data/uploads";
    /** 单文件大小上限（字节）。 */
    private long maxSizeBytes = 5 * 1024 * 1024L;
    /** 扩展名白名单，逗号分隔。 */
    private String allowedExtensions = "jpg,jpeg,png,pdf,csv,xlsx";

    public String getDir() {
        return dir;
    }

    public void setDir(String dir) {
        this.dir = dir;
    }

    public long getMaxSizeBytes() {
        return maxSizeBytes;
    }

    public void setMaxSizeBytes(long maxSizeBytes) {
        this.maxSizeBytes = maxSizeBytes;
    }

    public String getAllowedExtensions() {
        return allowedExtensions;
    }

    public void setAllowedExtensions(String allowedExtensions) {
        this.allowedExtensions = allowedExtensions;
    }
}
