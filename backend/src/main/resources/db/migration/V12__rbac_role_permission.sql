-- ============================================================================
-- V12__rbac_role_permission：角色三元组（角色 / 用户-角色 / 角色-权限）
--
-- 背景：原来 `sys_user.role_code` 是单值，一个用户只能一个角色；
-- 平台的真实诉求是「运营 + 审核」「机构管理员 + 教师」这类组合身份，
-- 以及「同一角色在不同租户下可以有不同权限」。
--
-- 设计取舍：
--   1) `sys_user_role` 用 role_code 字符串关联而不是 role_id：
--      现有代码全按 roleCode 判断，字符串关联能让迁移与回滚都可读，
--      也避免「角色表重建后 user_role 指向悬空」；
--   2) `sys_role_permission.permission_code` 支持 `*`（全部）与 `org:*`（前缀）通配，
--      管理员角色用 `*` 表达，避免每加一个接口就要往表里补一行；
--   3) 三元组带 tenant_id：机构可以有自己的自定义角色，权限不跨租户串用；
--   4) 回填保留 `sys_user.role_code` 不动：老代码与老 token 继续可用，
--      新逻辑以三元组为准，双写期结束后再考虑移除旧列。
-- ============================================================================

CREATE TABLE IF NOT EXISTS sys_role (
  id          BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '角色ID，主键',
  tenant_id   BIGINT       NOT NULL COMMENT '租户ID，关联 sys_tenant.id',
  code        VARCHAR(64)  NOT NULL COMMENT '角色编码，租户内唯一，如 ADMIN / ORG_ADMIN / STUDENT',
  name        VARCHAR(64)  NOT NULL COMMENT '角色名称，展示用',
  description VARCHAR(255) NULL COMMENT '角色说明',
  built_in    TINYINT      NOT NULL DEFAULT 0 COMMENT '是否内置角色：1-内置（禁止删除），0-自定义',
  status      VARCHAR(16)  NOT NULL DEFAULT 'ENABLED' COMMENT '状态：ENABLED-启用，DISABLED-停用',
  created_at  DATETIME     NOT NULL COMMENT '创建时间',
  updated_at  DATETIME     NOT NULL COMMENT '最后更新时间',
  deleted     TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除标记：0-正常，1-已删除'
);
CREATE UNIQUE INDEX uk_sys_role_code ON sys_role (tenant_id, code);

CREATE TABLE IF NOT EXISTS sys_user_role (
  id         BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
  tenant_id  BIGINT      NOT NULL COMMENT '租户ID，关联 sys_tenant.id',
  user_id    BIGINT      NOT NULL COMMENT '用户ID，关联 sys_user.id',
  role_code  VARCHAR(64) NOT NULL COMMENT '角色编码，关联 sys_role.code',
  created_at DATETIME    NOT NULL COMMENT '授予时间',
  updated_at DATETIME    NOT NULL COMMENT '最后更新时间',
  deleted    TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除标记：0-正常，1-已删除'
);
CREATE UNIQUE INDEX uk_sys_user_role ON sys_user_role (tenant_id, user_id, role_code);
CREATE INDEX idx_sys_user_role_user ON sys_user_role (tenant_id, user_id);

CREATE TABLE IF NOT EXISTS sys_role_permission (
  id              BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
  tenant_id       BIGINT       NOT NULL COMMENT '租户ID，关联 sys_tenant.id',
  role_code       VARCHAR(64)  NOT NULL COMMENT '角色编码，关联 sys_role.code',
  permission_code VARCHAR(128) NOT NULL COMMENT '权限码：* 表示全部，org:* 表示前缀匹配',
  created_at      DATETIME     NOT NULL COMMENT '创建时间',
  updated_at      DATETIME     NOT NULL COMMENT '最后更新时间',
  deleted         TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除标记：0-正常，1-已删除'
);
CREATE UNIQUE INDEX uk_sys_role_permission ON sys_role_permission (tenant_id, role_code, permission_code);

-- 内置角色（幂等）
INSERT INTO sys_role (tenant_id, code, name, description, built_in, status, created_at, updated_at, deleted)
SELECT 1, 'ADMIN', '平台管理员', '平台后台全部权限', 1, 'ENABLED', NOW(), NOW(), 0
WHERE NOT EXISTS (SELECT 1 FROM sys_role WHERE tenant_id = 1 AND code = 'ADMIN');

INSERT INTO sys_role (tenant_id, code, name, description, built_in, status, created_at, updated_at, deleted)
SELECT 1, 'ORG_ADMIN', '机构管理员', '仅限本机构范围内的管理权限', 1, 'ENABLED', NOW(), NOW(), 0
WHERE NOT EXISTS (SELECT 1 FROM sys_role WHERE tenant_id = 1 AND code = 'ORG_ADMIN');

INSERT INTO sys_role (tenant_id, code, name, description, built_in, status, created_at, updated_at, deleted)
SELECT 1, 'STUDENT', '考生', '报名、考试、查看成绩与权益', 1, 'ENABLED', NOW(), NOW(), 0
WHERE NOT EXISTS (SELECT 1 FROM sys_role WHERE tenant_id = 1 AND code = 'STUDENT');

-- 现存 role_code 里的其他取值（含各租户自定义）补成角色，避免赋角色时找不到
INSERT INTO sys_role (tenant_id, code, name, description, built_in, status, created_at, updated_at, deleted)
SELECT DISTINCT u.tenant_id, u.role_code, u.role_code, '由存量 role_code 迁移', 0, 'ENABLED', NOW(), NOW(), 0
FROM sys_user u
WHERE u.role_code IS NOT NULL AND u.role_code <> ''
  AND NOT EXISTS (SELECT 1 FROM sys_role r WHERE r.tenant_id = u.tenant_id AND r.code = u.role_code);

-- 存量用户 → 用户角色（幂等）
INSERT INTO sys_user_role (tenant_id, user_id, role_code, created_at, updated_at, deleted)
SELECT u.tenant_id, u.id, u.role_code, NOW(), NOW(), 0
FROM sys_user u
WHERE u.role_code IS NOT NULL AND u.role_code <> ''
  AND NOT EXISTS (
    SELECT 1 FROM sys_user_role ur
    WHERE ur.tenant_id = u.tenant_id AND ur.user_id = u.id AND ur.role_code = u.role_code
  );

-- 默认权限：管理员全量、机构管理员限机构域、考生限业务域
INSERT INTO sys_role_permission (tenant_id, role_code, permission_code, created_at, updated_at, deleted)
SELECT 1, 'ADMIN', '*', NOW(), NOW(), 0
WHERE NOT EXISTS (SELECT 1 FROM sys_role_permission WHERE tenant_id = 1 AND role_code = 'ADMIN' AND permission_code = '*');

INSERT INTO sys_role_permission (tenant_id, role_code, permission_code, created_at, updated_at, deleted)
SELECT 1, 'ORG_ADMIN', 'org:*', NOW(), NOW(), 0
WHERE NOT EXISTS (SELECT 1 FROM sys_role_permission WHERE tenant_id = 1 AND role_code = 'ORG_ADMIN' AND permission_code = 'org:*');

INSERT INTO sys_role_permission (tenant_id, role_code, permission_code, created_at, updated_at, deleted)
SELECT 1, 'STUDENT', 'exam:take', NOW(), NOW(), 0
WHERE NOT EXISTS (SELECT 1 FROM sys_role_permission WHERE tenant_id = 1 AND role_code = 'STUDENT' AND permission_code = 'exam:take');

INSERT INTO sys_role_permission (tenant_id, role_code, permission_code, created_at, updated_at, deleted)
SELECT 1, 'STUDENT', 'mall:buy', NOW(), NOW(), 0
WHERE NOT EXISTS (SELECT 1 FROM sys_role_permission WHERE tenant_id = 1 AND role_code = 'STUDENT' AND permission_code = 'mall:buy');
