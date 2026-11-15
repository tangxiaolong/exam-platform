package com.exam.platform.modules.system;

import com.exam.platform.modules.system.entity.SysAuditLog;
import com.exam.platform.modules.system.mapper.SysAuditLogMapper;
import com.exam.platform.modules.system.service.AuditArchiveService;
import com.exam.platform.support.AbstractApiTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Timestamp;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 审计日志的保留期与归档。
 *
 * <p>验的是三件不能出错的事：超期记录确实被写进文件后才从库里消失、未超期的记录一条都不能少、
 * 跨租户的旧记录也要被处理（否则归档只对平台租户生效，等于没做）。</p>
 *
 * <p>本类用独立的低保留期上下文（30 天、批大小 3），与 {@code ApiTestSupport} 的高配额上下文互不影响。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.audit.archive.enabled=true",
        "app.audit.archive.retention-days=30",
        "app.audit.archive.batch-size=3",
        "app.audit.archive.dir=target/test-audit-archive"
})
@ActiveProfiles("h2")
@DisplayName("审计日志：保留期与归档")
class AuditArchiveTest extends AbstractApiTestSupport {

    private static final LocalDateTime OLD = LocalDateTime.now().minusDays(40);
    private static final LocalDateTime RECENT = LocalDateTime.now().minusHours(2);

    @Autowired
    private AuditArchiveService auditArchiveService;

    @Autowired
    private SysAuditLogMapper auditLogMapper;

    @Test
    @DisplayName("超期记录归档进文件后从库中删除，未超期的原样保留")
    void shouldArchiveExpiredAndKeepRecent() throws Exception {
        // 5 条超期（其中 1 条属于另一个租户）+ 2 条近期；批大小 3 意味着至少要跑两轮
        for (int i = 1; i <= 4; i++) {
            insertAudit(1L, "OLD_ACTION_" + i, OLD);
        }
        insertAudit(99L, "OLD_ACTION_CROSS_TENANT", OLD);
        insertAudit(1L, "RECENT_ACTION_1", RECENT);
        insertAudit(1L, "RECENT_ACTION_2", RECENT);

        AuditArchiveService.ArchiveResult result = auditArchiveService.archive();

        assertThat(result.skipped()).isFalse();
        assertThat(result.archived()).as("五条超期记录都应被归档").isEqualTo(5L);
        assertThat(result.file()).as("有记录被归档时必须留下文件路径").isNotNull();

        assertThat(countAudit()).as("近期记录一条都不能少").isEqualTo(2L);
        assertThat(actionsInDb()).doesNotContain("OLD_ACTION_1", "OLD_ACTION_CROSS_TENANT")
                .contains("RECENT_ACTION_1", "RECENT_ACTION_2");

        Path file = Paths.get(result.file());
        assertThat(Files.exists(file)).as("归档文件必须真实存在，否则删库就是净损失").isTrue();
        String csv = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        assertThat(csv).as("BOM + 表头，便于直接交付审计").startsWith("\uFEFF记录ID,租户ID");
        assertThat(csv).contains("OLD_ACTION_1");
        assertThat(csv)
                .as("跨租户的旧记录也要归档：租户插件默认会把它挡在范围外")
                .contains("OLD_ACTION_CROSS_TENANT");
        assertThat(csv).as("近期记录不该出现在归档文件里").doesNotContain("RECENT_ACTION_1");
    }

    @Test
    @DisplayName("没有超期记录时不产生文件、不删任何数据")
    void shouldNotCreateFileWhenNothingExpired() {
        insertAudit(1L, "RECENT_ONLY", RECENT);

        AuditArchiveService.ArchiveResult result = auditArchiveService.archive();

        assertThat(result.archived()).isZero();
        assertThat(result.file()).isNull();
        assertThat(countAudit()).isEqualTo(1L);
    }

    @Test
    @DisplayName("开关关闭或保留期为 0 时整体跳过（默认不会删任何审计记录）")
    void shouldSkipWhenDisabledOrKeepForever() throws Exception {
        insertAudit(1L, "SHOULD_SURVIVE", OLD);

        AuditArchiveService disabled = new AuditArchiveService(auditLogMapper, false, 30, 500,
                "target/test-audit-archive");
        AuditArchiveService keepForever = new AuditArchiveService(auditLogMapper, true, 0, 500,
                "target/test-audit-archive");

        assertThat(disabled.archive().skipped()).isTrue();
        assertThat(keepForever.archive().skipped()).isTrue();
        assertThat(countAudit()).as("跳过模式下一行都不能删").isEqualTo(1L);
    }

    // ---------------------------------------------------------------- 辅助

    private void insertAudit(long tenantId, String action, LocalDateTime createdAt) {
        jdbcTemplate.update("""
                INSERT INTO sys_audit_log (tenant_id, user_id, username, action, target, detail, ip,
                                           created_at, updated_at, deleted)
                VALUES (?, 1, 'archive-tester', ?, 'target-x', 'detail-x', '127.0.0.1', ?, ?, 0)
                """, tenantId, action, Timestamp.valueOf(createdAt), Timestamp.valueOf(createdAt));
    }

    private long countAudit() {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM sys_audit_log", Long.class);
        return count == null ? 0 : count;
    }

    private java.util.List<String> actionsInDb() {
        return jdbcTemplate.queryForList("SELECT action FROM sys_audit_log", String.class);
    }
}
