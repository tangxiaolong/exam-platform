-- =============================================================================
-- 演示环境测试数据补齐（MySQL）——幂等，可重复执行，不覆盖已有数据
-- 覆盖：活动公告 / 套餐商品 / 待审核退款单（其余模块数据已存在则跳过）
-- 执行方式参考 README 或联系维护者；无 mysql 客户端时可用 JDBC 脚本执行
-- =============================================================================

-- 1) 活动公告：双十一备考季活动
INSERT INTO cms_notice (tenant_id, title, content, type, status, pinned, publish_at, expire_at,
                        publisher_id, created_at, updated_at, deleted)
SELECT 1, '双十一备考季活动', '活动期间报名立减 20 元，PMP 题库包限时 8 折。',
       'ACTIVITY', 'PUBLISHED', 0,
       DATE_SUB(NOW(), INTERVAL 6 HOUR), DATE_ADD(NOW(), INTERVAL 30 DAY),
       (SELECT id FROM sys_user WHERE username = 'admin' ORDER BY id LIMIT 1),
       NOW(), NOW(), 0
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM (SELECT 1 FROM cms_notice WHERE title = '双十一备考季活动') t);

-- 2) 平台公告：若不存在则补一条置顶公告
INSERT INTO cms_notice (tenant_id, title, content, type, status, pinned, publish_at,
                        publisher_id, created_at, updated_at, deleted)
SELECT 1, '平台正式上线公告', '综合考试平台正式开放报名，支持网页与微信小程序参加考试。',
       'NOTICE', 'PUBLISHED', 1, DATE_SUB(NOW(), INTERVAL 3 DAY),
       (SELECT id FROM sys_user WHERE username = 'admin' ORDER BY id LIMIT 1),
       NOW(), NOW(), 0
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM (SELECT 1 FROM cms_notice WHERE title = '平台正式上线公告') t);

-- 3) 套餐商品：四级备考套餐（PACKAGE 类型）
INSERT INTO mall_product (tenant_id, code, name, type, description, price_fen, original_price_fen,
                          validity_days, status, sort_no, created_at, updated_at, deleted)
SELECT 1, 'PKG-CET4-COMBO', '四级备考套餐', 'PACKAGE', '会员 + 题库包组合，备考季打包价',
       12900, 19900, 365, 'ON_SALE', 4, NOW(), NOW(), 0
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM (SELECT 1 FROM mall_product WHERE code = 'PKG-CET4-COMBO') t);

-- 4) 退款审核：对最近一笔已完成订单生成一条待审核退款单
INSERT INTO trade_refund (tenant_id, refund_no, order_id, order_no, user_id, amount_fen,
                          status, created_at, updated_at, deleted)
SELECT 1, 'R-DEMO-0001', o.id, o.order_no, o.user_id, o.pay_amount_fen, 'APPLIED',
       DATE_SUB(NOW(), INTERVAL 20 HOUR), DATE_SUB(NOW(), INTERVAL 20 HOUR), 0
FROM trade_order o
WHERE o.status = 'COMPLETED'
  AND NOT EXISTS (SELECT 1 FROM (SELECT 1 FROM trade_refund WHERE refund_no = 'R-DEMO-0001') t)
ORDER BY o.id DESC
LIMIT 1;
