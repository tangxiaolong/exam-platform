-- ============================================================================
-- V3__recon_anomaly_handling：对账异常的处置闭环
--
-- 背景：V2 只做到「检出 + 留痕」，异常落在库里没人认领。运营看得到列表，
-- 但「谁看过、处理了没有、为什么不修」全在群聊里，第二天再跑一次对账，
-- 同一批异常又原样出现——分不清是「新问题」还是「上次那批还没人管」。
--
-- 设计取舍：
--   1) 处置状态放在异常明细上而不是单独建处置表：一次检出对应一次处置，
--      同一 biz_id 在不同报告里是不同记录，各自处置，天然按报告轮次闭环；
--   2) 状态单向流转（PENDING → HANDLED / IGNORED）：不做「重新打开」，
--      因为重开会让「这条到底谁负责」变成二次扯皮，确需重开就下轮对账重新检出；
--   3) IGNORED 与 HANDLED 区分开：IGNORED 表示「账实相符，是对账规则误判」，
--      长期统计误报率要靠它，否则优化规则时没有依据；
--   4) 处置人只在有登录态时写入，定时任务与手工脚本留空。
-- ============================================================================

ALTER TABLE trade_recon_anomaly
  ADD COLUMN handle_status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT '处置状态：PENDING-待处理，HANDLED-已处理，IGNORED-已忽略（确认误报）';

ALTER TABLE trade_recon_anomaly
  ADD COLUMN handle_remark VARCHAR(200) NULL COMMENT '处置说明，写明修复方式或误报原因';

ALTER TABLE trade_recon_anomaly
  ADD COLUMN handled_by BIGINT NULL COMMENT '处置人ID，关联 sys_user.id；定时任务处置留空';

ALTER TABLE trade_recon_anomaly
  ADD COLUMN handled_at DATETIME NULL COMMENT '处置时间';

-- 运营最常见的查询是「最近一轮报告里还有哪些没处置」，按状态过滤要能走到索引
CREATE INDEX idx_trade_recon_anomaly_handle ON trade_recon_anomaly (tenant_id, handle_status, report_id);
