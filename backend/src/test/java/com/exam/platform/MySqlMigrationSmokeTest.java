package com.exam.platform;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MySQL 8 迁移演练：把 V1..Vn 在一台真实 MySQL 上跑一遍。
 *
 * <p>为什么必须单独验证：日常测试跑的是 H2 的 MySQL 兼容模式，它「兼容」的只是语法形状。
 * 真正会在生产咬人的差异都在兼容模式里被抹掉了——索引长度上限、UTF8 字符集与排序规则、
 * 时间类型精度、以及 {@code ALTER TABLE ... ADD COLUMN} 对已有数据行的默认值回填。
 * 迁移脚本一旦在生产首次执行失败，应用连启动都过不去（Flyway 失败即中断启动）。</p>
 *
 * <p>默认跳过：只有显式传入 {@code -Dmysql.test.url} 才会执行，因此不影响常规 {@code mvn test}。
 * CI 用 MySQL service 容器传入连接信息，本地可用 {@code tools/check-mysql-migration.ps1} 起容器演练。</p>
 */
@DisplayName("MySQL 8 迁移演练")
class MySqlMigrationSmokeTest {

    @Test
    @DisplayName("全部迁移脚本在 MySQL 8 上可执行，重复迁移为无操作")
    void migrateShouldSucceedOnMySql8() throws Exception {
        String url = System.getProperty("mysql.test.url");
        Assumptions.assumeTrue(url != null && !url.isBlank(),
                "未提供 -Dmysql.test.url，跳过 MySQL 迁移演练");
        String user = System.getProperty("mysql.test.user", "root");
        String password = System.getProperty("mysql.test.password", "");

        Flyway flyway = Flyway.configure()
                .dataSource(url, user, password)
                .locations("classpath:db/migration")
                .load();

        // 期望值来自 Flyway 实际解析到的脚本数，不写死数字。
        // 写死 3 的代价曾经是：新增 V4..V10 后这个用例在 CI 上必然失败，
        // 而它平时被 assumed-skip 掉，所以谁都没发现——演练其实一直在测三年前的快照。
        int expectedScripts = flyway.info().all().length;
        assertThat(expectedScripts)
                .as("classpath:db/migration 下应能找到迁移脚本")
                .isPositive();

        MigrateResult first = flyway.migrate();
        assertThat(first.migrationsExecuted)
                .as("全部 %s 个脚本都应在 MySQL 8 上执行成功，失败说明有脚本在真实 MySQL 上跑不通",
                        expectedScripts)
                .isEqualTo(expectedScripts);

        // 重复执行是发布流水线里的常态（多实例同时启动、回滚后重跑），必须是无操作而不是报错
        MigrateResult second = flyway.migrate();
        assertThat(second.migrationsExecuted)
                .as("已迁移过的库再次 migrate 应执行 0 个脚本")
                .isZero();

        try (Connection connection = DriverManager.getConnection(url, user, password)) {
            assertThat(columnsOf(connection, "trade_recon_anomaly"))
                    .as("对账异常处置字段（V3）应存在：漏掉它运营点不开「标记已处理」")
                    .contains("handle_status", "handle_remark", "handled_by", "handled_at");

            assertThat(indexesOf(connection, "trade_recon_anomaly"))
                    .as("按处置状态过滤是运营最高频查询，索引必须在 MySQL 上真实建出来")
                    .contains("idx_trade_recon_anomaly_handle");

            // 额度池（V5..V10）：账户、充值流水、发放流水三张表 + 对账索引。
            // 这些脚本里有 DDL 与索引，正是 H2 兼容模式抹掉差异的地方
            assertThat(columnsOf(connection, "sys_org_quota"))
                    .as("机构额度账户（V5/V7）应存在，含续充后的有效期字段")
                    .contains("org_id", "entitlement_type", "resource_id", "total_amount",
                            "used_amount", "expire_at");

            assertThat(columnsOf(connection, "sys_org_quota_recharge"))
                    .as("充值流水（V8）应存在：对账要能按采购单号追")
                    .contains("quota_id", "amount", "expire_at", "remark", "operator_id");

            assertThat(columnsOf(connection, "sys_org_quota_consume"))
                    .as("发放流水（V9）应存在：净额 = 扣减 - 退款")
                    .contains("batch_no", "target_count", "amount_per_user", "consumed_amount",
                            "granted_count", "skipped_count", "refunded_amount");

            assertThat(indexesOf(connection, "entitlement_ledger"))
                    .as("对账按时间窗扫台账，索引必须在真实 MySQL 上建出来（V10）")
                    .contains("idx_entitlement_ledger_recon");

            assertThat(indexesOf(connection, "sys_org_quota_consume"))
                    .as("发放流水的对账时间窗索引（V10）")
                    .contains("idx_sys_org_quota_consume_created");

            assertThat(tablesOf(connection))
                    .as("核心业务表应在 MySQL 上建出来")
                    .contains("trade_order", "trade_payment", "entitlement_account", "msg_task",
                            "exam_session", "exam_attempt", "qb_question", "sys_user", "sys_audit_log",
                            "sys_org", "sys_org_member", "sys_org_quota", "sys_org_quota_recharge",
                            "sys_org_quota_consume", "msg_template");
        }
    }

    private List<String> columnsOf(Connection connection, String table) throws Exception {
        return query(connection, """
                SELECT column_name FROM information_schema.columns
                 WHERE table_schema = DATABASE() AND table_name = ?
                 ORDER BY ordinal_position
                """, table);
    }

    private List<String> indexesOf(Connection connection, String table) throws Exception {
        return query(connection, """
                SELECT DISTINCT index_name FROM information_schema.statistics
                 WHERE table_schema = DATABASE() AND table_name = ?
                """, table);
    }

    private List<String> tablesOf(Connection connection) throws Exception {
        return query(connection, """
                SELECT table_name FROM information_schema.tables
                 WHERE table_schema = DATABASE()
                """, null);
    }

    private List<String> query(Connection connection, String sql, String param) throws Exception {
        List<String> values = new ArrayList<>();
        try (var statement = connection.prepareStatement(sql)) {
            if (param != null) {
                statement.setString(1, param);
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    values.add(resultSet.getString(1));
                }
            }
        }
        return values;
    }
}
