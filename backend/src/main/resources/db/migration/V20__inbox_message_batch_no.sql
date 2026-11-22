-- PRD 4.10 站内消息批次聚合：一次群发落一批次号，历史数据（无批次号）按单行成组
ALTER TABLE user_inbox_message ADD COLUMN batch_no VARCHAR(64) NULL COMMENT '群发批次号';
CREATE INDEX idx_user_inbox_batch ON user_inbox_message (tenant_id, batch_no);
