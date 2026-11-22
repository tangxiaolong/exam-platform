-- PRD 3.1 首页「活动」：活动复用公告模型，以 type 区分（NOTICE=通用公告，ACTIVITY=营销活动），
-- 不新建活动表——活动报名本质是场次报名、活动优惠本质是券，均已有链路。
ALTER TABLE cms_notice ADD COLUMN type VARCHAR(20) NOT NULL DEFAULT 'NOTICE' COMMENT '公告类型：NOTICE / ACTIVITY';
CREATE INDEX idx_cms_notice_type ON cms_notice (tenant_id, type);
