-- ============================================================================
-- V15__paper_rule_knowledge_point：组卷的知识点范围
--
-- 现状：抽题已经支持「题型 + 难度」过滤（exam_paper_rule.difficulty），
-- 但题目与知识点之间没有任何关联列——「按知识点抽题」在数据层就无从下手。
--
-- 设计取舍：
--   1) qb_question 直接挂一个 knowledge_point_id，而不是建多对多关联表：
--      一道题挂多个知识点会让「按知识点覆盖度抽题」出现重复计入，
--      而组卷关心的恰恰是覆盖度。真需要多标签时走已有的 tags 列；
--   2) 列可空：存量题目没有知识点标注，加 NOT NULL 会让迁移直接失败。
--      空值的语义是「未标注」，配置了知识点范围后这些题不会被抽中——
--      这是正确行为，但需要运营先补标注，错误信息里要讲清楚；
--   3) 规则上加 knowledge_point_id 而不是知识点数组：
--      一条规则 = 一个题型 + 一个难度 + 一个知识点，重复配置即可表达「多个知识点」，
--      比在规则里解析 JSON 数组更容易校验与报错定位。
-- ============================================================================

ALTER TABLE qb_question ADD COLUMN knowledge_point_id BIGINT NULL
  COMMENT '知识点ID，关联 exam_knowledge_point.id；为空表示未标注，按知识点组卷时不会被抽中';

CREATE INDEX idx_qb_question_knowledge ON qb_question (tenant_id, knowledge_point_id, type, difficulty);

ALTER TABLE exam_paper_rule ADD COLUMN knowledge_point_id BIGINT NULL
  COMMENT '知识点范围，关联 exam_knowledge_point.id；为空表示不限知识点';

INSERT INTO sys_config (tenant_id, config_key, config_value, remark, created_at, updated_at, deleted)
SELECT 1, 'question.knowledge-point.required', 'false',
       '是否强制题目必须标注知识点（开启后未标注题目不允许启用）', NOW(), NOW(), 0
WHERE NOT EXISTS (
  SELECT 1 FROM sys_config WHERE tenant_id = 1 AND config_key = 'question.knowledge-point.required'
);
