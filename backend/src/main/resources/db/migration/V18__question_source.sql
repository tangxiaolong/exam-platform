-- PRD 4.2 题目字段清单：来源（source）此前缺失，补列
ALTER TABLE qb_question ADD COLUMN source VARCHAR(255) NULL COMMENT '题目来源（教材/真题/自编等）';
