# 11 · RBAC 三元组

> 对应 Step 5 第二组。代码：`modules/system/RoleService`、`RoleController`、
> `common/security/AuthInterceptor`；迁移：`V12__rbac_role_permission.sql`。

## 一、模型

```text
sys_role              角色：code / name / built_in / status（租户内唯一）
sys_user_role         用户-角色：一个用户可多角色
sys_role_permission   角色-权限：permission_code 支持 * 与 org:* 通配
```

三个决定：

1. **`sys_user_role` 用 role_code 字符串关联**，不用 role_id——现有代码全按 roleCode 判断，
   字符串关联让迁移与回滚都可读，也不会出现「角色表重建后关联悬空」；
2. **迁移保留 `sys_user.role_code`**：老代码与已签发的 token 继续可用，双写期结束后再考虑移除；
3. **权限每次请求现查**，不写进 JWT。撤权必须立即生效——否则被停用的管理员还能用旧 token
   操作到过期为止，这类窗口是安全事故的常见来源。

## 二、兼容期回退（这次实现里最关键的一段）

V12 用 `INSERT ... SELECT` 回填了存量用户的角色，但**演示账号与后台创建的账号是在迁移之后写入的**，
三元组里没有它们。不做处理，升级后这些账号会集体失去权限。

`RoleService` 因此有三处兜底：

| 位置 | 行为 |
| --- | --- |
| `rolesOf` | 三元组为空时，读 `sys_user.role_code` 视为单角色 |
| `grant` | 授予新角色前，先把老角色落成一条 `user_role`（否则加角色会把老角色挤掉） |
| `revoke` | 撤销前先落老角色（否则 `UPDATE` 影响 0 行，「撤销唯一角色」会静默成功） |

这三处都有对应的测试断言。

## 三、权限判定

```text
*          全部
org:*      前缀通配（机构域）
exam:take  精确
```

前缀通配是必要的：管理员每新增一个机构接口就补一行权限，配置一定会漏。

## 四、防呆

| 场景 | 结果 |
| --- | --- |
| 撤销自己的角色 | `AUTH_SELF_REVOKE`（把自己锁在门外，且没有别的账号补救） |
| 撤销用户的最后一个角色 | `AUTH_LAST_ROLE`，且事务回滚 |

## 五、审计门禁（被 CI 抓到的真实缺口）

新增的 4 个写接口一开始没标 `@Audited`，`AuditCoverageTest.everyAdminWriteEndpointMustBeAudited`
直接把构建打红：

```text
Expecting empty but was: ["RoleController#grantPermission (/v1/admin/roles/{code}/permissions)", ...]
```

这条门禁的价值就在这里：权限变更必须能回答「谁在什么时候给了谁什么」，
漏了审计等于事后无法追查越权。

## 六、接口

| 方法 | 路径 |
| --- | --- |
| GET | `/v1/admin/roles` |
| POST | `/v1/admin/roles` |
| GET | `/v1/admin/roles/{code}/permissions` |
| POST | `/v1/admin/roles/{code}/permissions?permission=` |
| GET | `/v1/admin/users/{id}/roles` |
| POST | `/v1/admin/users/{id}/roles?roleCode=` |
| DELETE | `/v1/admin/users/{id}/roles/{code}` |

## 七、测试

`RbacFlowTest`（4 个用例）：存量 role_code 可读、考生访问后台被拒、加第二个角色权限取并集且重复授予幂等、
不能撤销自己/最后一个角色且被拒的撤销不生效。

本机 `mvn test`：`Tests run: 179, Failures: 0, Errors: 0, Skipped: 8`。

## 八、已知限制

1. 管理端 UI 未做角色/权限管理页面（接口已就绪）；
2. 权限码尚未全面接入业务接口——目前只有 `/v1/admin/**` 按角色强制，
   `requirePermission` 已提供但需要逐个接口迁移；
3. 角色删除（软删）未提供接口，避免误删导致用户失去全部权限；
4. JWT 撤销依赖角色/权限变更，不改密码的情况下 token 本身仍有效到期（无黑名单）。
