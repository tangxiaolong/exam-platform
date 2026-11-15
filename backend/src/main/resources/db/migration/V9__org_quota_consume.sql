-- ============================================================================
-- V9__org_quota_consume：额度发放流水
--
-- 背景：V5/V7/V8 之后，额度的「充值」有明细（sys_org_quota_recharge），
-- 但「消耗」只有账户上的一个 used_amount。这意味着没人能回答：
-- 「这 37 份到底发给了谁、哪一批、扣得对不对」——发放记录散在 entitlement_ledger 里，
-- 而 ledger 的 event_id（ORG-<batchNo>-<userId>）不含机构 ID，反查不出机构。
--
-- 设计取舍：
--   1) 一次发放一行（按批次），不是每人一行：发放本来就是以批次组织的，
--      每人明细在 entitlement_ledger 里（event_id 含 batchNo，可交叉追溯）；
--   2) 净额 = consumed_amount - refunded_amount：发放中被跳过的成员额度会退回，
--      只有净额才应该等于账户的 used_amount；
--   3) batch_no 与 entitlement_ledger 的 event_id 前缀对齐，出问题时能从任一侧追到另一侧。
-- ============================================================================

CREATE TABLE IF NOT EXISTS sys_org_quota_consume (
  id               BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '发放流水ID，主键',
  tenant_id        BIGINT      NOT NULL COMMENT '租户ID，关联 sys_tenant.id',
  org_id           BIGINT      NOT NULL COMMENT '机构ID，关联 sys_org.id',
  quota_id         BIGINT      NOT NULL COMMENT '扣减的额度账户ID，关联 sys_org_quota.id',
  batch_no         VARCHAR(64) NOT NULL COMMENT '发放批次号，与 entitlement_ledger.event_id 的前缀对应',
  entitlement_type VARCHAR(32) NOT NULL COMMENT '权益类型：MEMBER / QUESTION_BANK / COURSE / EXAM',
  resource_id      BIGINT      NOT NULL DEFAULT 0 COMMENT '资源ID；0 表示该类型通用额度',
  target_count     INT         NOT NULL COMMENT '本次目标成员数',
  amount_per_user  BIGINT      NOT NULL COMMENT '每人份数',
  consumed_amount  BIGINT      NOT NULL COMMENT '扣减总额 = target_count × amount_per_user',
  granted_count    INT         NOT NULL COMMENT '实际发放成功人数',
  skipped_count    INT         NOT NULL DEFAULT 0 COMMENT '被跳过人数',
  refunded_amount  BIGINT      NOT NULL DEFAULT 0 COMMENT '跳过成员退回的额度',
  operator_id      BIGINT      NULL COMMENT '操作人ID，关联 sys_user.id；无登录态时为空',
  created_at       DATETIME    NOT NULL COMMENT '发放时间',
  updated_at       DATETIME    NOT NULL COMMENT '最后更新时间',
  deleted          TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除标记：0-正常，1-已删除'
);

CREATE INDEX idx_sys_org_quota_consume_org ON sys_org_quota_consume (tenant_id, org_id, id);
CREATE INDEX idx_sys_org_quota_consume_quota ON sys_org_quota_consume (tenant_id, quota_id, id);
