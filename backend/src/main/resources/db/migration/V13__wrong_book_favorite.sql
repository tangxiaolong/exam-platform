-- ============================================================================
-- V13__wrong_book_favorite：错题本与收藏
--
-- 背景：判分结果只落在 exam_result.detail_json 里，考生想「把错题再过一遍」
-- 只能翻历史成绩，且跨场次无法汇总——错题本是把「一次性成绩」变成「可复习资产」。
--
-- 设计取舍：
--   1) 错题按（用户, 题目）唯一，同一题再次答错只累加 wrong_count 并清空 mastered，
--      而不是插一条新记录：错题本要回答「这道题我错了多少次」，不是流水账；
--   2) 不冗余题干/选项：题目可能被编辑，错题本展示应始终是最新版本；
--      真需要快照时应该用 exam_result 里的历史明细，而不是错题表；
--   3) mastered 是显式状态而不是「答对自动移除」：考生可能想留着复习，
--      自动删除等于替他做决定；
--   4) 收藏与错题本独立：收藏是主动行为（好题、常考题），错题是被动产生，语义不同。
-- ============================================================================

CREATE TABLE IF NOT EXISTS exam_wrong_question (
  id               BIGINT   NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '错题记录ID，主键',
  tenant_id        BIGINT   NOT NULL COMMENT '租户ID，关联 sys_tenant.id',
  user_id          BIGINT   NOT NULL COMMENT '考生ID，关联 sys_user.id',
  question_id      BIGINT   NOT NULL COMMENT '题目ID，关联 qb_question.id',
  wrong_count      INT      NOT NULL DEFAULT 1 COMMENT '累计答错次数',
  last_attempt_id  BIGINT   NULL COMMENT '最近一次答错的答卷ID，便于回看当时的作答',
  last_wrong_at    DATETIME NOT NULL COMMENT '最近一次答错时间',
  mastered         TINYINT  NOT NULL DEFAULT 0 COMMENT '是否已掌握：0-未掌握，1-已掌握；再次答错会重置为 0',
  created_at       DATETIME NOT NULL COMMENT '首次答错时间',
  updated_at       DATETIME NOT NULL COMMENT '最后更新时间',
  deleted          TINYINT  NOT NULL DEFAULT 0 COMMENT '逻辑删除标记：0-正常，1-已删除'
);
CREATE UNIQUE INDEX uk_exam_wrong_question ON exam_wrong_question (tenant_id, user_id, question_id);
CREATE INDEX idx_exam_wrong_question_list ON exam_wrong_question (tenant_id, user_id, mastered, last_wrong_at);

CREATE TABLE IF NOT EXISTS user_favorite (
  id          BIGINT   NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '收藏记录ID，主键',
  tenant_id   BIGINT   NOT NULL COMMENT '租户ID，关联 sys_tenant.id',
  user_id     BIGINT   NOT NULL COMMENT '用户ID，关联 sys_user.id',
  question_id BIGINT   NOT NULL COMMENT '题目ID，关联 qb_question.id',
  remark      VARCHAR(255) NULL COMMENT '收藏备注，如「常考题型」',
  created_at  DATETIME NOT NULL COMMENT '收藏时间',
  updated_at  DATETIME NOT NULL COMMENT '最后更新时间',
  deleted     TINYINT  NOT NULL DEFAULT 0 COMMENT '逻辑删除标记：0-正常，1-已删除'
);
CREATE UNIQUE INDEX uk_user_favorite ON user_favorite (tenant_id, user_id, question_id);
CREATE INDEX idx_user_favorite_list ON user_favorite (tenant_id, user_id, id);
