package com.exam.platform.modules.system.service;

import com.exam.platform.modules.system.entity.SysAuditLog;
import com.exam.platform.modules.system.mapper.SysAuditLogMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 审计日志的保留期与归档。
 *
 * <p>解决的是「表只增不减」：管理端每个写操作都落一条审计，一个活跃租户一年几十万行。
 * 审计记录既不能随便删（合规要求、审计本身的意义），也不能无限留着（查询越来越慢、
 * 备份越来越贵）。所以做成「先归档成可读文件，再物理删除」——归档文件是删除的前提。</p>
 *
 * <p>四个刻意的边界：</p>
 * <ol>
 *   <li><b>默认关闭</b>：{@code app.audit.archive.enabled} 默认 false。删审计必须是人明确做出的决定，
 *       而不是某个新版本悄悄带上的默认行为；</li>
 *   <li><b>先落盘再删库</b>：每批记录写文件成功后才删除。反过来一旦写失败，
 *       数据就从「有」变成「没有」，且没有任何补救手段；</li>
 *   <li><b>物理删除而非逻辑删除</b>：逻辑删除留下 {@code deleted=1} 的占位行，表照样膨胀，
 *       而审计的语义是「这条记录真实存在过」；</li>
 *   <li><b>跨租户</b>：归档是平台级运维动作，不能只看平台租户（详见 mapper 的 {@code @InterceptorIgnore}）。</li>
 * </ol>
 */
@Slf4j
@Service
public class AuditArchiveService {

    private static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final String HEADER = "记录ID,租户ID,操作人ID,操作人账号,动作,对象,详情,来源IP,操作时间\r\n";

    private final SysAuditLogMapper auditLogMapper;
    private final boolean enabled;
    private final int retentionDays;
    private final int batchSize;
    private final String dir;

    public AuditArchiveService(SysAuditLogMapper auditLogMapper,
                               @Value("${app.audit.archive.enabled:false}") boolean enabled,
                               @Value("${app.audit.archive.retention-days:180}") int retentionDays,
                               @Value("${app.audit.archive.batch-size:500}") int batchSize,
                               @Value("${app.audit.archive.dir:./data/audit-archive}") String dir) {
        this.auditLogMapper = auditLogMapper;
        this.enabled = enabled;
        this.retentionDays = retentionDays;
        this.batchSize = Math.max(1, batchSize);
        this.dir = dir;
    }

    /** 一轮归档：把超过保留期的记录全部写入文件并从库中删除。 */
    public ArchiveResult archive() {
        if (!enabled) {
            return new ArchiveResult(0, null, true, "归档未开启（app.audit.archive.enabled=false）");
        }
        if (retentionDays <= 0) {
            return new ArchiveResult(0, null, true, "保留期为 0，按「永久保留」处理");
        }

        LocalDateTime before = LocalDate.now().minusDays(retentionDays).atStartOfDay();
        Path file = Paths.get(dir).resolve("audit-archive-" + LocalDateTime.now().format(FILE_STAMP) + ".csv");
        long archived = 0;
        boolean headerWritten = false;

        while (true) {
            List<SysAuditLog> batch = auditLogMapper.selectExpiredCrossTenant(before, batchSize);
            if (batch.isEmpty()) {
                break;
            }
            // 写失败会抛异常，这批记录仍在库里，下一轮还能归档
            appendCsv(file, batch, !headerWritten);
            headerWritten = true;

            int deleted = auditLogMapper.deleteByIdsCrossTenant(
                    batch.stream().map(SysAuditLog::getId).toList());
            if (deleted <= 0) {
                throw new IllegalStateException("归档删除未生效（0 行），中止以避免无限循环");
            }
            archived += deleted;
            if (batch.size() < batchSize) {
                break;
            }
        }

        if (archived == 0) {
            return new ArchiveResult(0, null, false, "没有超过保留期的记录");
        }
        log.info("审计归档完成 archived={} file={}", archived, file);
        return new ArchiveResult(archived, file.toString(), false, null);
    }

    private void appendCsv(Path file, List<SysAuditLog> rows, boolean writeHeader) {
        StringBuilder sb = new StringBuilder();
        if (writeHeader) {
            sb.append('\uFEFF'); // BOM：Excel 直接打开中文不乱码
            sb.append(HEADER);
        }
        for (SysAuditLog row : rows) {
            sb.append(escape(str(row.getId()))).append(',')
                    .append(escape(str(row.getTenantId()))).append(',')
                    .append(escape(str(row.getUserId()))).append(',')
                    .append(escape(row.getUsername())).append(',')
                    .append(escape(row.getAction())).append(',')
                    .append(escape(row.getTarget())).append(',')
                    .append(escape(row.getDetail())).append(',')
                    .append(escape(row.getIp())).append(',')
                    .append(escape(str(row.getCreatedAt())).replace('T', ' ')).append("\r\n");
        }
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.write(file, sb.toString().getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException("写审计归档文件失败：" + file, e);
        }
    }

    private String escape(String value) {
        if (value == null) {
            return "";
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    private String str(Object value) {
        return value == null ? "" : value.toString();
    }

    /**
     * 归档结果。
     *
     * @param archived 本轮从库中删除（且已写入文件）的记录数
     * @param file     归档文件名；没有记录可归档时为 null
     * @param skipped  是否因配置原因整体跳过
     * @param reason   跳过或「无可归档」的说明
     */
    public record ArchiveResult(long archived, String file, boolean skipped, String reason) {
    }
}
