-- ============================================================================
-- V11__mall_sku_coupon_item：SKU / 订单明细 / 优惠券
--
-- 背景：原 `mall_product` 是一价一物，订单只有 product_id + quantity，
-- 退款只能整单全额。这三件事互相咬合，缺一个另外两个都不成立：
--   1) SKU：同一商品（如「会员」）要有月卡/季卡/年卡，价格与时长不同；
--      没有 SKU，订单金额就没有真实来源，优惠券也没有可依附的单价；
--   2) 订单明细：没有明细，部分退款算不出「退哪一项、退多少」；
--   3) 优惠券：抵扣必须落在订单金额上，否则退款金额与实付对不上。
--
-- 设计取舍：
--   1) 明细落「快照」（商品名 / SKU 名 / 单价），下单后改价不影响历史订单；
--   2) 优惠抵扣只记在订单级 `discount_amount_fen`，不分摊到明细——
--      分摊会引入尾差，且退款时还要回答「谁承担了折扣」，首期不做；
--      代价是部分退款时按「明细原价占比」折算实付，规则写死在 RefundService 注释；
--   3) 明细上记 `refunded_amount_fen` 做累计校验，否则同一笔可以重复退；
--   4) 券的「锁定」状态是必要的：下单锁券、支付成功核销、订单关闭/超时回滚，
--      少了 LOCKED 就会出现「同一张券同时抵两笔订单」。
-- ============================================================================

CREATE TABLE IF NOT EXISTS mall_sku (
  id                 BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT 'SKU ID，主键',
  tenant_id          BIGINT       NOT NULL COMMENT '租户ID，关联 sys_tenant.id',
  product_id         BIGINT       NOT NULL COMMENT '商品ID，关联 mall_product.id',
  code               VARCHAR(64)  NOT NULL COMMENT 'SKU 编码，商品内唯一',
  name               VARCHAR(128) NOT NULL COMMENT 'SKU 名称，如「年卡」',
  price_fen          BIGINT       NOT NULL COMMENT '售价（分），订单明细的单价来源',
  original_price_fen BIGINT       NULL COMMENT '划线价（分），仅展示',
  duration_days      INT          NULL COMMENT '有效天数；NULL 表示沿用商品配置',
  status             VARCHAR(16)  NOT NULL DEFAULT 'ON_SALE' COMMENT '状态：ON_SALE-在售，OFF_SALE-下架',
  sort_no            INT          NOT NULL DEFAULT 0 COMMENT '排序号，小的在前',
  created_at         DATETIME     NOT NULL COMMENT '创建时间',
  updated_at         DATETIME     NOT NULL COMMENT '最后更新时间',
  deleted            TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除标记：0-正常，1-已删除'
);
CREATE UNIQUE INDEX uk_mall_sku_code ON mall_sku (tenant_id, product_id, code);
CREATE INDEX idx_mall_sku_product ON mall_sku (tenant_id, product_id, status);

CREATE TABLE IF NOT EXISTS trade_order_item (
  id                  BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '明细ID，主键',
  tenant_id           BIGINT       NOT NULL COMMENT '租户ID，关联 sys_tenant.id',
  order_id            BIGINT       NOT NULL COMMENT '订单ID，关联 trade_order.id',
  product_id          BIGINT       NOT NULL COMMENT '商品ID（快照关联）',
  sku_id              BIGINT       NOT NULL COMMENT 'SKU ID（快照关联）',
  product_name        VARCHAR(128) NOT NULL COMMENT '商品名快照，下单时的名称',
  sku_name            VARCHAR(128) NOT NULL COMMENT 'SKU 名快照',
  unit_price_fen      BIGINT       NOT NULL COMMENT '成交单价（分），下单时的 SKU 价格',
  quantity            INT          NOT NULL COMMENT '数量',
  amount_fen          BIGINT       NOT NULL COMMENT '明细金额 = 单价 × 数量（分）',
  refunded_amount_fen BIGINT       NOT NULL DEFAULT 0 COMMENT '该明细已退金额（分），用于累计校验',
  duration_days       INT          NULL COMMENT '该明细对应的权益有效天数',
  created_at          DATETIME     NOT NULL COMMENT '创建时间',
  updated_at          DATETIME     NOT NULL COMMENT '最后更新时间',
  deleted             TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除标记：0-正常，1-已删除'
);
CREATE INDEX idx_trade_order_item_order ON trade_order_item (tenant_id, order_id);

CREATE TABLE IF NOT EXISTS marketing_coupon (
  id                BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '优惠券ID，主键',
  tenant_id         BIGINT       NOT NULL COMMENT '租户ID，关联 sys_tenant.id',
  code              VARCHAR(64)  NOT NULL COMMENT '券编码，租户内唯一',
  name              VARCHAR(128) NOT NULL COMMENT '券名称，对用户展示',
  type              VARCHAR(16)  NOT NULL COMMENT '类型：AMOUNT-满减，DISCOUNT-折扣',
  threshold_fen     BIGINT       NOT NULL DEFAULT 0 COMMENT '使用门槛（分），订单实付满此金额可用',
  value_fen         BIGINT       NOT NULL DEFAULT 0 COMMENT '满减金额（分），type=AMOUNT 时有效',
  discount_percent  INT          NOT NULL DEFAULT 0 COMMENT '折扣百分比，85 表示 85 折，type=DISCOUNT 时有效',
  max_discount_fen  BIGINT       NULL COMMENT '折扣券封顶金额（分），NULL 表示不封顶',
  total_quantity    INT          NOT NULL DEFAULT 0 COMMENT '发行总量，0 表示不限量',
  per_user_limit    INT          NOT NULL DEFAULT 1 COMMENT '每用户限领数量',
  scope_type        VARCHAR(16)  NOT NULL DEFAULT 'ALL' COMMENT '适用范围：ALL-全部商品，PRODUCT-指定商品',
  scope_id          BIGINT       NULL COMMENT 'scope_type=PRODUCT 时的商品ID',
  valid_from        DATETIME     NOT NULL COMMENT '有效期开始',
  valid_to          DATETIME     NOT NULL COMMENT '有效期结束',
  status            VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT '状态：ACTIVE-可领可用，STOPPED-停用',
  created_at        DATETIME     NOT NULL COMMENT '创建时间',
  updated_at        DATETIME     NOT NULL COMMENT '最后更新时间',
  deleted           TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除标记：0-正常，1-已删除'
);
CREATE UNIQUE INDEX uk_marketing_coupon_code ON marketing_coupon (tenant_id, code);

CREATE TABLE IF NOT EXISTS marketing_coupon_grant (
  id            BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '持券记录ID，主键',
  tenant_id     BIGINT      NOT NULL COMMENT '租户ID，关联 sys_tenant.id',
  coupon_id     BIGINT      NOT NULL COMMENT '优惠券ID，关联 marketing_coupon.id',
  user_id       BIGINT      NOT NULL COMMENT '持有人，关联 sys_user.id',
  status        VARCHAR(16) NOT NULL COMMENT '状态：UNUSED-未使用，LOCKED-已锁定下单中，USED-已核销，EXPIRED-已过期',
  discount_fen  BIGINT      NULL COMMENT '实际抵扣金额（分），核销时写入',
  order_id      BIGINT      NULL COMMENT '关联订单ID，锁定与核销时写入',
  granted_at    DATETIME    NOT NULL COMMENT '领取时间',
  used_at       DATETIME    NULL COMMENT '核销时间',
  created_at    DATETIME    NOT NULL COMMENT '创建时间',
  updated_at    DATETIME    NOT NULL COMMENT '最后更新时间',
  deleted       TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除标记：0-正常，1-已删除'
);
CREATE INDEX idx_coupon_grant_user ON marketing_coupon_grant (tenant_id, user_id, status);
CREATE INDEX idx_coupon_grant_order ON marketing_coupon_grant (tenant_id, order_id);

ALTER TABLE marketing_coupon
  ADD COLUMN claimed_quantity INT NOT NULL DEFAULT 0 COMMENT '已领取数量，领取时原子累加，用于控量防超发';

-- 为存量商品补一个默认 SKU。
-- 老前端与老订单只传 productId，如果商品没有 SKU，订单明细就写不出 sku_id，
-- 部分退款也就无从归因——所以默认 SKU 是这次迁移的必要一环，而不是可选优化。
INSERT INTO mall_sku (tenant_id, product_id, code, name, price_fen, original_price_fen,
                      duration_days, status, sort_no, created_at, updated_at, deleted)
SELECT p.tenant_id, p.id, 'DEFAULT', p.name, p.price_fen, p.original_price_fen,
       p.validity_days, 'ON_SALE', 0, NOW(), NOW(), 0
FROM mall_product p
WHERE NOT EXISTS (
  SELECT 1 FROM mall_sku s
  WHERE s.tenant_id = p.tenant_id AND s.product_id = p.id AND s.code = 'DEFAULT'
);

ALTER TABLE trade_order
  ADD COLUMN coupon_grant_id BIGINT NULL COMMENT '使用的优惠券持券记录ID，NULL 表示未用券';

ALTER TABLE trade_order
  ADD COLUMN item_count INT NOT NULL DEFAULT 1 COMMENT '明细行数，便于列表展示不查明细表';

ALTER TABLE trade_refund
  ADD COLUMN order_item_id BIGINT NULL COMMENT '部分退款时对应的订单明细ID，NULL 表示整单退款';

ALTER TABLE trade_refund
  ADD COLUMN quantity INT NOT NULL DEFAULT 1 COMMENT '本次退款数量（部分退款时为正数）';
