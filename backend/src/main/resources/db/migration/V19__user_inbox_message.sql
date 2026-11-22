-- PRD 4.10 站内消息：考生收件箱（per-user 行，群发即逐用户落一行）
CREATE TABLE IF NOT EXISTS user_inbox_message (
    id         BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '站内信ID，主键',
    tenant_id  BIGINT        NOT NULL COMMENT '租户ID，关联 sys_tenant.id',
    user_id    BIGINT        NOT NULL COMMENT '接收用户ID，关联 sys_user.id',
    title      VARCHAR(150)  NOT NULL COMMENT '标题',
    content    VARCHAR(2000) NOT NULL COMMENT '正文',
    read_flag  TINYINT       NOT NULL DEFAULT 0 COMMENT '已读标记：0-未读，1-已读',
    created_at DATETIME      NOT NULL COMMENT '发送时间',
    updated_at DATETIME      NOT NULL COMMENT '最后更新时间',
    deleted    TINYINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除标记：0-正常，1-已删除'
);
CREATE INDEX idx_user_inbox_user ON user_inbox_message (tenant_id, user_id, read_flag);
