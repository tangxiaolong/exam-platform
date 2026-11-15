-- ============================================================================
-- V6__msg_template：消息模板可配置化
--
-- 背景：模板文案原先只存在于代码常量里（MessageTemplates 的三个编码），
-- 内容由微信模板侧决定、本地只有一串 JSON payload。于是「把通知文案改得客气一点」
-- 这种纯运营动作也要发版，且本地联调时看不到任何可读文案。
--
-- 设计取舍：
--   1) 模板按租户隔离：同一平台下不同机构/学校可以有各自的措辞；
--   2) 唯一键 (tenant_id, template_code)：一个编码一个模板，改文案是 UPDATE 而不是再插一条，
--      避免「同编码两个模板，到底用哪条」；
--   3) 库里没有某编码时不报错，回退到代码里的默认文案：升级后新增编码、
--      或新租户还没配模板，消息都必须照常发出去；
--   4) enabled=0 表示「先不配文案」，同样回退默认值——停用不等于丢消息。
-- ============================================================================

CREATE TABLE IF NOT EXISTS msg_template (
  id            BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '模板ID，主键',
  tenant_id     BIGINT       NOT NULL COMMENT '租户ID，关联 sys_tenant.id',
  template_code VARCHAR(64)  NOT NULL COMMENT '模板编码，如 PAYMENT_SUCCESS',
  title         VARCHAR(120) NOT NULL COMMENT '消息标题（可含 {占位符}）',
  content       VARCHAR(500) NOT NULL COMMENT '消息正文（可含 {占位符}，占位符取自消息 payload 的键）',
  enabled       TINYINT      NOT NULL DEFAULT 1 COMMENT '是否启用：0-停用（回退默认文案），1-启用',
  created_at    DATETIME     NOT NULL COMMENT '创建时间',
  updated_at    DATETIME     NOT NULL COMMENT '最后更新时间',
  deleted       TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除标记：0-正常，1-已删除'
);

CREATE UNIQUE INDEX uk_msg_template ON msg_template (tenant_id, template_code);
