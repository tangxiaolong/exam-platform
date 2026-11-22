-- ============================================================================
-- V14__notice_config：公告与系统配置
--
-- 公告的建模取舍：
--   1) status 与 publish_at 分离：状态是「运营意图」，publish_at 是「生效时间」。
--      合并成一个字段就没法做「先排期、到点自动上线」；
--   2) 用 publish_at / expire_at 时间窗而不是定时任务改状态：
--      查询时按时间过滤，天然幂等，不会因为任务漏跑导致公告该下线没下线；
--   3) pinned 是布尔而不是排序号：运营只需要「置顶/不置顶」，
--      让运营维护一个排序数字，最终一定出现重复值和空洞；
--   4) 公告正文用 TEXT 存富文本片段，不做附件表——真需要富媒体时再拆。
--
-- 配置的建模取舍：
--   1) 键值对而不是一行多列：每加一个开关就改表结构，迁移会失控；
--   2) config_key 租户内唯一：不同租户可以有不同的开关策略；
--   3) 值统一按字符串存，由读取方按需转换——JSON 存值会让「默认值」和
--      「解析失败」混在一起，排查困难。
-- ============================================================================

CREATE TABLE IF NOT EXISTS cms_notice (
  id           BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '公告ID，主键',
  tenant_id    BIGINT       NOT NULL COMMENT '租户ID，关联 sys_tenant.id',
  title        VARCHAR(200) NOT NULL COMMENT '公告标题',
  content      TEXT         NULL COMMENT '公告正文（纯文本或简单富文本片段）',
  status       VARCHAR(16)  NOT NULL DEFAULT 'DRAFT' COMMENT '状态：DRAFT-草稿，PUBLISHED-已发布，ARCHIVED-已归档',
  pinned       TINYINT      NOT NULL DEFAULT 0 COMMENT '是否置顶：1-置顶，0-否',
  publish_at   DATETIME     NULL COMMENT '生效时间，为空表示发布即刻生效',
  expire_at    DATETIME     NULL COMMENT '失效时间，为空表示长期有效',
  publisher_id BIGINT       NULL COMMENT '发布人ID，关联 sys_user.id',
  created_at   DATETIME     NOT NULL COMMENT '创建时间',
  updated_at   DATETIME     NOT NULL COMMENT '最后更新时间',
  deleted      TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除标记：0-正常，1-已删除'
);
CREATE INDEX idx_cms_notice_query ON cms_notice (tenant_id, status, pinned, publish_at);

CREATE TABLE IF NOT EXISTS sys_config (
  id           BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '配置ID，主键',
  tenant_id    BIGINT       NOT NULL COMMENT '租户ID，关联 sys_tenant.id',
  config_key   VARCHAR(128) NOT NULL COMMENT '配置键，租户内唯一，如 notice.banner.enabled',
  config_value VARCHAR(2000) NULL COMMENT '配置值，统一按字符串存储',
  remark       VARCHAR(255) NULL COMMENT '配置说明，便于运营理解用途',
  created_at   DATETIME     NOT NULL COMMENT '创建时间',
  updated_at   DATETIME     NOT NULL COMMENT '最后更新时间',
  deleted      TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除标记：0-正常，1-已删除'
);
CREATE UNIQUE INDEX uk_sys_config_key ON sys_config (tenant_id, config_key);

-- 初始配置（幂等）：把「公告是否强制阅读」这类开关先落下来，避免前后端硬编码
INSERT INTO sys_config (tenant_id, config_key, config_value, remark, created_at, updated_at, deleted)
SELECT 1, 'notice.list.page-size', '20', '考生端公告列表默认每页条数', NOW(), NOW(), 0
WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE tenant_id = 1 AND config_key = 'notice.list.page-size');

INSERT INTO sys_config (tenant_id, config_key, config_value, remark, created_at, updated_at, deleted)
SELECT 1, 'notice.detail.require-read', 'false', '公告详情是否强制已读确认', NOW(), NOW(), 0
WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE tenant_id = 1 AND config_key = 'notice.detail.require-read');
