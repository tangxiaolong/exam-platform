-- ============================================================================
-- V8__org_quota_recharge：额度充值流水
--
-- 背景：V5/V7 的额度账户只记 total/used 两个累计值，每笔充值除了审计日志（ORG_QUOTA_RECHARGE）
-- 之外没有任何结构化记录。运营拿合同或采购单来对账时，「这笔采购充了多少、充给谁、
-- 期到哪天、谁操作的」只能去翻审计正文，既查不快也算不准（审计的 detail 是拼出来的字符串）。
--
-- 设计取舍：
--   1) 流水是「账面」而非「余额」的唯一来源：账户上的 total_amount 仍由累加 SQL 维护，
--      流水只用于核对与追溯；两者之和必须相等，测试直接断言这一点；
--   2) operator_id 记录操作人，但允许为空——手工补录或脚本初始化的历史数据没有操作人；
--   3) 不把流水做成「发放流水」：发放有 entitlement_ledger 与批次号（ORG-<batchNo>-<userId>），
--      两者都在各自模块里有完整记录，这里只补齐「充值」这一侧。
-- ============================================================================

CREATE TABLE IF NOT EXISTS sys_org_quota_recharge (
  id               BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '充值流水ID，主键',
  tenant_id        BIGINT       NOT NULL COMMENT '租户ID，关联 sys_tenant.id',
  org_id           BIGINT       NOT NULL COMMENT '机构ID，关联 sys_org.id',
  quota_id         BIGINT       NOT NULL COMMENT '对应的额度账户ID，关联 sys_org_quota.id',
  entitlement_type VARCHAR(32)  NOT NULL COMMENT '权益类型：MEMBER / QUESTION_BANK / COURSE / EXAM',
  resource_id      BIGINT       NOT NULL DEFAULT 0 COMMENT '资源ID；0 表示该类型通用额度',
  amount           BIGINT       NOT NULL COMMENT '本次充值份数',
  expire_at        DATETIME     NULL COMMENT '本次充值指定的有效期；NULL 表示未指定（不改变账户原有期限）',
  remark           VARCHAR(200) NULL COMMENT '备注（采购单号、合同号）',
  operator_id      BIGINT       NULL COMMENT '操作人ID，关联 sys_user.id；脚本补录时为空',
  created_at       DATETIME     NOT NULL COMMENT '充值时间',
  updated_at       DATETIME     NOT NULL COMMENT '最后更新时间',
  deleted          TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除标记：0-正常，1-已删除'
);

CREATE INDEX idx_sys_org_quota_recharge_org ON sys_org_quota_recharge (tenant_id, org_id, id);
