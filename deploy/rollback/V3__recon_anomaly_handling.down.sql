-- ============================================================================
-- V3__recon_anomaly_handling 的逆操作（仅在「必须撤回结构变更」时使用）
--
-- 详见 doc/05_数据库迁移演练与回滚预案.md 的场景 C。日常发布不要执行：
-- 正常发布出问题时，正确做法是把应用回滚到上一版本，脚本保留（旧版本忽略新列即可）。
--
-- 执行前置：
--   1) 停写（关停对账定时任务，确认无进行中的事务）
--   2) 备份并导出处置记录：
--      mysqldump --single-transaction exam_platform trade_recon_anomaly > trade_recon_anomaly.bak.sql
--      SELECT * FROM trade_recon_anomaly WHERE handle_status <> 'PENDING';
--   3) 确认备份文件可读、行数一致
--
-- 警告：DROP COLUMN 会永久丢失所有处置痕迹（谁把哪条异常标记为已处理、当时写了什么理由）。
-- ============================================================================

DROP INDEX idx_trade_recon_anomaly_handle ON trade_recon_anomaly;

ALTER TABLE trade_recon_anomaly
  DROP COLUMN handled_at,
  DROP COLUMN handled_by,
  DROP COLUMN handle_remark,
  DROP COLUMN handle_status;
