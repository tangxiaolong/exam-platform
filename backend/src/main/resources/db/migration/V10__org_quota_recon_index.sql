-- ============================================================================
-- V10__org_quota_recon_index
--
-- 背景：V9 之后对账任务每天要扫两张大表——
--   * entitlement_ledger（所有权益流水，含订单、核销、回收，ORG-% 只占其中一部分）
--   * sys_org_quota_consume（额度发放流水）
-- 原来的索引都以 tenant_id 打头（供业务查询按租户过滤），但对账是跨租户任务，
-- SQL 里没有 tenant_id 条件，用不上这些复合索引，等于全表扫。
--
-- 设计取舍：
--   1) 对账按 created_at 时间窗（默认最近 7 天）分批核查，所以给 created_at 单独建索引：
--      有它才能让「只扫最近几天」真的只扫最近几天；
--   2) 把 event_id 放在 created_at 之后组成复合索引，让 entitlement_ledger 上
--      「时间窗 + ORG- 前缀」的条件尽量在索引内完成，少回表；
--   3) 不删除原有索引：业务查询仍按 tenant_id 过滤，两者用途不同。
-- ============================================================================

CREATE INDEX idx_entitlement_ledger_recon ON entitlement_ledger (created_at, event_id);

CREATE INDEX idx_sys_org_quota_consume_created ON sys_org_quota_consume (created_at, id);
