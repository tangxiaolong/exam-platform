-- ============================================================================
-- V5__org_quota：机构额度池
--
-- 背景：机构自助发放（/v1/org/**）上线后，机构管理员可以无限量地给自己人发权益——
-- 系统里没有「这家机构到底买了多少」的概念，发放不限额，平台的商业边界全靠人盯。
--
-- 设计取舍：
--   1) 额度按 (机构, 权益类型, 资源) 三维记账，而不是一张「机构总余额」：
--      不同类型/不同课程的额度不可互换，混在一个余额里会出现「拿 A 课的额度发 B 课」；
--   2) resource_id 用 0 表示「该类型通用额度」，而不是 NULL——MySQL 的 UNIQUE 索引对 NULL
--      是「互不相等」，允许重复插入，唯一键就形同虚设；用 0 才能让唯一约束真正生效；
--   3) 只记 total/used 两个累计值，余额 = total - used：不引入「充值流水表」，
--      因为每笔充值都有审计日志（ORG_QUOTA_RECHARGE），流水可从审计回溯；
--   4) 扣减用单条 UPDATE 的原子条件（total - used >= amount）而不是「先查后改」：
--      并发发放时先查后改会双花。
-- ============================================================================

CREATE TABLE IF NOT EXISTS sys_org_quota (
  id               BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '额度账户ID，主键',
  tenant_id        BIGINT       NOT NULL COMMENT '租户ID，关联 sys_tenant.id',
  org_id           BIGINT       NOT NULL COMMENT '机构ID，关联 sys_org.id',
  entitlement_type VARCHAR(32)  NOT NULL COMMENT '权益类型：MEMBER / QUESTION_BANK / COURSE / EXAM',
  resource_id      BIGINT       NOT NULL DEFAULT 0 COMMENT '资源ID；0 表示该类型通用额度（不用 NULL 是为了让唯一键生效）',
  total_amount     BIGINT       NOT NULL DEFAULT 0 COMMENT '累计充值额度',
  used_amount      BIGINT       NOT NULL DEFAULT 0 COMMENT '累计已消耗额度',
  remark           VARCHAR(200) NULL COMMENT '最近一次充值的备注（如采购单号）',
  created_at       DATETIME     NOT NULL COMMENT '创建时间',
  updated_at       DATETIME     NOT NULL COMMENT '最后更新时间',
  deleted          TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除标记：0-正常，1-已删除'
);

CREATE UNIQUE INDEX uk_sys_org_quota ON sys_org_quota (tenant_id, org_id, entitlement_type, resource_id);

CREATE INDEX idx_sys_org_quota_org ON sys_org_quota (tenant_id, org_id);
