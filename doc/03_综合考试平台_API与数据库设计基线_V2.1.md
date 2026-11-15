# 综合考试平台 API 与数据库设计基线

**版本：** V2.1  
**说明：** 本文是研发建模基线，不替代评审后的完整 DDL。字段长度、索引和分库分表策略须结合压测与实际数据量确定。

## 1. API 统一规范

- Base URL：按环境配置，例如 `/api/v1`。
- 请求格式：JSON；时间使用 ISO 8601，金额使用整数分。
- 统一响应：
```json
{
  "code": "0",
  "message": "成功",
  "data": {},
  "traceId": "trace-id"
}
```
- 分页参数：`pageNo`、`pageSize`；最大 pageSize 建议 100。
- 认证：`Authorization: Bearer <token>`。
- 幂等：支付、报名、答题保存、交卷等写接口支持 `Idempotency-Key` 或业务 requestId。
- 错误码建议：`AUTH_`、`TENANT_`、`EXAM_`、`QUESTION_`、`ORDER_`、`PAY_`、`ENTITLEMENT_`。

## 2. 核心 API 清单

| 模块 | 方法 | 路径 | 说明 |
|---|---|---|---|
| 登录 | POST | `/auth/wechat-login` | 小程序登录 |
| 用户 | GET | `/users/me` | 当前用户资料 |
| 项目 | GET | `/projects` | 项目列表 |
| 科目 | GET | `/projects/{id}/subjects` | 科目列表 |
| 题库 | GET | `/questions` | 练习题查询，不返回正式考试答案 |
| 练习 | POST | `/practice/sessions` | 创建练习 |
| 练习 | POST | `/practice/sessions/{id}/answers` | 提交练习答案 |
| 错题 | GET | `/wrong-questions` | 错题列表 |
| 考试 | GET | `/exam-sessions` | 可报名场次 |
| 报名 | POST | `/exam-sessions/{id}/registrations` | 报名 |
| 考试 | POST | `/attempts/{id}/start` | 开始考试 |
| 答题 | PUT | `/attempts/{id}/answers/{questionId}` | 保存答案 |
| 恢复 | GET | `/attempts/{id}/resume` | 恢复考试 |
| 交卷 | POST | `/attempts/{id}/submit` | 主动交卷 |
| 成绩 | GET | `/attempts/{id}/result` | 查询成绩 |
| 商品 | GET | `/products` | 商品列表 |
| 订单 | POST | `/orders` | 创建订单 |
| 支付 | POST | `/orders/{id}/payments` | 创建支付单 |
| 支付回调 | POST | `/payments/wechat/notify` | 微信支付通知 |
| 退款 | POST | `/orders/{id}/refunds` | 申请退款 |
| 权益 | GET | `/entitlements` | 我的权益 |

## 3. 核心接口约定

### 3.1 微信登录
请求：`{ "code": "wx.login返回值", "profile": {} }`  
处理：服务端换取 openid；按 appid + openid 绑定渠道身份；首次登录创建用户；签发平台 token。禁止客户端提交 openid 作为可信身份。

### 3.2 报名
请求：`{ "requestId": "...", "contact": {} }`  
校验：场次状态、报名时间、名额、用户资格、权益、重复报名。成功返回 registrationId、准考状态和须知版本。

### 3.3 开始考试
请求：`{ "confirmNoticeVersion": "v1" }`  
返回：attemptId、serverTime、startedAt、deadlineAt、试卷快照题目（不含标准答案）、保存策略。重复开始请求返回同一有效 attempt。

### 3.4 保存答案
请求：`{ "requestId": "...", "answer": {}, "clientVersion": 3 }`  
响应：`{ "saved": true, "serverVersion": 4, "serverTime": "..." }`  
校验考试状态和截止时间；按服务端版本防止旧请求覆盖新答案。禁止返回标准答案。

### 3.5 交卷
请求：`{ "requestId": "...", "reason": "USER_SUBMIT" }`  
服务端将 attempt 从 IN_PROGRESS 原子更新为 SUBMITTED；重复请求返回既有交卷结果。超时任务使用相同状态转换逻辑。

### 3.6 创建订单
请求：`{ "productId": "...", "skuId": "...", "quantity": 1, "couponId": null }`  
服务端读取商品价格快照并计算应付金额；不信任客户端传入价格。返回 orderId、amountFen、expireAt。

### 3.7 支付通知
必须验签并核对 appid、商户号、商户订单号、支付金额、币种、交易状态。先持久化通知流水，再幂等更新支付单/订单并触发权益发放事件。回调响应遵循微信支付协议。

## 4. 数据库设计原则
1. 主键统一使用 BIGINT 或全局唯一 ID，项目内统一。
2. 业务表包含 `id`、`tenant_id`、`created_at`、`updated_at`、`created_by`、`updated_by`、`deleted`（按需）。
3. 金额以 BIGINT 分存储；不使用浮点数。
4. 高频查询索引结合 where/order by 设计，避免过度索引。
5. 关键业务状态使用明确枚举，并保留状态变更流水。
6. 题目、试卷、考试规则和商品价格使用快照/版本机制。
7. 多租户唯一键通常包含 tenant_id，例如 `(tenant_id, code)`。

## 5. 核心表清单

| 领域 | 表名 | 用途 |
|---|---|---|
| 租户 | `sys_tenant` | 租户主表 |
| 用户 | `sys_user` | 平台用户 |
| 渠道身份 | `sys_user_identity` | 微信等渠道身份 |
| 组织 | `sys_org` | 机构/企业组织 |
| 角色 | `sys_role`、`sys_user_role` | 权限关系 |
| 项目 | `exam_project` | 考试项目 |
| 科目 | `exam_subject` | 项目科目 |
| 知识点 | `exam_knowledge_point` | 知识点树 |
| 题目 | `qb_question` | 题目主表 |
| 题目版本 | `qb_question_version` | 题干/答案历史版本 |
| 选项 | `qb_question_option` | 题目选项 |
| 试卷 | `exam_paper` | 试卷定义 |
| 试卷题目 | `exam_paper_question` | 试卷题目配置 |
| 试卷快照 | `exam_paper_snapshot` | 发布后的不可变快照 |
| 场次 | `exam_session` | 考试场次 |
| 报名 | `exam_registration` | 考生报名 |
| 考试记录 | `exam_attempt` | 考试作答实例 |
| 答题 | `exam_answer` | 考生答案 |
| 成绩 | `exam_result` | 判分结果 |
| 商品 | `mall_product`、`mall_sku` | 商品与 SKU |
| 订单 | `trade_order`、`trade_order_item` | 订单及明细 |
| 支付 | `trade_payment`、`trade_payment_notify` | 支付及通知 |
| 退款 | `trade_refund` | 退款 |
| 权益 | `entitlement_account`、`entitlement_ledger` | 权益账户与流水 |
| 会员 | `member_subscription` | 会员订阅 |
| 消息 | `msg_task`、`msg_send_log` | 消息任务与发送记录 |
| 审计 | `sys_audit_log` | 管理操作审计 |

## 6. 关键字段建议

### `exam_attempt`
- `id`、`tenant_id`、`session_id`、`registration_id`、`user_id`
- `paper_snapshot_id`、`status`（CREATED/IN_PROGRESS/SUBMITTED/EXPIRED/REVIEWING/GRADED）
- `started_at`、`deadline_at`、`submitted_at`
- `total_score`、`scoring_version`、`answer_version`
- 唯一约束建议：有效场次内按 `(tenant_id, session_id, user_id, attempt_no)` 控制次数。

### `exam_answer`
- `id`、`tenant_id`、`attempt_id`、`question_id`、`question_snapshot_id`
- `answer_json`、`answer_version`、`is_marked`、`saved_at`
- 唯一约束：`(tenant_id, attempt_id, question_id)`。
- 更新采用乐观锁；保留必要的答案变更历史或事件日志。

### `trade_order`
- `id`、`tenant_id`、`order_no`、`user_id`、`status`
- `total_amount_fen`、`discount_amount_fen`、`pay_amount_fen`
- `expire_at`、`paid_at`、`source`、`remark`
- 唯一约束：`(tenant_id, order_no)`。

### `entitlement_ledger`
- `id`、`tenant_id`、`user_id`、`entitlement_type`、`resource_id`
- `change_type`、`change_amount`、`balance_after`、`source_order_id`
- `event_id`、`expire_at`、`created_at`
- 幂等约束建议：`(tenant_id, event_id, entitlement_type, resource_id)`。

## 7. DDL 示例

```sql
CREATE TABLE exam_attempt (
  id BIGINT NOT NULL PRIMARY KEY,
  tenant_id BIGINT NOT NULL,
  session_id BIGINT NOT NULL,
  registration_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  paper_snapshot_id BIGINT NOT NULL,
  status VARCHAR(24) NOT NULL,
  started_at DATETIME(3) NULL,
  deadline_at DATETIME(3) NULL,
  submitted_at DATETIME(3) NULL,
  total_score DECIMAL(10,2) NULL,
  scoring_version INT NOT NULL DEFAULT 1,
  answer_version INT NOT NULL DEFAULT 0,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  deleted TINYINT NOT NULL DEFAULT 0,
  KEY idx_attempt_user_session (tenant_id, user_id, session_id),
  KEY idx_attempt_status_deadline (status, deadline_at),
  KEY idx_attempt_registration (tenant_id, registration_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

## 8. 状态机基线

- 订单：CREATED → PENDING_PAYMENT → PAID → COMPLETED；异常分支 CLOSED / REFUNDING / REFUNDED。
- 考试：CREATED → IN_PROGRESS → SUBMITTED / EXPIRED → REVIEWING（可选）→ GRADED → PUBLISHED。
- 报名：PENDING → CONFIRMED → CANCELLED / ABSENT。
- 权益：AVAILABLE → PART_USED → USED / EXPIRED / REVOKED。

## 9. 索引与数据治理
按租户、用户、场次、状态和时间建立组合索引；避免低选择性字段单独建索引。大表按时间归档，考试答题与审计日志制定保留期限。题目图片和导入文件存对象存储，数据库仅存对象 key 和元数据。所有批量导入导出使用异步任务并提供任务状态。

## 10. 评审待确认
ID 生成方案、是否分库分表、软删除策略、考试数据保留期限、题目版本保留周期、支付渠道、权益核销模型、机构授权颗粒度、并发规模及数据合规要求。
