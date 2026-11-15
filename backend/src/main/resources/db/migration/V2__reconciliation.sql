-- ============================================================================
-- V2__reconciliation：对账报告与异常明细
--
-- 背景：支付链路是「回调 → 落流水 → 验签 → 置订单已支付 → 发权益」的多步写，
-- 事务只覆盖单次回调，任何一步失败/漏跑都会留下「钱到了但权益没到」这类
-- 静默不一致。对账任务把这类问题从「用户投诉才发现」变成「每天主动检出」。
--
-- 设计取舍：
--   1) 报告与异常分表：报告记录一次检查的范围与结论，异常逐条留痕，便于按类型统计；
--   2) 异常不自动修复：涉及钱与权益，自动补偿风险高于人工确认，
--      首期只做「检出 + 可查 + 可导出」，修复动作仍由运营执行；
--   3) 保留 tenant_id 走租户插件，跨租户数据不会混进同一份报告。
-- ============================================================================

CREATE TABLE IF NOT EXISTS trade_recon_report (
  id              BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '对账报告ID，主键',
  tenant_id       BIGINT       NOT NULL COMMENT '租户ID，关联 sys_tenant.id',
  report_no       VARCHAR(64)  NOT NULL COMMENT '报告编号，租户内唯一',
  scope           VARCHAR(16)  NOT NULL DEFAULT 'MANUAL' COMMENT '触发方式：MANUAL-人工触发，SCHEDULED-定时任务',
  checked_orders  INT          NOT NULL DEFAULT 0 COMMENT '本次检查的订单数',
  checked_payments INT         NOT NULL DEFAULT 0 COMMENT '本次检查的支付单数',
  checked_accounts INT         NOT NULL DEFAULT 0 COMMENT '本次检查的权益账户数',
  anomaly_count   INT          NOT NULL DEFAULT 0 COMMENT '检出异常条数，0 表示通过',
  status          VARCHAR(16)  NOT NULL DEFAULT 'PASSED' COMMENT '结论：PASSED-一致，ANOMALY-存在异常，FAILED-检查本身执行失败',
  error_msg       VARCHAR(500) NULL COMMENT '执行失败原因',
  started_at      DATETIME     NOT NULL COMMENT '开始时间',
  finished_at     DATETIME     NULL COMMENT '结束时间',
  created_at      DATETIME     NOT NULL COMMENT '创建时间',
  updated_at      DATETIME     NOT NULL COMMENT '最后更新时间',
  deleted         TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除标记：0-正常，1-已删除',
  CONSTRAINT uk_trade_recon_report_no UNIQUE (tenant_id, report_no)
);

CREATE TABLE IF NOT EXISTS trade_recon_anomaly (
  id             BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '异常明细ID，主键',
  tenant_id      BIGINT       NOT NULL COMMENT '租户ID，关联 sys_tenant.id',
  report_id      BIGINT       NOT NULL COMMENT '所属对账报告ID，关联 trade_recon_report.id',
  anomaly_type   VARCHAR(48)  NOT NULL COMMENT '异常类型：ORDER_PAID_WITHOUT_ENTITLEMENT / ORDER_COMPLETED_WITHOUT_ENTITLEMENT / PAYMENT_PAID_ORDER_NOT_PAID / PAYMENT_AMOUNT_MISMATCH / ACCOUNT_LEDGER_DRIFT',
  biz_type       VARCHAR(16)  NOT NULL COMMENT '业务对象类型：ORDER / PAYMENT / ACCOUNT',
  biz_id         BIGINT       NOT NULL COMMENT '业务对象主键（订单ID / 支付单ID / 权益账户ID）',
  biz_no         VARCHAR(64)  NULL COMMENT '业务编号（订单号 / 支付单号），便于人工定位',
  expected_value VARCHAR(128) NULL COMMENT '对账期望值',
  actual_value   VARCHAR(128) NULL COMMENT '实际值',
  detail         VARCHAR(500) NULL COMMENT '补充说明',
  created_at     DATETIME     NOT NULL COMMENT '创建时间',
  updated_at     DATETIME     NOT NULL COMMENT '最后更新时间',
  deleted        TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除标记：0-正常，1-已删除'
);

CREATE INDEX idx_trade_recon_anomaly_report ON trade_recon_anomaly (tenant_id, report_id, anomaly_type);
