-- PRD 3.1 商城「套餐」第一步：PACKAGE 商品 + 静态组合明细表。
-- 计价口径：套餐价 = mall_product.price_fen 自身，不做折扣率派生（一个价格只有一个数）。
-- 履约口径：第一步下单/支付沿用现有整单发放；第二步再按明细逐件履行。
CREATE TABLE IF NOT EXISTS mall_package_item (
    id                 BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    tenant_id          BIGINT NOT NULL COMMENT '租户ID',
    package_product_id BIGINT NOT NULL COMMENT '套餐商品ID（mall_product.id）',
    item_product_id    BIGINT NOT NULL COMMENT '组内商品ID（mall_product.id）',
    quantity           INT    NOT NULL DEFAULT 1 COMMENT '数量',
    created_at         DATETIME NOT NULL,
    updated_at         DATETIME NOT NULL,
    deleted            TINYINT NOT NULL DEFAULT 0
);
CREATE INDEX idx_mall_pkg_item_pkg ON mall_package_item (package_product_id);
