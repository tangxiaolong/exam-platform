# 综合考试平台 · 基础工程

按 `doc/` 下 PRD V3.1、技术设计 V1.1、API/DB 基线 V2.1、WBS V2.1 搭建的**最小可运行基线**。

三个工程，不做服务拆分：


| 工程             | 技术栈                                              | 说明                           |
| -------------- | ------------------------------------------------ | ---------------------------- |
| `backend/`     | Java 17 + Spring Boot 3.2 + MyBatis-Plus 3.5.9   | **一个**后台服务（模块化单体），替代文档中的多微服务 |
| `admin-web/`   | Vue 3 + TypeScript + Vite + Element Plus + Pinia | 独立运营后台                       |
| `miniprogram/` | 微信原生小程序（纯 JS，零构建）                                | 考生端                          |


> 设计取舍：文档第 3 节明确「首期建议采用模块化单体 + 核心服务独立部署的渐进式方案，不以服务数量作为微服务成熟度指标」，因此本工程把 `identity / content / question / paper / exam / learning / trade / entitlement` 全部按**包边界**落在同一个 Spring Boot 进程内，模块之间只通过 Service 协作，不跨模块读写对方表。后续需要独立部署时，按包直接拆成独立工程即可。

---

## 1. 目录结构

```text
teamGitCode/
├── doc/                                  # 需求与技术基线文档
├── backend/                              # 后台服务（单体）
│   ├── pom.xml
│   └── src/main/
│       ├── java/com/exam/platform/
│       │   ├── ExamPlatformApplication.java
│       │   ├── common/                   # 公共能力：统一响应、异常、JWT、租户、MyBatis 配置
│       │   └── modules/
│       │       ├── auth/                 # 登录（账号密码 / 微信）、当前用户
│       │       ├── system/               # 用户、租户、审计日志
│       │       ├── content/              # 考试项目、科目、知识点
│       │       ├── question/             # 题库、判分规则
│       │       ├── paper/                # 试卷、试卷快照
│       │       ├── exam/                 # 场次、报名、作答、交卷、判分、成绩、自动交卷任务
│       │       ├── learning/             # 练习、错题
│       │       ├── trade/                # 商品、订单、支付、支付回调
│       │       ├── entitlement/          # 权益账户与流水
│       │       ├── dashboard/            # 运营工作台
│       │       └── demo/                 # 演示数据初始化
│       └── resources/
│           ├── application.yml           # 公共配置
│           ├── application-h2.yml        # 默认：内存 H2，开箱即跑
│           ├── application-mysql.yml     # 生产：MySQL 8
│           └── db/
│               └── migration/
│                   └── V1__baseline.sql # Flyway 基线：建表 + 二级索引
├── admin-web/                            # 运营后台
│   └── src/{api,layout,router,stores,views,styles}
└── miniprogram/                          # 微信小程序
    ├── app.js / app.json / app.wxss
    ├── config.js                         # 后端地址、mock 登录开关
    ├── utils/                            # 请求封装、时间金额格式化
    └── pages/{index,project,practice,exam,mall,my}
```

---



## 2. 快速开始



### 2.1 后端（默认 H2，无需装任何中间件）

```bash
cd backend
mvn spring-boot:run
```

- 服务地址：`http://localhost:8080/api`
- 接口文档：`http://localhost:8080/api/swagger-ui.html`
- H2 控制台：`http://localhost:8080/api/h2-console`（JDBC URL `jdbc:h2:mem:exam_platform`，用户 `sa`，空密码）
- 启动后自动建表并写入演示数据（项目、科目、6 道题、试卷、已发布场次、2 个商品、管理员与体验考生）

切换到 MySQL：

```bash
createdb exam_platform            # 或用 DDL 工具建库，字符集 utf8mb4
cd backend
mvn spring-boot:run "-D spring-boot.run.profiles=mysql"
# PowerShell 下 -D 参数必须整体加引号，否则会被拆成 "-Dspring-boot" 与 ".run.profiles=mysql"，
# Maven 会报 Unknown lifecycle phase ".run.profiles=mysql"
# 可用环境变量覆盖：MYSQL_URL / MYSQL_USER / MYSQL_PASSWORD
# 表结构与二级索引由 Flyway 启动时自动迁移（db/migration），无需手工执行 DDL
```

生产必须覆盖的配置：


| 配置                                                              | 说明                                                                                   |
| --------------------------------------------------------------- | ------------------------------------------------------------------------------------ |
| `APP_JWT_SECRET`                                                | JWT 签名密钥（≥32 字节）                                                                     |
| `app.wechat.mock=false` + `WECHAT_APP_ID` / `WECHAT_APP_SECRET` | 关闭模拟登录，改走真实 `jscode2session`                                                          |
| `WECHAT_PAY_MCH_ID` / `WECHAT_PAY_API_V3_KEY` / `WECHAT_PAY_MERCHANT_SERIAL` | 微信支付 v3 商户号、APIv3 密钥（32 字节）、商户证书序列号；`mock=false` 时任一缺失会**直接启动失败**（不给「线上误用模拟支付」留窗口） |
| `WECHAT_PAY_PRIVATE_KEY` 或 `WECHAT_PAY_PRIVATE_KEY_PATH` | 商户 API 私钥（PKCS#8 PEM）；容器环境通常挂载文件路径                                                        |
| `WECHAT_PAY_NOTIFY_URL`                                         | 支付回调地址，**必须 https 公网可达**（配成 http 时启动即报错，因为微信不会回调 http）                              |
| `app.demo-data.enabled=false`                                   | 关闭演示数据                                                                               |
| `APP_TRUSTED_PROXIES`                                           | 部署在 Nginx / SLB 之后**必须**填可信代理网段（如 `10.0.0.0/8`），否则 IP 维度限流按 socket 对端计数，所有用户会共用同一份配额 |
| `APP_RATE_LIMIT_BACKEND=redis` + `spring.data.redis.*`          | **多实例部署必须**切到分布式限流，否则实际配额 = 配置值 × 实例数                                                |
| `APP_REDIS_HEALTH_ENABLED=true`                                 | 切到 Redis 后端时打开 Redis 健康探测                                                            |




### 2.1.1 限流（P2-A 准入硬指标）

令牌桶按「规则 × 维度」独立计数，用户维度与 IP 维度**任一耗尽即拒绝**——只按用户限流挡不住批量注册刷号，只按 IP 限流挡不住同出口多账号刷接口。


| 规则            | 命中路径                                                                       | 每用户    | 每 IP   |
| ------------- | -------------------------------------------------------------------------- | ------ | ------ |
| `answer-save` | `/v1/attempts/*/answers/*`、`/v1/practice/sessions/*/answers`               | 20/分钟  | 120/分钟 |
| `auth-login`  | `/v1/auth/login`、`/v1/auth/wechat-login`                                   | 60/分钟  | 300/分钟 |
| `export`      | `/v1/admin/exports/*/download`、`/v1/admin/reconciliation/anomalies/export` | 10/分钟  | 30/分钟  |
| `default`     | 其余 `/v1/**`                                                                | 300/分钟 | 600/分钟 |


`export` 单独配一组更严的配额，理由是这类接口的「一次请求」等于「一整份数据」：订单报表一次就是全量订单，异常清单一次是全部待处置记录。走 `default` 的 300 次/分钟，意味着一个账号一分钟能把整库拉走 300 遍，而它平时一分钟只会点两次——人手动下载不会超过 10 次，自动化爬取一定会，配额就是用来区分这两者的。

两种后端按 `app.rate-limit.backend` 切换，拦截器不感知差异：

- `memory`（默认）：进程内令牌桶，零依赖。配额是「每实例」的，适合单体与开发环境。
- `redis`：Redis + Lua 原子令牌桶，配额全局精确。时间取 Redis 服务端 `TIME` 而非各实例本机时钟——多实例时钟漂移会让同一个桶在不同节点算出不同补充量。

拒绝时返回 `RATE_LIMITED` + `Retry-After` 头，并累加 `app.ratelimit.rejected` 指标（按 `rule` / `dimension` 打标签）。

Redis 不可用时**降级到本地令牌桶（fail-degraded），不是放行**：

- 早期实现是纯 fail-open（一律放行），理由是「限流是保护措施而非硬依赖」。那个取舍漏掉了一层：Redis 一挂，防刷这件事整体消失，只剩一条告警提醒「现在谁都能无限刷」——而攻击者恰好会在你忙着救 Redis 的时候刷进来；
- 现在 Redis 取令牌抛错即刻切到进程内桶（同 key、同配置），配额按**单实例**口径继续生效；多实例时总配额会放大成「配置值 × 实例数」，口径放宽好过彻底不设防；
- 同时置一段熔断冷却（`app.rate-limit.redis-degraded-cooldown-ms`，默认 5s）：冷却期内不再尝试 Redis，否则每个请求都要先付一次连接超时，限流自己就成了拖慢全站的元凶；到期自动试探一次，成功即恢复分布式口径；
- 指标分两条：`app.ratelimit.backend.error`（真去连 Redis 但失败）、`app.ratelimit.backend.degraded{decision=allow|deny}`（这次由本地桶判定）。两者一起才能回答「降级持续了多久、有没有误拒正常用户」；
- 想把口径退回去做纯 fail-open：`app.rate-limit.redis-fallback=false`。



### 2.1.2 文件上传

落盘前的校验链：扩展名白名单 → 大小上限 → **文件头魔数比对** → 服务端重命名 → 路径穿越断言。

- 魔数校验：只看扩展名会被「改后缀」绕过，二进制格式（jpg/png/pdf/xlsx）强制比对文件头；CSV 无魔数，退化为「必须是合法文本」（按无符号字节判定，否则 UTF-8 中文会被误判成二进制）。
- 路径穿越：先 `normalize` 再断言仍在根目录内；绝对路径、盘符路径单独拒绝。下载走 query 参数而非路径参数，避免 `../` 在 URL 规范化阶段变成另一个路径。
- 落盘名由服务端生成（UUID + 校验过的扩展名），原名只作记录。

`POST /v1/uploads`、`GET /v1/uploads/content?path=...`

### 2.1.3 题目批量导入

`POST /v1/admin/questions/imports`（csv / xlsx），对齐 4.2 验收「导入成功/失败行均有结果报告」。

**校验式导入**：先整表逐行校验并收集全部失败原因，只要有失败行就整批不落库，避免导入一半留下只能人工清洗的题库。表头支持中英文别名、列顺序无关；选项格式 `A:甲|B:乙`；判断题答案接受 `对/错/T/F/√/×` 并规范化为 `A/B`；行号对齐 Excel 原行号（含表头）。

返回结构：

```json
{
  "totalRows": 6, "successCount": 1, "failureCount": 5, "committed": false,
  "rows": [
    { "lineNo": 2, "status": "SUCCESS", "stem": "合法题目" },
    { "lineNo": 3, "status": "FAILED", "errorCode": "QUESTION_IMPORT_ROW_INVALID",
      "errorMessage": "标准答案不能为空", "stem": "缺答案题目" }
  ]
}
```



### 2.1.4 审计（声明式，非手写）

管理端写操作统一用 `@Audited(action = "...", target = "...", detail = "...")` 声明，由 `AuditAspect` 落 `sys_audit_log`。

改造原因很实际：手写 `auditService.record(...)` 时，新增接口会忘——实测就漏了 5 个写接口（科目改/删、知识点增改删）且长期无人察觉。注解化之后，「有没有审计」从"靠人记得"变成可被 CI 捕获的状态：

- `AuditCoverageTest#everyAdminWriteEndpointMustBeAudited` 静态扫描类路径，任何 `/v1/admin/**` 写接口缺注解即测试失败；只读 POST 需显式加入白名单并说明理由。
- 其余用例真实调用接口，断言审计确实落库、`target` 解析出主键、`detail` 解析出业务字段。

三个刻意的行为选择：

1. **只在方法正常返回后记录**：异常代表操作未生效，记「已执行」会误导排查；
2. **审计失败绝不影响主流程**：表达式求值出错只降级为 `null` 并打 warn，审计是旁路能力，不能因它的问题回滚业务事务；
3. **表达式用 SpEL**：`target` / `detail` 可引用方法参数名（`#id`、`#status`）与返回值 `#result`。实体类（Lombok getter）用属性访问 `#result.data.name`，`record` 用方法调用 `#result.data.id()`。

**读的入口**（此前只有写、没有读）：审计记录落库后，客服问「谁在几点强制收了某人的卷」仍只能直连数据库翻——审计能力被锁在 DBA 手里。

- `GET /v1/admin/audit-logs`：`action` 精确匹配（动作是有限取值，模糊只会带出无关记录）、`username` 模糊匹配（运营通常只记得半个账号）、`from`/`to` 日期区间，分页返回；
- `GET /v1/admin/audit-logs/actions`：已出现过的动作品类，供前端下拉；
- 日期区间按「起始日 00:00 ≤ t < 截止日次日 00:00」处理，`<= 当天 23:59:59` 会漏掉最后一秒的记录；租户隔离仍由全局租户插件负责，查询代码里不手写 `tenant_id`。

**数据外带同样留痕**：报表下载（`REPORT_EXPORT_DOWNLOAD`）与对账异常清单导出（`TRADE_RECON_EXPORT`）也标了 `@Audited`，尽管它们是 GET 而不是写操作。覆盖率测试只强制要求写接口有注解，但这两类接口是「把整份数据搬出系统」——「谁在什么时候拉走了哪份报表」事后必须能回答，否则数据一旦外泄，连调查起点都没有。两份导出还额外走 `export` 限流规则（10 次/分钟），防的是自动化批量拉取。

**保留期与归档**：审计表只增不减——管理端每个写操作落一条，一个活跃租户一年几十万行。V4 迁移补了 `(tenant_id, action, created_at)` 索引支撑上面那套查询（租户等值 → 动作等值 → 时间范围，三列都参与），并提供一套**默认关闭**的归档任务：

```bash
# 开启归档（默认关闭：删审计要人明确决定，不能由新版本悄悄带上默认行为）
APP_AUDIT_ARCHIVE_ENABLED=true APP_AUDIT_RETENTION_DAYS=180
```

- 超期记录**先写成 CSV 再物理删除**：归档文件是删除的前提，写文件失败则这批不删，下轮还能归档；
- **跨租户**处理（mapper 上 `@InterceptorIgnore(tenantLine)`）：归档任务跑在无登录态的系统线程里，不显式忽略租户拦截的话，其它租户的旧记录永远清不掉；
- 用原生 `DELETE` 而非逻辑删除：逻辑删除留下 `deleted=1` 的占位行，表照样膨胀，而审计的语义是「这条记录真实存在过」；
- 归档任务接入了「定时任务健康」指标——它失败通常意味着磁盘不可写或数据库拒绝删除，两种都需要人介入，等磁盘满了才发现就晚了。



### 2.1.5 超时保护

对齐 4.5「答题保存接口具备幂等、限流和**超时保护**」。超时保护分三层，避免慢查询在集中开考时一点点耗尽线程与连接：


| 层      | 配置                                                         | 作用                           |
| ------ | ---------------------------------------------------------- | ---------------------------- |
| SQL 执行 | `mybatis-plus.configuration.default-statement-timeout: 10` | 每条语句的执行上限（秒）                 |
| 取连接    | `spring.datasource.hikari.connection-timeout: 3000`        | 池满时快速失败，不让请求线程无限排队           |
| 网络     | MySQL URL `connectTimeout=3000&socketTimeout=30000`        | 连不上 / 读不返回时断开，而不是挂在 socket 上 |


超时与过载异常单独归类，**不当成代码 bug**：`QueryTimeoutException` / `CannotAcquireLockException` → `COMMON_TIMEOUT`，`CannotGetJdbcConnectionException` → `COMMON_BUSY`，均为可重试语义，日志降为 warn（走通用分支会打 error 并返回「服务开小差」，既淹没有效告警，客户端也不知道该不该重试）。

配置是否真的生效由 `TimeoutProtectionTest` 断言到 MyBatis 运行时配置——只断言 yml 里有这行字是无效的，写错时它是静默失效。

### 2.1.6 数据库迁移（Flyway）

表结构不再靠「启动时跑一份幂等 DDL」，改为版本化迁移脚本：

```
backend/src/main/resources/db/migration/
├── V1__baseline.sql                # 基线：37 张业务表 + 24 个二级索引
├── V2__reconciliation.sql          # 对账报告与异常明细
├── V3__recon_anomaly_handling.sql   # 对账异常的处置闭环（状态 / 说明 / 处置人 / 处置时间）
├── V4__audit_log_retention.sql      # 审计日志按 action 检索的索引
├── V5__org_quota.sql                # 机构额度池
├── V6__msg_template.sql             # 消息模板可配置化
├── V7__org_quota_expire.sql         # 额度有效期
├── V8__org_quota_recharge.sql       # 额度充值流水
├── V9__org_quota_consume.sql        # 额度发放流水
└── V10__org_quota_recon_index.sql   # 对账用的 created_at 索引
```

当前版本：**v10**。全新库执行一次 `mvn spring-boot:run` 会依次跑完 10 个脚本（H2 约 0.2s，MySQL 约 4.6s）。

- 应用启动时自动迁移，`spring.flyway.baseline-on-migrate=true`：历史上用 `schema.sql` 建过表的库首次接入会**自动打基线**，不会因「表已存在」启动失败；
- **已部署的库不要再改** `V1`：结构变更新增 `V2__xxx.sql`，改基线会让已迁移的库因 checksum 不一致而拒绝启动；
- 二级索引同步进基线，而不是留一份「生产环境请手工执行 `mysql-indexes.sql`」的散落脚本——新库必须是可复现的完整结构；
- H2（`MODE=MySQL`）与 MySQL 8.x 共用同一套脚本，测试跑的就是部署用的脚本。

**迁移演练是发布前的前置条件**：迁移失败等于应用起不来（Flyway 中断启动）。H2 的兼容模式抹掉的差异恰好是生产首次迁移最容易翻车的地方（索引长度上限、字符集、`ALTER` 默认值回填、`ADD COLUMN IF NOT EXISTS` 不被支持），所以迁移必须在真 MySQL 上先跑过一遍：

```bash
bash tools/check-mysql-migration.sh      # 或 pwsh tools/check-mysql-migration.ps1
```

CI 的 `MySQL 8 · flyway migrate` job 每次 PR 都跑同一件事，并断言「重复 `migrate` 是无操作」。演练方式与回滚预案见 `[doc/05_数据库迁移演练与回滚预案.md](doc/05_数据库迁移演练与回滚预案.md)`，涉及删改结构的迁移脚本必须在 `deploy/rollback/` 提交同名 `.down.sql`。

### 2.1.7 可观测性（Prometheus + 告警规则）

```bash
curl http://localhost:8080/api/actuator/prometheus   # 抓取端点（只读，无需登录）
```

- 已开启 `http_server_requests` / `hikaricp_connections_acquire` 直方图：没有直方图只能算平均值，p95/p99 告警根本无从谈起；
- 自定义指标：`app_ratelimit_rejected_total{rule,dimension}`、`app_ratelimit_backend_error_total`、`app_ratelimit_backend_degraded_total{decision}`、`app_recon_anomaly_total{type}`、`app_job_staleness_seconds{job}` / `app_job_stale_ratio{job}` / `app_job_consecutive_failures{job}`；
- 告警规则见 `deploy/prometheus/exam-platform-rules.yml`（服务存活、5xx 比例、答题保存 p99、限流拒绝风暴、**限流后端降级**、**对账检出异常**、**定时任务停摆 / 连续失败**、连接池排队/取连接超时、GC 停顿）；
- 「限流后端降级」单独设 critical：Redis 故障时降级到本地令牌桶，配额按单实例继续生效（口径被放大），这是「防刷变弱」的静默失效，不告警等于默认接受；
- 看板见 `deploy/grafana/exam-platform-dashboard.json`（10 个面板，15 条查询：实例存活、5xx 比例、答题保存 p99 / 全站 p95、限流拒绝与降级、连接池、JVM、对账异常、定时任务停摆与连续失败）。Grafana 里「Dashboards → Import → Upload JSON」，选择 Prometheus 数据源即可；
- 「定时任务停摆」用 `app_job_stale_ratio > 2` 判定：三个任务周期不同（对账 24h / 消息重试 60s / 自动交卷 30s），但都只需回答一个问题——「距上次成功是不是已经过了两个周期」。任务没跑这件事在日志里看不出来（没有日志本身就是信号，可没人会去翻「缺少的日志」），只能由指标主动暴露。

`PrometheusMetricsTest` 断言的是**抓取端点的真实输出**（指标名、标签是否与规则里的表达式一致）。指标名对不上时，告警规则语法正确却永不触发——比没有监控更糟。

### 2.1.8 支付 / 订单 / 权益对账

支付链路是「回调落流水 → 验签 → 置订单已支付 → 发权益」的多步写，单次回调的事务覆盖不了跨步骤失败，这类不一致在业务上表现为「钱到了、权益没到」，只能等用户投诉才暴露。

```bash
# 人工触发一次（受审计，action=TRADE_RECON_RUN）
curl -X POST http://localhost:8080/api/v1/admin/reconciliation/runs -H "Authorization: Bearer <admin token>"
# 读取最近一次结果（异常明细含处置状态）
curl http://localhost:8080/api/v1/admin/reconciliation/runs/latest -H "Authorization: Bearer <admin token>"
# 处置一条异常（受审计，action=TRADE_RECON_HANDLE）
curl -X POST "http://localhost:8080/api/v1/admin/reconciliation/anomalies/123/handle?status=HANDLED&remark=已核对并补发权益" \
  -H "Authorization: Bearer <admin token>"
# 跨轮次待处置池（分页，可按处置状态 / 业务类型过滤）
curl "http://localhost:8080/api/v1/admin/reconciliation/anomalies?handleStatus=PENDING&pageNo=1&pageSize=10" \
  -H "Authorization: Bearer <admin token>"
# 导出给财务的 CSV（带 BOM，Excel 直接打开不乱码）
curl "http://localhost:8080/api/v1/admin/reconciliation/anomalies/export?handleStatus=PENDING" \
  -H "Authorization: Bearer <admin token>" -o recon-anomalies.csv
```

两个视角分工明确，不能互相替代：`/runs/latest` 回答「这一轮查出什么」，`/anomalies` 回答「平台上现在还有多少笔没对上」。后者必须跨轮次——上一轮检出、这一轮因数据变动没再检出的问题（数据变了但没人修），只在这个视角里可见。导出上限 5000 条（一份交给财务的清单不该有几十万行，超出按月分次导）。

检出的五类不一致：


| 异常类型                                  | 含义                                                  |
| ------------------------------------- | --------------------------------------------------- |
| `ORDER_PAID_WITHOUT_ENTITLEMENT`      | 订单已支付，但没有权益发放流水                                     |
| `ORDER_COMPLETED_WITHOUT_ENTITLEMENT` | 订单已完成，却没有权益流水                                       |
| `PAYMENT_PAID_ORDER_NOT_PAID`         | 支付单已成功，订单仍是待支付 / 已关闭                                |
| `PAYMENT_AMOUNT_MISMATCH`             | 支付金额与订单应付不一致                                        |
| `ACCOUNT_LEDGER_DRIFT`                | 权益账户余额与流水累计不一致（不变量 `total - used = Σchange_amount`） |


三个刻意的边界：

1. **不自动修复**：涉及资金与权益，自动补偿做错一次就是把错账写进账本，首期只检出 + 留痕（`trade_recon_report` / `trade_recon_anomaly`）；
2. **留宽限期**（`app.reconciliation.paid-grace-minutes: 10`）：回调后几秒内「已支付但无权益」是正常的，不排除就会每次误报一批刚付款的订单；
3. **单类异常封顶**：异常明细用 `LIMIT` 封顶而不是全量落库，避免一次故障把明细表写爆。

定时任务每天 02:00 逐租户执行（任务线程无登录态，必须显式逐租户切换 `UserContext`，否则会用平台租户口径得出错误结论）。

**检出之后要有闭环**，否则第二天对账原样再报一遍，分不清是「新问题」还是「上次那批没人管」：

- 每条异常带处置状态：`PENDING`（待处置）/ `HANDLED`（已处理）/ `IGNORED`（人工核对后确认误报，误报率用于后续优化规则）；
- 状态**单向流转**，重复处置直接返回 `TRADE_RECON_ANOMALY_ALREADY_HANDLED` 而不是覆盖——两个人同时看同一批异常时，后者覆盖前者会让「谁修了什么」彻底失真；
- 处置按异常明细（而非业务对象）做，天然按报告轮次闭环：下一轮对账若还能检出，是一条新的待处理记录；
- 处置必须写处置说明（修复方式或误报原因）并入审计，事后复盘靠它判断「到底改了什么」；
- 检出即打点：`app_recon_anomaly_total{type}` 进告警规则，修复后下一轮对账干净、告警自然停止，不需要人工消警。

运营入口：后台「对账中心」页（立即对账 / 待处置筛选 / 逐条标记）。

### 2.2 运营后台

```bash
cd admin-web
npm install
npm run dev          # http://localhost:5173 ，/api 已代理到 8080
```

登录账号：`admin / admin123`

页面：工作台、考试项目（含科目抽屉）、题库管理、试卷管理、考试场次（发布/关闭/监控/强制收卷）、订单与商品、**对账中心（立即对账 / 待处置筛选 / 逐条标记已处理或确认误报）**、用户管理。

### 2.3 微信小程序

1. 微信开发者工具「导入项目」，目录选 `miniprogram/`，AppID 选「测试号」；
2. 详情 → 本地设置 → 勾选 **不校验合法域名、web-view（业务域名）、TLS 版本以及 HTTPS 证书**；
3. 后端地址在 `miniprogram/config.js` 的 `baseUrl`（默认 `http://localhost:8080/api/v1`）。

`config.js` 里 `mockLogin: true` 时会用固定 `devCode` 换 openid，保证反复进入仍是同一个考生账号；接真实微信登录时置为 `false`（后端需实现 `jscode2session`）。

演示数据里的体验考生账号：`student / 123456`（小程序走微信登录，此账号仅供接口调试）。

### 2.4 本地联调启动（本项目当前使用的端口与配置）

上面的 8080 / H2 是「开箱即跑」的默认口径；日常联调用的是 **8090 + MySQL 持久库**，三者端口对齐如下：

| 服务 | 地址 | 配置 |
| --- | --- | --- |
| 后端 `backend/` | `http://localhost:8090/api` | `spring-boot.run.profiles=mysql`，库 `127.0.0.1:3306/exam_platform` |
| 运营后台 `admin-web/` | `http://localhost:5173` | `/api` 代理目标需从 8080 改成 8090（见 `vite.config.ts`） |
| 小程序 `miniprogram/` | `baseUrl: http://localhost:8090/api/v1` | 见 `config.js` |

启动后端（MySQL 模式，PowerShell；写成单行以避免续行拼接问题）：

```powershell
cd backend
$env:MYSQL_PASSWORD='<本机 MySQL 密码>'   # 库/账号默认值见 application-mysql.yml
mvn -B -ntp spring-boot:run "-Dspring-boot.run.profiles=mysql" "-Dspring-boot.run.arguments=--server.port=8090"
```

启动成功的判据（约 15s；首次需编译 303 个源文件，会更久）：

```text
The following 1 profile is active: "mysql"
Successfully validated 22 migrations (…)   Schema `exam_platform` is up to date. No migration necessary.
Tomcat started on port 8090 (http) with context path '/api'
Started ExamPlatformApplication in ~15s
演示数据初始化完成
```

就绪后的接口自检（不启动小程序也能验通链路）：

```powershell
# 1) 登录拿 token
$w = Invoke-RestMethod -Uri 'http://localhost:8090/api/v1/auth/login' -Method POST -ContentType 'application/json' -Body '{"username":"student","password":"123456"}'
$h = @{ Authorization = 'Bearer ' + $w.data.token }

# 2) 关键读接口（预期：项目列表有数据、收藏含 difficulty/answerJson、错题本可返回）
Invoke-RestMethod -Uri 'http://localhost:8090/api/v1/projects?pageNo=1&pageSize=3' -Headers $h
Invoke-RestMethod -Uri 'http://localhost:8090/api/v1/favorites?pageNo=1&pageSize=10' -Headers $h
Invoke-RestMethod -Uri 'http://localhost:8090/api/v1/wrong-questions' -Headers $h
```

**重启流程**（改了后端代码必须重启才生效）：确认端口占用 → 停旧进程 → 起新进程 → 等监听 → 接口自检。

```powershell
$t = Get-NetTCPConnection -LocalPort 8090 -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
if ($t) { Stop-Process -Id $t.OwningProcess -Force }
Start-Sleep -Seconds 3
# 再执行上面的 mvn spring-boot:run，等到 "Tomcat started on port 8090" 后做接口自检
```

> 已知问题：`tools/start-backend.ps1` / `.bat` 的注释段存在 GBK 与 UTF-8 混存，Windows PowerShell 5.1 解析时报 `The string is missing the terminator`。暂以**上面的直接 `mvn` 命令**为准；脚本需整体重存为 UTF-8 才能恢复使用。

---



## 3. 已实现的核心能力（对应验收点）


| 文档要求            | 本工程实现                                                                                                                                                                    |
| --------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| 统一响应体 / traceId | `common/api/Result`、`TraceIdFilter`（响应头 `X-Trace-Id`）                                                                                                                    |
| 错误码前缀           | `AUTH_` `TENANT_` `EXAM_` `QUESTION_` `ORDER_` `PAY_` `ENTITLEMENT_` 等，见各 Service                                                                                        |
| 权限与租户隔离         | JWT 鉴权拦截器 + `/v1/admin/**` 要求平台管理员、`/v1/org/**` 要求机构管理员；**MyBatis-Plus 租户插件在 SQL 层自动追加** `tenant_id`，客户端传入的 tenant_id 一律不被采信                                             |
| 微信登录            | `POST /v1/auth/wechat-login`，openid 只作渠道标识，平台建自身用户主键                                                                                                                     |
| 项目/科目/题库        | 完整 CRUD，题目选项与答案以 JSON 存储，修改递增 `version`                                                                                                                         |
| 试卷与快照           | `exam_paper` 定义 + 发布时生成 `exam_paper_snapshot`（含题干/选项/答案/分值 + SHA-256 内容哈希，读取时校验完整性）；支持固定组卷与**规则组卷**（按题型/难度随机抽题、去重、题库不足可读报错、可试组卷预览）                                       |
| 场次与报名           | 发布后规则锁定；报名校验场次状态、时间窗、名额、重复报名（唯一约束兜底）                                                                                                                                     |
| 在线考试            | 服务端下发 `started_at` / `deadline_at`；答案保存按 `(attempt_id, question_id)` 唯一 + 版本号防旧覆盖；`resume` 以服务端答案为准；不返回标准答案                                                              |
| 交卷与判分           | 带状态条件的原子更新 + 结果表唯一约束 → 重复交卷不重复判分；客观题自动判分，成绩可追溯到快照版本                                                                                                                      |
| 主观题与人工阅卷        | 交卷后含主观题 → `REVIEWING`（考生只看到客观题分，明细不可见）；逐题打分、评语、结束阅卷后成绩生效；**成绩更正必须填原因并写入** `exam_result_history` **保留历史版本**                                                               |
| 自动交卷            | `AutoSubmitJob` 定时扫描到期考卷，与主动交卷共用同一状态机                                                                                                                                    |
| 交易与支付           | 金额全部以「分」存 `BIGINT`；价格取服务端商品快照；支付回调先落流水再验签+核对金额/币种/状态；按 `transaction_id` 幂等发权益                                                                                            |
| 权益              | `entitlement_account` + `entitlement_ledger`，唯一键 `(tenant_id, event_id, entitlement_type, resource_id)` 保证重复回调不重复发放；状态机 AVAILABLE → PART_USED → USED / REVOKED / EXPIRED |
| 退款              | 申请 → 运营审核 → 退款成功则订单置 `REFUNDED` 并回收未使用权益（`REVOKE` 流水），重复审核幂等                                                                                                             |
| 消息              | `msg_task` + `msg_send_log` + `msg_subscription`：业务事件先落任务再投递，未授权置 `SKIPPED`、失败按退避重试、管理员可手动重发                                                                             |
| 审计              | 管理端写操作以 `@Audited` 注解声明式记录 `sys_audit_log`；覆盖率由 `AuditCoverageTest` 静态扫描守护，新增写接口漏注解会直接测试失败（见 2.1.4）                                                                      |




### 关键链路（已通过实际接口冒烟验证）

```text
微信登录 → 报名 → 开始考试 → 逐题保存答案（幂等 + 版本） → 恢复考试 → 交卷 → 判分 → 成绩明细
选购商品 → 创建订单（服务端计价） → 创建支付单 → 支付回调（验签/金额/币种） → 幂等发权益
```

---



## 4. 相对文档的简化点与演进路径


| 简化                                 | 原因                                                                              | 演进                                                                 |
| ---------------------------------- | ------------------------------------------------------------------------------- | ------------------------------------------------------------------ |
| 单体而非多服务                            | 文档允许的渐进式方案，减少运维与联调成本                                                            | 按 `modules/*` 拆包 ⇒ 独立工程，网关前置                                       |
| session / 热点缓存未上 Redis，MQ 未引入      | 首期无跨实例共享状态；支付事件同步事务内处理。限流已提供 Redis 后端（`app.rate-limit.backend=redis`），单机默认走进程内桶 | 会话与热点缓存接 Redis；RocketMQ 承载支付成功事件 → 权益异步发放                          |
| RBAC 用 `sys_user.role_code` 单角色    | 首期角色只有平台管理员 / 机构管理员 / 考生三种，够用                                                   | 增加 `sys_role`、`sys_user_role`、`sys_role_permission`，接口层无需改动        |
| 退款仅支持整单全额                          | 首期「整单退款 + 未使用权益作废」已闭环                                                           | 按订单明细拆单、部分退款、退款回调与对账                                               |
| 消息真实通道未接                           | 首期用本地模拟通道，接口契约与真实一致                                                             | `app.message.mock=false` 时缺少 `MessageChannel` 实现会直接启动失败，避免线上误用模拟通道 |
| 课程仅支持图文课时                          | 未接入点播平台与防盗链                                                                     | `course_lesson.video_url` 已预留，接入时补签名播放地址                           |
| 批量授权按组织维度发放，未接入采购单对账               | 首期以「批次号 + 审计日志」追溯                                                               | 增加采购单实体，批次与订单/合同关联                                                 |
| 机构侧无「组织级数据可见范围」控制                  | 机构门户只做「看自己机构 + 给自己的人发权益」，机构成员的业务数据仍在租户内可见                                       | 数据可见范围由角色 + 组织双维度控制                                                |
| 业务时区固定 Asia/Shanghai，`DATETIME` 存储 | 国内单区域业务，降低复杂度                                                                   | 全链路改 UTC + 前端按用户时区渲染                                               |
| 规则组卷未支持知识点范围与难度配比                  | 首期按题型 + 可选难度抽题已够用                                                               | 规则表增加 `knowledge_point_id` 与按比例抽题算法                                |
| 消息队列/outbox 未落库                    | 支付与权益同一事务，无跨服务一致性问题                                                             | 拆服务后必须引入本地消息表 + 重试补偿                                               |
| 定时任务以平台租户上下文运行                     | 单租户部署                                                                           | 多租户部署时按租户遍历执行                                                      |
| 判分仅客观题                             | 主观题阅卷属 P1                                                                       | 增加 `REVIEWING` 分支、人工阅卷与成绩更正留痕                                      |


---



## 5. 接口速查（Base URL `/api/v1`）

免登录：`POST /auth/login`（`{username, password, tenantCode?}`，`tenantCode` 缺省为 `PLATFORM`）、`POST /auth/wechat-login`、`POST /payments/wechat/notify`、`POST /dev/payments/{payNo}/mock-notify`（仅 mock 模式）。

考生端：

```text
GET  /projects                          GET  /projects/{id}/subjects
POST /practice/sessions                 POST /practice/sessions/{id}/answers?questionId=
GET  /wrong-questions
GET  /exam-sessions                     GET  /exam-sessions/{id}/registration
POST /exam-sessions/{id}/registrations  POST /exam-sessions/{id}/attempts
GET  /attempts/{id}/resume              PUT  /attempts/{id}/answers/{questionId}
POST /attempts/{id}/submit              GET  /attempts/{id}/result      GET /attempts
GET  /products                          POST /orders                    GET /orders
POST /orders/{id}/payments              POST /orders/{id}/refunds       GET  /refunds
GET  /entitlements                      GET  /entitlements/ledger
POST /entitlements/{id}/consume?amount=&requestId=
POST /messages/subscriptions            GET  /messages/subscriptions     GET  /messages
GET  /courses                           GET  /courses/{id}
GET  /courses/{id}/lessons/{lessonId}   POST /courses/{id}/lessons/{lessonId}/progress
GET  /users/me                          PUT  /users/me
```

运营后台（`/v1/admin/**`，需管理员角色）：

```text
GET/POST/PUT/DELETE  /projects, /subjects, /knowledge-points
GET/POST/PUT/DELETE  /questions  (+ PUT /questions/{id}/status, POST /questions/imports, GET /questions/imports/template)
GET/POST/PUT/DELETE  /papers     (+ POST /papers/{id}/snapshots | preview, GET /papers/{id}/rules)
GET/POST/PUT/DELETE  /sessions   (+ POST /sessions/{id}/publish|close)
GET  /sessions/{id}/registrations    GET  /sessions/{id}/attempts
POST /attempts/{id}/force-submit
GET  /marking/queue                      GET  /attempts/{id}/marking      PUT /attempts/{id}/marking/{questionId}
POST /attempts/{id}/marking/finish       POST /attempts/{id}/results/correct
GET  /attempts/{id}/results              GET  /attempts/{id}/results/history
GET/POST/PUT/DELETE  /products       GET /orders
GET  /users  (+ PUT /users/{id}/status|role)
GET  /refunds (+ POST /refunds/{id}/approve|reject)
GET  /orgs  (+ GET/POST/DELETE /orgs/{id}/members, POST /orgs/{id}/grants, GET/POST /orgs/{id}/quotas, GET /orgs/{id}/quota-recharges, GET /orgs/expired-quotas)
GET  /messages (+ GET /messages/{id}/logs, POST /messages/{id}/resend, GET/PUT /messages/templates)
POST /exports  (+ GET /exports, GET /exports/{id}, GET /exports/{id}/download)
GET  /audit-logs  (+ GET /audit-logs/actions)
GET  /reconciliation/runs/latest (+ POST /runs, GET /runs/recent)
POST /reconciliation/anomalies/{id}/handle  GET /reconciliation/anomalies(+ /anomalies/export)
GET  /dashboard/overview
```

机构侧门户（`/v1/org/**`，需 `ORG_ADMIN` 角色；只能操作自己所属的机构，服务端每次重查成员关系）：

```text
GET  /my-orgs
GET  /orgs/{orgId}/profile              GET  /orgs/{orgId}/members
POST /orgs/{orgId}/grants               GET  /orgs/{orgId}/quotas
GET  /orgs/{orgId}/quota-recharges
```

机构侧的自助发放动作码是 `ORG_PORTAL_GRANT`，与平台代发的 `ORG_GRANT` 分开——事后查审计能直接看出「这一批是谁发的」。

**机构额度池**：平台按 `(机构, 权益类型, 资源)` 三维记账（`POST/GET /v1/admin/orgs/{id}/quotas`，`resourceId` 缺省表示该类型通用额度）。机构一旦有账户，发放就按「买多少发多少」执行：

- 余额判断与扣减在**同一条 UPDATE** 里完成（`total - used >= amount`），并发发放不会双花；
- 余额不足**整批拒绝**（不半途发放），发放中被跳过的成员额度**退回**；
- **没有账户的机构行为完全不变**——额度池是给已签约采购的机构用的可选能力，不是给所有机构套的新门槛。这一条由测试守着（`shouldGrantWithoutQuota`）。

额度可设有效期（`expireAt`，空表示长期有效，V7 迁移加列）。两个刻意的约定：

- **续充只能把期限往后推**：更早的日期接口直接拒绝，传空则保留原期限——「这次忘了填期限」不能悄悄变成「永久有效」；
- **过期账户不会被当成「无账户」放行**：有账户但过期时抛 `ORG_QUOTA_EXPIRED`，否则额度一到期就等于绕过了限额。

**充值流水与过期报表**：每笔充值落一行结构化流水（`sys_org_quota_recharge`，`GET /orgs/{id}/quota-recharges`），运营拿采购单/合同对账时不用再去翻审计正文。流水是「明细」，账户上的 `total_amount` 是「汇总」，**两者之和必须相等**——测试直接断言这一点，因为对不上就意味着有笔充值没落流水、或有人手工改过账户。

过期账户**不删除也不清零**，只在报表里列出来（`GET /orgs/expired-quotas`，带未用余额）。删掉账户会让机构退化成「没有额度账户」，而那在当前实现里等于不限额——所以「过期」只影响还能不能继续消耗，账户本身留着供追溯。

**额度对账（两层）**：发放侧也有结构化流水（`sys_org_quota_consume`，一次批量发放一行，含批次号、扣减额、退款额）。

- **第一层（账户级）**：账户 `used_amount`（汇总）= 流水净额（扣减 − 退款，明细）。抓「扣减/退款与记账不同步」；
- **第二层（批次级）**：流水净额 = `entitlement_ledger` 里该批次的**实际入账**金额。抓「钱扣了、权益没发到成员手上」——此时第一层完全一致，只看第一层永远发现不了。

每日 04:00 的对账任务（`app.org-quota.recon-cron`）两层都跑，**任一层不一致都按「失败」进定时任务健康告警**——额度对不上可能是少发（机构来投诉）也可能多发（平台亏钱），两种都需要人看。批次号与 `entitlement_ledger.event_id` 的前缀对齐，出问题时能从任一侧追到另一侧。

两层的查询范围**故意不同**，这一点值得单独说明：


|          | 查询范围                             | 原因                                                                                        |
| -------- | -------------------------------- | ----------------------------------------------------------------------------------------- |
| 第一层（账户级） | 全量，但只发一条 `GROUP BY` 聚合           | `used_amount` 是累计值，加时间窗会把「窗口之前的消耗」全算成差异，产出一堆假告警；而「列账户 + 逐个 SUM」是 N+1（1000 个账户 = 1001 次查询） |
| 第二层（批次级） | 最近 `recon-lookback-days`（默认 7 天） | 每行流水写入后不再变化，天然可增量核查。台账只增不删，全表扫迟早撞上 `default-statement-timeout`——**超时的对账等于没对账**            |


窗口必须大于任务周期：窗口小于执行间隔时，两次执行之间发生的发放就永远没人核。时间窗两侧留 1 小时余量，避免同事务内「先写台账、后写流水」的批次刚好卡在边界时被截断成假差异。

窗口不是「写了就算」的——它有专门的边界用例守着：同一批 30 天前的数据，`reconcileBatches(7)` 必须看不见、`reconcileBatches(60)` 必须抓到；台账比窗口起点早 30 分钟要能匹配上，早 90 分钟（超出余量）则仍然要报差异。**余量是补偿同事务写入抖动的，不是「顺手扫全表」的借口**，这两条用例同时钉住了两端。

运维还可在「机构 / 企业」页手动触发一次对账（`GET /orgs/quota-recon`，`fullScan=true` 为不限窗口的全量核对），不必等到凌晨——机构投诉「我的额度怎么少了」时当场就能给答案。全量核对只用于怀疑历史遗留问题：台账只增不删，全表扫可能撞上语句超时，而**「对账超时」比「对账报错」更糟**，它会让人以为核过了没问题。

平台运营侧在「机构 / 企业」页有「过期额度」入口（`GET /orgs/expired-quotas`），能直接看到「哪些机构的额度作废了多少」；点「成员 / 授权」抽屉还能看到该机构的额度账户（累计充值 / 已发放 / 剩余 / 有效期）与充值流水。机构侧则在门户的「额度账单 / 充值流水」里看自己的账面。

**消息模板**：标题与正文可配置（`msg_template` + `GET/PUT /v1/admin/messages/templates/{code}`），正文用 `{键}` 引用消息 payload。取值顺序是「库里配的 → 代码兜底 → 放弃渲染」，所以升级后新增编码、或新租户还没配模板时，消息都照常发得出去。缓存的是**模板原文**（按租户隔离、改文案即时失效、另有 60 秒 TTL 兜底多实例），不是渲染结果——缓存渲染结果会让所有人的消息变成同一个人的内容。

---



## 6. 小程序端界面（考生端页面清单与改版记录）

`miniprogram/` 共 18 个页面，全部完成了一轮视觉与交互改版。统一设计语言：**蓝紫渐变页头（`#1d4ed8 → #4f46e5 → #7c3aed`）+ 24rpx 圆角白卡轻投影 + 胶囊分段切换 + 渐变主按钮 + 底部安全区留白**。所有图标均为内联 SVG（base64 data URI），不依赖图片资源。

| 页面 | 路径 | 主要能力 |
| --- | --- | --- |
| 首页 | `pages/index/index` | Hero + 快捷宫格 + 推荐项目卡 |
| 项目详情 | `pages/project/detail` | 封面 Hero、考试安排、价格与权益卡、科目五宫格练习入口、知识点 chips、底部固定操作栏 |
| 练习答题 | `pages/practice/practice` | 顶部状态条（模式/进度/模拟剩余时间）、收藏本题、判分结果卡、离线答案队列 |
| 学习记录 | `pages/practice/history` | 渐变统计头（练习次数/累计答题/正确率）、按日期分组（今天/昨天/日期）、正确率进度条、按科目筛选 chips、分页加载 |
| 考试中心 | `pages/exam/list` | 场次卡（考试时间/报名截止/时长三行）、报名状态徽标、准考证号 |
| 答题中 | `pages/exam/taking` | 吸顶信息条 + 题号导航、红色倒计时胶囊、底部固定上一题/下一题/交卷 |
| 成绩单 | `pages/exam/result` | Hero 得分卡（得分率进度条、场次排名）、全部/仅错题筛选、逐题判定与解析、底部固定操作栏 |
| 课程列表 / 详情 | `pages/course/list`、`pages/course/detail` | 课程卡、课时目录（试看/未解锁/已完成）、标记已学完 |
| 商城 | `pages/mall/index` | 领券横幅、商品卡（价格 / 划线原价 / 立即购买） |
| 我的 | `pages/my/index` | 渐变头部 + 上浮统计卡（考试/错题/收藏 99+/消息/权益）、继续练习进度卡、功能菜单组、权益卡、考试记录 |
| 公告 | `pages/notice/index` | 置顶徽标、展开收起与箭头旋转动效 |
| 错题本 | `pages/wrong/index` | 来源/科目双维筛选、答案与解析展开、重做此题（本地判分） |
| 收藏 | `pages/favorite/index` | 展开查看正确答案与解析、重做此题、取消收藏 |
| 消息 | `pages/message/index` | 未读红点 + 左侧蓝条、全部已读、分页 |
| 订单 | `pages/order/index` | 订单 / 退款双段切换、支付与退款入口 |
| 优惠券 | `pages/coupon/index` | 票券造型（两侧圆缺口 + 虚线撕口）、领券中心 / 我的券 |
| 密码 | `pages/password/index` | 账号密码设置表单 |

### 6.1 「我的」页的权益与考试记录（两块重点）

这两块的原始形态是「后端枚举直出」：权益卡显示 `entitlementType`、`status` 原值与订单号，考生看不懂；考试记录把所有历史全铺在页面上，且得分藏在文案里。

现在的处理：

- **权益卡**改为三栏——左侧类型图标块（会/题/课/考，按类型配色）、中间中文类型名 + 状态胶囊（生效中/已过期）+ 来源订单与格式化有效期、右侧「剩余额度」数字块。类型与状态在 `my/index.js` 的 `mapEntitlement()` 里统一翻译（`MEMBER/QUESTION_BANK/COURSE/EXAM`、`ACTIVE/EXPIRED/USED_UP`），未知枚举值原样回显兜底，不再出现空白。整卡可点直达商城续购；区块头另有「去开通 ›」，空态给引导按钮。
- **考试记录**只渲染最近 3 条（`latestAttempts`），左侧得分方块把分数前置（进行中显示 `--`），右侧「查看 ›」胶囊可直接进成绩页；区块头「全部 N 条 ›」跳考试中心，空态给「去考试中心」按钮。

### 6.2 本轮修复的两处功能缺陷（不只是样式）

| 缺陷 | 根因 | 修复 |
| --- | --- | --- |
| 错题本「重做此题」选对也判错 | 标准答案取自练习详情接口 `GET /questions/{id}`，而后端 `QuestionView` **按设计不返回答案**，`answer` 恒为空数组，判分两边永远不相等 | 标准答案改从错题列表行的 `correctAnswer` 反查；比较前统一 `trim + toUpperCase` 归一；答案缺失时显式提示「无法判分」，不再静默判错 |
| 收藏题「点击无反应」 | 收藏卡只有「取消收藏」一个动作，没有查看答案/解析或重做的入口；且后端 `pageFavorites` 的 SQL 未返回答案与解析字段 | 后端补 `q.difficulty` / `q.answer_json` / `q.analysis` 三列；前端收藏卡支持展开答案与解析、重做此题（同款本地判分）、取消收藏（改 `catchtap` 防误触） |

修复后 `mvn test` 仍为 `Tests run: 250, Failures: 0, Errors: 0, Skipped: 8`。

---

## 7. 数据模型

DDL 见 `backend/src/main/resources/db/migration/V1__baseline.sql`（Flyway 版本化迁移，表名与字段严格对齐文档第 5、6 节）：

`sys_tenant` `sys_user` `sys_user_identity` `sys_audit_log` `exam_project` `exam_subject` `exam_knowledge_point`
`qb_question` `exam_paper` `exam_paper_snapshot` `exam_session` `exam_registration` `exam_attempt` `exam_answer`
`exam_result` `learn_practice` `learn_practice_answer` `mall_product` `trade_order` `trade_payment`
`trade_payment_notify` `trade_refund` `entitlement_account` `entitlement_ledger`
`sys_org` `sys_org_member` `course_info` `course_lesson` `course_progress`
`msg_task` `msg_send_log` `msg_subscription` `exam_marking` `exam_result_history` `exam_paper_rule` `export_task`

要点：

- 业务表统一 `id` / `tenant_id` / `created_at` / `updated_at` / `deleted`；
- 金额用 `BIGINT` 存分，不用浮点；
- 唯一键均含 `tenant_id`（如 `(tenant_id, order_no)`、`(tenant_id, attempt_id, question_id)`）；
- 腾讯试题、试卷、商品价格均以快照/版本方式固定历史数据。

---



## 8. 自测



### 8.1 自动化测试（179 个用例，`mvn test` 全绿）

```bash
cd backend && mvn test
```


| 测试类                             | 覆盖点                                                                                                                                                                                                                                  |
| ------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `AuthAndTenantTest`（6）          | 管理员登录与资料、未登录拦截、密码错误不泄漏账号存在性、考生越权访问后台、**租户数据隔离 + 账号名按租户隔离**、未知租户拒绝                                                                                                                                                                    |
| `ExamFlowTest`（3）               | 报名/开考幂等、答案版本控制（旧请求不覆盖）、恢复考试以服务端为准、交卷幂等、快照判分、交卷后禁止作答、未报名不能开考、重复发布拦截、**到期自动收卷**                                                                                                                                                        |
| `EssayMarkingTest`（1）           | 含主观题交卷进 `REVIEWING`、客观题先判分且明细不可见、未评分不得结案、超分拦截、打分后发布、**成绩更正留痕与版本号递增**                                                                                                                                                                    |
| `RulePaperTest`（4）              | 规则组卷按题型抽题、无重复题、总分符合规则、题库不足报错、重复规则拦截、发布场次后快照固定                                                                                                                                                                                        |
| `CourseFlowTest`（4）             | 免费课程直接可看、**付费课程未购买时锁定内容（含直接请求课时接口）**、购买后经支付回调发放权益解锁、学习进度以较大值合并不回退、考生只见已发布课程                                                                                                                                                          |
| `MessageFlowTest`（4）            | 未授权任务置 `SKIPPED` 且不影响报名、补授权后可重发并扣减次数、已发送不可重发、重复业务事件不重复建任务、支付与成绩消息各自独立、管理端按状态/场景筛选                                                                                                                                                    |
| `OrgGrantTest`（4）               | **按组织维度批量发放权益**、重复提交按批次号叠加而非吞掉、指定成员时忽略非本组织成员、停用组织/无成员组织拒绝授权、成员移出后不再被授权、重复加入组织幂等                                                                                                                                                      |
| `ExportFlowTest`（4）             | **异步导出：提交 → 轮询 → 下载 CSV**、BOM 与表头正确、按场次过滤、非法类型拒绝、任务只对提交人可见、跨租户无法下载他人产物                                                                                                                                                               |
| `TradeFlowTest`（2）              | 客户端改价无效、回调验签失败/金额篡改被拒、重复回调只发一次权益                                                                                                                                                                                                     |
| `WechatPayCryptTest`（12）        | **微信支付 v3 对接的纯逻辑层**（自建密钥对，不连网）：按官方规则签名能验过、改一个字节就验不过、超窗时间戳被拒（防重放）、AAD 篡改导致解密失败、v3 回调「验签→解密→归属校验」全链路、**他商户的回调被拒**（验签只证明来自微信，不证明是我们的单）、未知平台证书显式失败而非静默放行、拉证书失败被显式上报 |
| `RefundFlowTest`（2）             | 退款申请与重复申请拦截、**审核通过后订单退款 + 剩余权益作废**、重复审核幂等、驳回后订单恢复可用、未支付订单不可退款、权益核销幂等与不足拦截                                                                                                                                                            |
| `AnswerJudgeTest`（6）            | 判分规则：顺序无关、大小写与空格归一、漏选/多选不得分、空答案不得分                                                                                                                                                                                                   |
| `ReconciliationTest`（6）         | 对账按 biz_id 精确检出五类不一致、宽限期内的新订单不误报、重复对账保留历史、**异常处置闭环**（标记已处理带处置人/说明、重复处置拦截、非法状态拦截、审计留痕）、**检出即进指标**                                                                                                                                     |
| `QuestionImportTest`（5）         | 题目批量导入：部分行失败整批回滚、成功批次写入审计                                                                                                                                                                                                            |
| `TimeoutProtectionTest`（3）      | 语句超时 / 取连接超时被归类为可重试错误，而非「服务开小差」                                                                                                                                                                                                      |
| `PrometheusMetricsTest`（2）      | 抓取端点的真实输出与告警规则里的指标名、标签一致                                                                                                                                                                                                             |
| `AuditLogQueryTest`（5）          | **审计从「只能写」变成「能查」**：按动作精确 / 操作人模糊 / 日期区间过滤都能命中，考生访问返回 `AUTH_FORBIDDEN`                                                                                                                                                                |
| `JobHealthRecorderTest`（5）      | **定时任务健康**：成功即清零连续失败、失败累加、从未成功时 staleness 随进程运行时长增长、未登记的任务上报时不抛异常                                                                                                                                                                    |
| `ReconAnomalyPoolTest`（4）       | **跨轮次待处置池**：处置后从「待处置」消失并出现在「已处理」、按业务类型过滤不混入、导出 CSV 带 BOM 与表头、空池只输出表头                                                                                                                                                                 |
| `ExportRateLimitTest`（1）        | 导出 / 下载走独立更严配额：配额内可下载，超限返回 `RATE_LIMITED` + `Retry-After`，且该路径不会被 `default` 规则吞掉                                                                                                                                                     |
| `AuditArchiveTest`（3）           | **审计保留期**：超期记录写进 CSV 后才从库中删除、近期记录一条不少、跨租户旧记录也被归档、开关关闭或保留期为 0 时整体跳过                                                                                                                                                                   |
| `OrgPortalTest`（3）              | **机构侧门户**：机构管理员能看到自己所属机构与成员、自助发放留审计（动作码与平台代发区分）、请求体里的机构 ID 被丢弃、访问别人家机构返回 `ORG_NOT_MEMBER`、考生被拒、停用机构拒绝服务                                                                                                                              |
| `OrgQuotaTest`（7）               | **机构额度池**：充值 100、发 3 人后余额 97；余额不足整批拒绝且一分不扣；没有额度账户的机构仍按旧逻辑发放；**过期账户拒绝发放且不退化成「不限额」**；续充只能把有效期往后推（更早日期直接拒）；**充值落流水且流水合计等于账户累计**；**过期报表列出作废余额、长期有效账户不进报表**                                                                               |
| `OrgQuotaReconTest`（10）         | **额度对账两层**：发放后账户已用等于流水净额；流水净额扣掉退款（扣 6 退 2 → 净 4 才算消耗）；账户余额被手工改动时检出差异并给出 `diff`；**删掉一条入账后台张级对账检出「钱扣了但权益没到账」，而账户级对此完全无感**；**时间窗边界**（同批数据 7 天窗口看不见、60 天能抓到）；**余量有界**（台账早 30 分钟能匹配、早 90 分钟仍报差异）；账户级不加时间窗；对账报告接口回显扫描窗口；全量核对能回答「是不是历史上一直有」 |
| `OrgQuotaReconJobTest`（2）       | **对账告警不能只进日志**：两层一致时连续失败清零；账户被改坏时**连续失败次数递增**（能被 `app.job.consecutive.failures` 抓到），而不是打一行 `log.warn` 就过去                                                                                                                            |
| `MessageTemplateTest`（5）        | **消息模板可配置**：运营改文案后渲染立刻生效、未配置编码用代码兜底、模板停用回退兜底、未知占位符原样保留便于定位写错键、**缓存的是模板原文（同一模板对不同 payload 渲染各自内容）**、改文案留审计                                                                                                                            |
| `QuestionImportTemplateTest`（1） | **导入模板自证一致**：下载的模板原样喂回导入接口能成功（模板与解析器不漂移），表头带 BOM 可被 Excel 直接打开                                                                                                                                                                       |


均为真实启动 Web 容器的接口级测试（随机端口 + H2），覆盖 Servlet → 拦截器 → 数据库全链路。

**每个测试方法前都会重建数据库**（`DROP ALL OBJECTS` → 重跑 Flyway → 重新初始化演示数据）。H2 连接串带 `DB_CLOSE_DELAY=-1`，被缓存的多个 Spring 上下文共用同一个库；不重建的话，「断言总数」「断言列表第一条」这类用例能不能绿取决于执行顺序——单独跑绿、全套跑红，或者反过来。代价是「跨方法共享数据」的写法不再被纵容，这正是要消除的耦合。

**Redis 原子性验证（CI 必跑，本地可选）**：`RedisRateLimiterIntegrationTest`（5 个用例）用真实 Redis 验证打桩测不出的四件事——并发取令牌不超发、按服务端时间补充、桶 key 的 TTL、多实例共享同一 key 时配额不按实例数放大。连不上 Redis 时自动 `skipped`（不红构建），配置后自动生效：

```bash
cd backend
APP_TEST_REDIS_HOST=127.0.0.1 APP_TEST_REDIS_PORT=6379 \
APP_TEST_REDIS_PASSWORD=<密码，无密码则不填> APP_TEST_REDIS_DB=15 \
mvn test -Dtest=RedisRateLimiterIntegrationTest
```

`-Dtest=` 单跑只能说明「你本机连上了」；**CI 里这个 Job 会起 `redis:7-alpine` service 并要求这 5 条用例的 `skipped="0"`**——否则「构建绿」只代表这四条性质从未被验证过。第一次真正跑起来时就抓到两条一直红的用例，见 §8 说明。

### 8.2 前端契约与一致性校验（无需 Node / JDK）

两个纯静态扫描，先排除「路径写错导致批量 404」和「DTO 字段漂移导致字段恒为 undefined」这类只能靠点页面才发现的问题：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\check-api-contract.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\check-frontend-consistency.ps1
```

Linux / macOS / Git Bash 用等价的 bash 版本（CI 就跑这两个，不依赖 `pwsh`）：

```bash
bash tools/check-api-contract.sh
bash tools/check-frontend-consistency.sh
```

`check-api-contract` 输出示例（失败时退出码为 1）：

```text
backend mappings: 120
admin-web calls: 68, matched: 68, unmatched: 0
miniprogram calls: 22, matched: 22, unmatched: 0
```

注意这两个数字曾经是 `26` 和 `12`：那时 PowerShell 版正则写在双引号字符串里，字符类中的反引号被解析掉，**所有用模板字符串 `` url: `/x/${id}` `` 声明的接口都没被扫到**（68 条里漏了 42 条）。所以「unmatched 0」曾经只是「没检查」，不是「检查过」。两个实现现在都认三种引号，并把占位符统一归一成 `{p}`（后端 `{attemptId}` 与前端 `${attemptId}` 属同一结构，参数名不同不算契约漂移）。

`check-frontend-consistency` 抓三类 `vue-tsc` 抓不到、运行时才炸的问题：页面调用了不存在的 `xxxApi.method`、后端 DTO 字段与前端 interface 漂移（前端多出的字段恒为 `undefined`）、菜单项指向不存在的路由。输出示例：

```text
api 对象: 16 个（auditLogApi, authApi, courseApi, ... userApi）
后端 DTO record: 50 个
视图: 16 个，路由条目: 15 个
FRONTEND CONSISTENCY OK
```



### 8.3 手工回归建议（前端联调时）

```text
1. 小程序：练习 → 断网 → 恢复后可继续作答，答案与服务端一致
2. 小程序：考试倒计时归零 → 自动交卷 → 成绩页展示逐题解析
3. 小程序：答题保存后退出重进 → 恢复到最后保存的题目与答案
4. 管理端：场次发布后修改规则 → 被拒绝（EXAM_SESSION_LOCKED）
5. 管理端：修改题目 → 复查历史成绩里的题面仍是当时快照
6. 管理端：报表导出 → 任务状态自动刷新 → 下载 CSV 可用 Excel 直接打开
7. 支付：真实商户环境下的回调验签与对账
```



### 8.4 静态核对已发现并修复的前端缺陷

前端未实机运行前，通过读代码 + 后端 DTO 对照发现以下问题（均已修复）：


| 问题                                               | 影响                                       | 修复                                        |
| ------------------------------------------------ | ---------------------------------------- | ----------------------------------------- |
| 小程序考试页用 `question.id`，后端返回 `questionId`          | 答题保存时 `questionId` 为 `undefined`，全部答案存不上 | 数据入口统一映射为 `id`，保留 `questionId`            |
| 小程序把整个 `attempt` 对象塞进 `data-attempt`             | 小程序 `dataset` 不支持对象，拿到字符串导致继续考试/查看成绩分支失效 | 改传 `data-status` / `data-attempt-id` 基本类型 |
| 管理端 `request()` 统一拆包，下载接口 `responseType: 'blob'` | 报表下载拿到 `undefined`                       | 二进制响应跳过拆包；失败时把 Blob 读回解析错误码               |




### 8.5 性能基线（答题保存 / 交卷）

集中开考时压力集中在两个瞬间：几百人同时刷新答案、整点同时交卷。它们慢下来不是「页面转圈」——答题保存慢会让考生以为答案丢了反复重试，交卷慢会把收卷队列堆到超时。

```bash
cd backend
mvn test -Dtest=LoadBaselineTest "-Dload.test.enabled=true" \
         "-Dload.test.threads=8" "-Dload.test.saves=25"
# 对已运行的真实环境压（需要一名已开考考生的 token 与 attemptId）
pwsh tools/load-baseline.ps1 -Token "<token>" -AttemptId 123 -QuestionId 456 -Threads 16 -Total 400
```

默认**不跑**：压测数字依赖机器，放进常规构建只让构建变慢且指标无意义。同机同参数的两次结果才可比较，判断标准见 `[doc/06_性能基线与压测方法.md](doc/06_性能基线与压测方法.md)`（含本机实测基线表、p99 余量、以及「H2 上交卷会被串行化，不能据此承诺对外耗时」的说明）。

### 8.6 MySQL 8 迁移演练

日常测试的 H2 兼容模式抹掉的差异恰好是生产首次迁移最容易翻车的地方，所以迁移脚本必须在真 MySQL 上先跑过：

```bash
bash tools/check-mysql-migration.sh                        # Linux / macOS
powershell -File tools/check-mysql-migration.ps1           # Windows（PowerShell 5.1+，需 Docker）
```

CI 每次 PR 都跑同一件事（`mysql-migration` job，直接用 `mvn` 命令，不经过这两个脚本），并断言「重复 `migrate` 是无操作」。没有 Docker 时脚本直接报错退出，不做静默跳过——跳过会让人误以为演练过了。

> 这个脚本此前是**必然失败**的：参数用了「反引号 + LF」续行（PowerShell 5.1 不认），`docker run` 只收到 `run`；修掉后又暴露出两个问题——`$ErrorActionPreference='Stop'` 下 `docker logs` 的 stderr 被当成终止性错误，以及含 `&` 的 JDBC URL 被 `cmd` 当成命令分隔符。三处都已修（详见 `doc/07` 第 2.2 节）。

---



### 8.7 小程序离线答题队列单测

`miniprogram/utils/answer-queue.js` 的语义（按题去重、200 条上限、按场次隔离、失败停止 / 业务错误出队、冲突回调）只依赖 `wx.getStorageSync` / `wx.setStorageSync`，用最小桩就能在 Node 里跑，不需要微信开发者工具、也不需要装任何依赖：

```bash
node --test tools/miniprogram-tests/
```

12 个用例，覆盖 `doc/08` 清单里能自动判定的部分（MP-01/02/03/04/06/08/09/10）。真机只剩「网络事件是否触发」「toast 是否可见」这类必须在真实运行时观察的项。

---



## 9. CI 流水线

`.github/workflows/ci.yml`，共五个 Job，全部为阻断项：


| Job                 | 内容                                                                                                                                                 | 失败是否阻断  |
| ------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------- | ------- |
| `backend-test`      | `mvn -B -ntp test`，跑全部 171 个用例（H2 内存库；本机无 Redis/MySQL 时 `Skipped: 8`，CI 起 Redis 后为 `Skipped: 3`）；**并起一个真实 Redis**（`redis:7-alpine` service），让「只有真 Redis 才能验证」的令牌桶行为（Lua 原子性、补充节奏、TTL、多实例共享）进构建门禁 | 是（阻断合并） |
| `api-contract`      | 两个纯静态扫描（不依赖 Node / JDK / pwsh）：`check-api-contract.sh` 校验前端声明的路径都能在后端找到映射；`check-frontend-consistency.sh` 校验页面调用的 api 方法存在、后端 DTO 字段与前端 interface 不漂移、路由与菜单一致 | 是（阻断合并） |
| `miniprogram-test`  | `node --test tools/miniprogram-tests/`，不装任何依赖（Node 内置 test runner + 最小 `wx` 桩）：离线答题队列的按题去重、200 条上限、按场次隔离、失败停止 / 业务错误出队、冲突回调                       | 是（阻断合并） |
| `mysql-migration`   | 起 `mysql:8.0` service，跑 `MySqlMigrationSmokeTest`：全部迁移脚本（V1..V10）在真 MySQL 上迁移、重复迁移为无操作、关键表与索引（含 V10 的对账索引）真实存在                                     | 是（阻断合并） |
| `admin-web-build`   | `npm ci && npm run build`（`vue-tsc` 类型检查 + Vite 构建）                                                                                                | 是（阻断合并） |


说明：

- **门禁断言的是「真的跑了」，不只是「没有报错」**：`backend-test` 与 `mysql-migration` 各有一段断言，检查对应 Surefire 报告里 `skipped="0"`。这两个用例类连不上中间件时会 abort 成 skipped，如果没有这道断言，「构建绿」只说明它们被跳过了。
- 前端构建不 `continue-on-error`：类型错误会直接让运营后台打包失败，允许它「红着也能过」等于把构建门禁关掉了。`package-lock.json` 已提交，CI 用 `npm ci` 并按其缓存依赖，不再每次解析最新 minor 版本。
- `api-contract` 跑 bash 版本而非 `pwsh` 版本：`pwsh` 不是 ubuntu runner 的硬保证，而这两个检查不需要任何运行时，少一个依赖就少一个「镜像变化导致 Job 挂掉」的来源。两个脚本的 PowerShell 版仍然保留给 Windows 本地使用。
- `TZ` 显式设为 `Asia/Shanghai`（与 §4 的「业务时区固定」一致）。runner 默认 UTC，不显式对齐会让日期边界类断言在 CI 上换一种表现——那是环境差异不是缺陷。**代价**：CI 不覆盖「换成 UTC 部署会不会坏」，这条列在 `doc/07` 的已知盲区里。
- 测试报告以 `surefire-reports` 附件形式保留 7 天，便于排查失败用例。
- 触发条件：向 `main` / `master` / `develop` 推送或提 PR（同一分支的新推送取消上一次未完成的运行），另支持 `workflow_dispatch` 手动触发——接入 workflow 本身时不必为了跑一次而提交空提交。

### 9.1 首次真跑发现并修复的缺陷

这四个 Job 第一次真正执行（本地等价预跑：Redis 6380 + MySQL 8 容器 + `npm ci`）时抓到 5 处真缺陷，全部是「平时被跳过 / 从未执行」掩盖的：

| 位置 | 缺陷 | 后果 |
| --- | --- | --- |
| `RedisRateLimiterIntegrationTest.bucketKeyShouldCarryTtl` | 用 `limiter("it:ttl:")` 构造限流器（内部会再追加随机串），却断言固定 key `it:ttl:user:1` 的 TTL | 断言对象与真实 key 永远不是同一个，`getExpire` 恒为 `null` → 用例一直是红的 |
| `RedisRateLimiterIntegrationTest.twoInstancesShouldShareQuota` | 两个「实例」各自传入前缀、各自被追加不同随机串，落在不同 key 上 | 测的不是「共享」而是「互不干扰」，容量 10 时放行 12 → 一直是红的 |
| `MySqlMigrationSmokeTest` | 断言表名写成 `attempt`，实际迁移出来的是 `exam_attempt` | 迁移本身没问题，断言写错 → 用例一直是红的 |
| `tools/check-mysql-migration.ps1` | 参数用「反引号 + LF」续行，而脚本是纯 LF 行尾 | Windows PowerShell 5.1 不认这种续行，`docker run` 只收到 `run` → 演练脚本直接报参数不足，从未跑成功过 |
| `tools/check-mysql-migration.ps1` / `tools/load-baseline.ps1` | `$ErrorActionPreference='Stop'` 下执行 `docker logs`（stderr 有内容）、以及把含 `&` 的 JDBC URL 直接交给 `mvn.cmd` | 前者被当成终止性错误，后者被 cmd 当成命令分隔符 → 一个永远等不到 MySQL 就绪，一个报 `'allowPublicKeyRetrieval' 不是内部或外部命令` |

修复后本地实测：`mvn test` → `Tests run: 179, Failures: 0, Errors: 0, Skipped: 8`（本机无 Redis/MySQL；CI 起 Redis 后为 `Skipped: 3`，网关断言的是对应用例 `skipped="0"`）；MySQL 8 演练 → `OK：全部迁移脚本在 MySQL 8 上迁移通过`；`npm ci && npm run build` → 通过；两个 bash 静态检查 → `unmatched: 0` / `FRONTEND CONSISTENCY OK`。
