-- ============================================================================
-- 综合考试平台 基线 DDL（Flyway V1）
-- 说明：
--   1) 为同时兼容本地 H2（MySQL 模式）与生产 MySQL 8.x，脚本未使用
--      ENGINE/CHARSET 子句；二级索引一并放在本文件末尾（两者均支持 CREATE INDEX）。
--   2) 所有业务表统一带 tenant_id / created_at / updated_at / deleted。
--   3) 时间字段为 DATETIME，业务时区固定 Asia/Shanghai（详见 README 简化说明）。
--   4) 字段注释统一使用 MySQL 的 COMMENT 子句，H2（MODE=MySQL）2.x 同样支持；
--      生产环境可通过 INFORMATION_SCHEMA.COLUMNS.REMARKS 或 SHOW FULL COLUMNS 查看。
--   5) 本文件是基线，**已部署的库不要再修改它**——结构变更新增 V2__xxx.sql，
--      否则已迁移的库会因 checksum 不一致启动失败。
-- ============================================================================

-- ----------------------------------------------------------------------------
-- sys_tenant 租户（一个租户 = 一套业务数据，SQL 层由租户插件强制隔离）
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sys_tenant (
  id         BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '租户ID，主键',
  code       VARCHAR(64)  NOT NULL COMMENT '租户编码，登录时定位租户，全局唯一（如 PLATFORM）',
  name       VARCHAR(128) NOT NULL COMMENT '租户名称，展示用',
  status     VARCHAR(16)  NOT NULL DEFAULT 'ENABLED' COMMENT '状态：ENABLED-启用，DISABLED-停用',
  created_at DATETIME     NOT NULL COMMENT '创建时间（Asia/Shanghai）',
  updated_at DATETIME     NOT NULL COMMENT '最后更新时间（Asia/Shanghai）',
  deleted    TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除标记：0-正常，1-已删除',
  CONSTRAINT uk_sys_tenant_code UNIQUE (code)
);

-- ----------------------------------------------------------------------------
-- sys_user 用户（管理员与考生共用一张表，角色由 role_code 区分）
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sys_user (
  id            BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '用户ID，平台自身主键（非微信 openid）',
  tenant_id     BIGINT       NOT NULL COMMENT '租户ID，关联 sys_tenant.id',
  username      VARCHAR(64)  NOT NULL COMMENT '登录账号，租户内唯一；微信用户由系统生成',
  password      VARCHAR(100) NULL COMMENT '密码哈希（BCrypt），微信注册用户可为空',
  nickname      VARCHAR(64)  NULL COMMENT '昵称，展示用',
  phone         VARCHAR(32)  NULL COMMENT '手机号，用于联系与消息触达',
  avatar        VARCHAR(512) NULL COMMENT '头像地址 URL',
  role_code     VARCHAR(32)  NOT NULL DEFAULT 'STUDENT' COMMENT '角色编码：ADMIN-管理员，STUDENT-考生',
  status        VARCHAR(16)  NOT NULL DEFAULT 'ENABLED' COMMENT '账号状态：ENABLED-启用，DISABLED-停用（停用后禁止登录）',
  last_login_at DATETIME     NULL COMMENT '最近一次登录时间，未登录过为空',
  created_at    DATETIME     NOT NULL COMMENT '创建时间',
  updated_at    DATETIME     NOT NULL COMMENT '最后更新时间',
  deleted       TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除标记：0-正常，1-已删除',
  CONSTRAINT uk_sys_user_username UNIQUE (tenant_id, username)
);

-- ----------------------------------------------------------------------------
-- sys_user_identity 第三方身份绑定（openid 仅作渠道标识，不落用户主键）
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sys_user_identity (
  id         BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '身份绑定ID，主键',
  tenant_id  BIGINT      NOT NULL COMMENT '租户ID，关联 sys_tenant.id',
  user_id    BIGINT      NOT NULL COMMENT '平台用户ID，关联 sys_user.id',
  provider   VARCHAR(32) NOT NULL COMMENT '渠道标识：WECHAT-微信，后续可扩展其他渠道',
  open_id    VARCHAR(64) NOT NULL COMMENT '渠道用户唯一标识（微信 openid），租户内按渠道唯一',
  union_id   VARCHAR(64) NULL COMMENT '渠道跨应用唯一标识（微信 unionid），可为空',
  created_at DATETIME    NOT NULL COMMENT '创建时间（首次绑定时间）',
  updated_at DATETIME    NOT NULL COMMENT '最后更新时间',
  deleted    TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除标记：0-正常，1-已删除',
  CONSTRAINT uk_sys_user_identity UNIQUE (tenant_id, provider, open_id)
);

-- ----------------------------------------------------------------------------
-- sys_org 组织（院校 / 机构，用于按组织维度批量授权）
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sys_org (
  id            BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '组织ID，主键',
  tenant_id     BIGINT       NOT NULL COMMENT '租户ID，关联 sys_tenant.id',
  code          VARCHAR(64)  NOT NULL COMMENT '组织编码，租户内唯一',
  name          VARCHAR(128) NOT NULL COMMENT '组织名称',
  type          VARCHAR(16)  NOT NULL DEFAULT 'INSTITUTION' COMMENT '组织类型：INSTITUTION-院校/培训机构，ENTERPRISE-企业',
  contact_name  VARCHAR(64)  NULL COMMENT '联系人姓名',
  contact_phone VARCHAR(32)  NULL COMMENT '联系人电话',
  status        VARCHAR(16)  NOT NULL DEFAULT 'ENABLED' COMMENT '状态：ENABLED-启用，DISABLED-停用（停用后不可授权）',
  remark        VARCHAR(255) NULL COMMENT '备注说明',
  created_at    DATETIME     NOT NULL COMMENT '创建时间',
  updated_at    DATETIME     NOT NULL COMMENT '最后更新时间',
  deleted       TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除标记：0-正常，1-已删除',
  CONSTRAINT uk_sys_org_code UNIQUE (tenant_id, code)
);

-- ----------------------------------------------------------------------------
-- sys_org_member 组织成员（用户与组织的多对多关系）
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sys_org_member (
  id         BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '成员关系ID，主键',
  tenant_id  BIGINT      NOT NULL COMMENT '租户ID，关联 sys_tenant.id',
  org_id     BIGINT      NOT NULL COMMENT '组织ID，关联 sys_org.id',
  user_id    BIGINT      NOT NULL COMMENT '用户ID，关联 sys_user.id',
  role       VARCHAR(16) NOT NULL DEFAULT 'MEMBER' COMMENT '组织内角色：MEMBER-普通成员，ADMIN-组织管理员',
  status     VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT '成员状态：ACTIVE-在册，REMOVED-已移出（移出后不再被授权）',
  created_at DATETIME    NOT NULL COMMENT '加入时间',
  updated_at DATETIME    NOT NULL COMMENT '最后更新时间',
  deleted    TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除标记：0-正常，1-已删除',
  CONSTRAINT uk_sys_org_member UNIQUE (tenant_id, org_id, user_id)
);

-- ----------------------------------------------------------------------------
-- sys_audit_log 审计日志（管理端写操作留痕）
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sys_audit_log (
  id         BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '审计日志ID，主键',
  tenant_id  BIGINT       NOT NULL COMMENT '租户ID，关联 sys_tenant.id',
  user_id    BIGINT       NULL COMMENT '操作人用户ID，关联 sys_user.id',
  username   VARCHAR(64)  NULL COMMENT '操作人登录账号（冗余存储，便于直接查阅）',
  action     VARCHAR(64)  NOT NULL COMMENT '操作动作编码，如 QUESTION_CREATE / USER_DISABLE',
  target     VARCHAR(128) NULL COMMENT '操作对象标识，如实体名 + 主键',
  detail     TEXT         NULL COMMENT '操作详情（JSON 或文本），记录变更前后关键字段',
  ip         VARCHAR(64)  NULL COMMENT '请求来源 IP',
  created_at DATETIME     NOT NULL COMMENT '操作时间',
  updated_at DATETIME     NOT NULL COMMENT '最后更新时间',
  deleted    TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除标记：0-正常，1-已删除'
);

-- ----------------------------------------------------------------------------
-- exam_project 考试项目（对外招考的顶层分类，如「上半年教师资格考试」）
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS exam_project (
  id              BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '项目ID，主键',
  tenant_id       BIGINT       NOT NULL COMMENT '租户ID，关联 sys_tenant.id',
  code            VARCHAR(64)  NOT NULL COMMENT '项目编码，租户内唯一',
  name            VARCHAR(128) NOT NULL COMMENT '项目名称',
  intro           TEXT         NULL COMMENT '项目介绍（富文本或纯文本）',
  cover_url       VARCHAR(512) NULL COMMENT '封面图地址',
  target_audience VARCHAR(255) NULL COMMENT '适用人群说明，如「在校生 / 社会考生」',
  status          VARCHAR(16)  NOT NULL DEFAULT 'DRAFT' COMMENT '状态：DRAFT-草稿，PUBLISHED-已发布，OFFLINE-已下线',
  sort_no         INT          NOT NULL DEFAULT 0 COMMENT '排序号，越小越靠前',
  created_at      DATETIME     NOT NULL COMMENT '创建时间',
  updated_at      DATETIME     NOT NULL COMMENT '最后更新时间',
  deleted         TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除标记：0-正常，1-已删除',
  CONSTRAINT uk_exam_project_code UNIQUE (tenant_id, code)
);

-- ----------------------------------------------------------------------------
-- exam_subject 考试科目（挂在项目下，题库与试卷均按科目归集）
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS exam_subject (
  id         BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '科目ID，主键',
  tenant_id  BIGINT       NOT NULL COMMENT '租户ID，关联 sys_tenant.id',
  project_id BIGINT       NOT NULL COMMENT '所属项目ID，关联 exam_project.id',
  code       VARCHAR(64)  NOT NULL COMMENT '科目编码，项目内唯一',
  name       VARCHAR(128) NOT NULL COMMENT '科目名称',
  intro      VARCHAR(1024) NULL COMMENT '科目介绍',
  status     VARCHAR(16)  NOT NULL DEFAULT 'ENABLED' COMMENT '状态：ENABLED-启用，DISABLED-停用',
  sort_no    INT          NOT NULL DEFAULT 0 COMMENT '排序号，越小越靠前',
  created_at DATETIME     NOT NULL COMMENT '创建时间',
  updated_at DATETIME     NOT NULL COMMENT '最后更新时间',
  deleted    TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除标记：0-正常，1-已删除',
  CONSTRAINT uk_exam_subject_code UNIQUE (tenant_id, project_id, code)
);

-- ----------------------------------------------------------------------------
-- exam_knowledge_point 知识点（科目下的树形结构，parent_id 为空表示根节点）
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS exam_knowledge_point (
  id         BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '知识点ID，主键',
  tenant_id  BIGINT       NOT NULL COMMENT '租户ID，关联 sys_tenant.id',
  subject_id BIGINT       NOT NULL COMMENT '所属科目ID，关联 exam_subject.id',
  parent_id  BIGINT       NULL COMMENT '父知识点ID，为空表示一级知识点',
  name       VARCHAR(128) NOT NULL COMMENT '知识点名称',
  sort_no    INT          NOT NULL DEFAULT 0 COMMENT '同级排序号，越小越靠前',
  created_at DATETIME     NOT NULL COMMENT '创建时间',
  updated_at DATETIME     NOT NULL COMMENT '最后更新时间',
  deleted    TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除标记：0-正常，1-已删除'
);

-- ----------------------------------------------------------------------------
-- qb_question 题库题目（选项与答案以 JSON 存储，修改时递增 version）
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS qb_question (
  id           BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '题目ID，主键',
  tenant_id    BIGINT       NOT NULL COMMENT '租户ID，关联 sys_tenant.id',
  subject_id   BIGINT       NOT NULL COMMENT '所属科目ID，关联 exam_subject.id',
  type         VARCHAR(16)  NOT NULL COMMENT '题型：SINGLE-单选，MULTIPLE-多选，JUDGE-判断，ESSAY-主观题',
  stem         TEXT         NOT NULL COMMENT '题干文本',
  options_json TEXT         NULL COMMENT '选项 JSON，如 [{"key":"A","content":"..."}]；主观题为空',
  answer_json  TEXT         NOT NULL COMMENT '标准答案 JSON，如 ["A"] / ["A","C"] / true；不返回给考生',
  analysis     TEXT         NULL COMMENT '答案解析，成绩页展示',
  difficulty   VARCHAR(16)  NOT NULL DEFAULT 'NORMAL' COMMENT '难度：EASY-简单，NORMAL-一般，HARD-困难',
  tags         VARCHAR(255) NULL COMMENT '标签，英文逗号分隔',
  status       VARCHAR(16)  NOT NULL DEFAULT 'ENABLED' COMMENT '状态：ENABLED-启用，DISABLED-停用（不可组卷）',
  version      INT          NOT NULL DEFAULT 1 COMMENT '题目版本号，每次修改递增',
  created_at   DATETIME     NOT NULL COMMENT '创建时间',
  updated_at   DATETIME     NOT NULL COMMENT '最后更新时间',
  deleted      TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除标记：0-正常，1-已删除'
);

-- ----------------------------------------------------------------------------
-- exam_paper 试卷定义（固定组卷或规则组卷，发布场次时生成快照）
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS exam_paper (
  id            BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '试卷ID，主键',
  tenant_id     BIGINT       NOT NULL COMMENT '租户ID，关联 sys_tenant.id',
  project_id    BIGINT       NOT NULL COMMENT '所属项目ID，关联 exam_project.id',
  subject_id    BIGINT       NULL COMMENT '所属科目ID，关联 exam_subject.id；跨科目综合卷为空',
  name          VARCHAR(128) NOT NULL COMMENT '试卷名称',
  mode          VARCHAR(16)  NOT NULL DEFAULT 'FIXED' COMMENT '组卷模式：FIXED-固定组卷，RULE-规则组卷',
  total_score   INT          NOT NULL DEFAULT 0 COMMENT '试卷总分',
  question_json TEXT         NOT NULL COMMENT '题目清单 JSON（题目ID/分值/排序），固定组卷时为题目明细',
  status        VARCHAR(16)  NOT NULL DEFAULT 'DRAFT' COMMENT '状态：DRAFT-草稿，PUBLISHED-已发布（发布后锁版本）',
  version       INT          NOT NULL DEFAULT 1 COMMENT '试卷版本号，内容变更后递增',
  created_at    DATETIME     NOT NULL COMMENT '创建时间',
  updated_at    DATETIME     NOT NULL COMMENT '最后更新时间',
  deleted       TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除标记：0-正常，1-已删除'
);

-- ----------------------------------------------------------------------------
-- exam_paper_snapshot 试卷快照（场次发布时冻结题面与答案，判分与复查都以此为准）
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS exam_paper_snapshot (
  id            BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '快照ID，主键',
  tenant_id     BIGINT      NOT NULL COMMENT '租户ID，关联 sys_tenant.id',
  paper_id      BIGINT      NOT NULL COMMENT '试卷ID，关联 exam_paper.id',
  paper_version INT         NOT NULL DEFAULT 1 COMMENT '快照对应的试卷版本号',
  total_score   INT         NOT NULL DEFAULT 0 COMMENT '快照总分',
  content_json  TEXT        NOT NULL COMMENT '快照内容 JSON（题干/选项/答案/分值/排序）',
  content_hash  VARCHAR(64) NOT NULL COMMENT '快照内容 SHA-256 哈希，读取时校验完整性',
  created_at    DATETIME    NOT NULL COMMENT '创建时间（出版本时间）',
  updated_at    DATETIME    NOT NULL COMMENT '最后更新时间',
  deleted       TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除标记：0-正常，1-已删除'
);

-- ----------------------------------------------------------------------------
-- exam_session 考试场次（绑定期次与试卷，发布后规则锁定）
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS exam_session (
  id                         BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '场次ID，主键',
  tenant_id                  BIGINT       NOT NULL COMMENT '租户ID，关联 sys_tenant.id',
  project_id                 BIGINT       NOT NULL COMMENT '所属项目ID，关联 exam_project.id',
  paper_id                   BIGINT       NOT NULL COMMENT '使用试卷ID，关联 exam_paper.id',
  snapshot_id                BIGINT       NULL COMMENT '发布时生成的试卷快照ID，关联 exam_paper_snapshot.id',
  name                       VARCHAR(128) NOT NULL COMMENT '场次名称',
  register_start_at          DATETIME     NULL COMMENT '报名开始时间，为空表示不限制',
  register_end_at            DATETIME     NULL COMMENT '报名截止时间，为空表示不限制',
  start_at                   DATETIME     NOT NULL COMMENT '考试开始时间',
  end_at                     DATETIME     NOT NULL COMMENT '考试结束时间（超过则自动收卷）',
  duration_minutes           INT          NOT NULL DEFAULT 60 COMMENT '答题时长（分钟），从开考起算',
  allow_enter_before_minutes INT          NOT NULL DEFAULT 20 COMMENT '允许提前进入考场的分钟数',
  late_limit_minutes         INT          NOT NULL DEFAULT 0 COMMENT '允许迟到进入的分钟数，0 表示不限制',
  result_publish_at          DATETIME     NULL COMMENT '成绩发布时间，为空表示交卷即可见',
  quota                      INT          NOT NULL DEFAULT 0 COMMENT '报名名额上限，0 表示不限',
  notice_version             VARCHAR(16)  NOT NULL DEFAULT 'v1' COMMENT '考生须知版本号，版本变化需重新确认',
  notice_content             TEXT         NULL COMMENT '考生须知正文',
  status                     VARCHAR(16)  NOT NULL DEFAULT 'DRAFT' COMMENT '状态：DRAFT-草稿，PUBLISHED-已发布，CLOSED-已关闭',
  created_at                 DATETIME     NOT NULL COMMENT '创建时间',
  updated_at                 DATETIME     NOT NULL COMMENT '最后更新时间',
  deleted                    TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除标记：0-正常，1-已删除'
);

-- ----------------------------------------------------------------------------
-- exam_registration 考试报名（同一用户同一场次仅一条，唯一约束兜底）
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS exam_registration (
  id         BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '报名ID，主键',
  tenant_id  BIGINT      NOT NULL COMMENT '租户ID，关联 sys_tenant.id',
  session_id BIGINT      NOT NULL COMMENT '场次ID，关联 exam_session.id',
  project_id BIGINT      NOT NULL COMMENT '项目ID，关联 exam_project.id（冗余便于按项目统计）',
  user_id    BIGINT      NOT NULL COMMENT '考生用户ID，关联 sys_user.id',
  status     VARCHAR(16) NOT NULL DEFAULT 'CONFIRMED' COMMENT '状态：CONFIRMED-报名成功，CANCELLED-已取消',
  ticket_no  VARCHAR(64) NOT NULL COMMENT '准考证号，报名成功后生成',
  request_id VARCHAR(64) NULL COMMENT '幂等请求号，同一请求重复提交不重复生成报名',
  created_at DATETIME    NOT NULL COMMENT '报名时间',
  updated_at DATETIME    NOT NULL COMMENT '最后更新时间',
  deleted    TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除标记：0-正常，1-已删除',
  CONSTRAINT uk_exam_registration UNIQUE (tenant_id, session_id, user_id)
);

-- ----------------------------------------------------------------------------
-- exam_attempt 答卷（一次作答实例，服务端下发 started_at / deadline_at）
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS exam_attempt (
  id                BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '答卷ID，主键',
  tenant_id         BIGINT        NOT NULL COMMENT '租户ID，关联 sys_tenant.id',
  session_id        BIGINT        NOT NULL COMMENT '场次ID，关联 exam_session.id',
  registration_id   BIGINT        NOT NULL COMMENT '报名ID，关联 exam_registration.id',
  user_id           BIGINT        NOT NULL COMMENT '考生用户ID，关联 sys_user.id',
  paper_snapshot_id BIGINT        NOT NULL COMMENT '使用的试卷快照ID，关联 exam_paper_snapshot.id',
  attempt_no        INT           NOT NULL DEFAULT 1 COMMENT '作答序号，同一用户同一场次可重考时的轮次',
  status            VARCHAR(24)   NOT NULL COMMENT '状态：IN_PROGRESS-作答中，SUBMITTED-已交卷，REVIEWING-阅卷中，EXPIRED-已超时，GRADED-已出分',
  started_at        DATETIME      NULL COMMENT '实际开考时间（以服务端为准）',
  deadline_at       DATETIME      NULL COMMENT '本卷截止时间，取场次结束时间与开考+时长中较早者',
  submitted_at      DATETIME      NULL COMMENT '交卷时间，未交卷为空',
  submit_reason     VARCHAR(32)   NULL COMMENT '交卷原因：USER_SUBMIT-考生主动交卷，AUTO_SUBMIT-到期自动交卷，ADMIN_FORCE-管理员强制收卷',
  total_score       DECIMAL(10,2) NULL COMMENT '本次得分，阅卷完成前为空或仅含客观题分',
  scoring_version   INT           NOT NULL DEFAULT 1 COMMENT '判分版本号，判分规则变化时递增以便追溯',
  answer_version    INT           NOT NULL DEFAULT 0 COMMENT '答案版本号，每次保存答案递增，用于防止旧请求覆盖新答案',
  created_at        DATETIME      NOT NULL COMMENT '创建时间（进入考场时间）',
  updated_at        DATETIME      NOT NULL COMMENT '最后更新时间',
  deleted           TINYINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除标记：0-正常，1-已删除',
  CONSTRAINT uk_exam_attempt UNIQUE (tenant_id, session_id, user_id, attempt_no)
);

-- ----------------------------------------------------------------------------
-- exam_answer 作答明细（按题保存，唯一键 + 版本号保证幂等与顺序）
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS exam_answer (
  id             BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '作答明细ID，主键',
  tenant_id      BIGINT      NOT NULL COMMENT '租户ID，关联 sys_tenant.id',
  attempt_id     BIGINT      NOT NULL COMMENT '答卷ID，关联 exam_attempt.id',
  question_id    BIGINT      NOT NULL COMMENT '题目ID，关联 qb_question.id',
  answer_json    TEXT        NULL COMMENT '考生答案 JSON，如 ["A"]；未作答为空',
  answer_version INT         NOT NULL DEFAULT 1 COMMENT '答案版本号，后写入的更大版本才生效',
  is_marked      TINYINT     NOT NULL DEFAULT 0 COMMENT '是否标记待复查：0-否，1-是',
  saved_at       DATETIME    NULL COMMENT '最近一次保存时间',
  created_at     DATETIME    NOT NULL COMMENT '创建时间',
  updated_at     DATETIME    NOT NULL COMMENT '最后更新时间',
  deleted        TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除标记：0-正常，1-已删除',
  CONSTRAINT uk_exam_answer UNIQUE (tenant_id, attempt_id, question_id)
);

-- ----------------------------------------------------------------------------
-- exam_result 成绩（每份答卷一条，唯一约束保证重复交卷不重复判分）
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS exam_result (
  id              BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '成绩ID，主键',
  tenant_id       BIGINT        NOT NULL COMMENT '租户ID，关联 sys_tenant.id',
  attempt_id      BIGINT        NOT NULL COMMENT '答卷ID，关联 exam_attempt.id',
  session_id      BIGINT        NOT NULL COMMENT '场次ID，关联 exam_session.id（冗余便于统计）',
  user_id         BIGINT        NOT NULL COMMENT '考生用户ID，关联 sys_user.id',
  total_score     DECIMAL(10,2) NOT NULL DEFAULT 0 COMMENT '总得分',
  full_score      INT           NOT NULL DEFAULT 0 COMMENT '满分',
  correct_count   INT           NOT NULL DEFAULT 0 COMMENT '答对题数（客观题）',
  question_count  INT           NOT NULL DEFAULT 0 COMMENT '总题数',
  scoring_version INT           NOT NULL DEFAULT 1 COMMENT '判分版本号，与答卷一致',
  detail_json     TEXT          NULL COMMENT '逐题判分明细 JSON（得分/答案/解析），主观题阅卷结束前对考生隐藏',
  status          VARCHAR(16)   NOT NULL DEFAULT 'GRADED' COMMENT '状态：REVIEWING-阅卷中（考生仅可见客观题分），GRADED-已判分待发布，PUBLISHED-已发布；成绩更正通过 revision 递增留痕',
  published_at    DATETIME      NULL COMMENT '成绩发布时间，为空表示尚未发布',
  revision        INT           NOT NULL DEFAULT 1 COMMENT '成绩修订版本号，每次更正递增',
  created_at      DATETIME      NOT NULL COMMENT '创建时间（判分时间）',
  updated_at      DATETIME      NOT NULL COMMENT '最后更新时间',
  deleted         TINYINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除标记：0-正常，1-已删除',
  CONSTRAINT uk_exam_result_attempt UNIQUE (tenant_id, attempt_id)
);

-- ----------------------------------------------------------------------------
-- learn_practice 练习会话（一次刷题过程，题库清单在 question_ids 中固化）
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS learn_practice (
  id             BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '练习ID，主键',
  tenant_id      BIGINT      NOT NULL COMMENT '租户ID，关联 sys_tenant.id',
  user_id        BIGINT      NOT NULL COMMENT '考生用户ID，关联 sys_user.id',
  subject_id     BIGINT      NOT NULL COMMENT '练习科目ID，关联 exam_subject.id',
  mode           VARCHAR(16) NOT NULL DEFAULT 'SEQUENCE' COMMENT '练习模式：SEQUENCE-顺序，RANDOM-随机，WRONG-错题重练',
  question_ids   TEXT        NULL COMMENT '本次练习的题目ID清单 JSON，创建时固化，避免中途题库变化',
  total_count    INT         NOT NULL DEFAULT 0 COMMENT '总题数',
  answered_count INT         NOT NULL DEFAULT 0 COMMENT '已作答题数',
  correct_count  INT         NOT NULL DEFAULT 0 COMMENT '答对题数',
  status         VARCHAR(16) NOT NULL DEFAULT 'IN_PROGRESS' COMMENT '状态：IN_PROGRESS-进行中，FINISHED-已完成',
  started_at     DATETIME    NULL COMMENT '开始时间',
  finished_at    DATETIME    NULL COMMENT '结束时间',
  created_at     DATETIME    NOT NULL COMMENT '创建时间',
  updated_at     DATETIME    NOT NULL COMMENT '最后更新时间',
  deleted        TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除标记：0-正常，1-已删除'
);

-- ----------------------------------------------------------------------------
-- learn_practice_answer 练习作答明细（错题本由此表按 correct=0 汇总）
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS learn_practice_answer (
  id          BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '作答明细ID，主键',
  tenant_id   BIGINT      NOT NULL COMMENT '租户ID，关联 sys_tenant.id',
  practice_id BIGINT      NOT NULL COMMENT '练习ID，关联 learn_practice.id',
  user_id     BIGINT      NOT NULL COMMENT '考生用户ID，关联 sys_user.id',
  question_id BIGINT      NOT NULL COMMENT '题目ID，关联 qb_question.id',
  subject_id  BIGINT      NOT NULL COMMENT '科目ID，关联 exam_subject.id（冗余便于错题本按科目聚合）',
  answer_json TEXT        NULL COMMENT '考生答案 JSON；未作答为空',
  correct     TINYINT     NOT NULL DEFAULT 0 COMMENT '是否答对：0-错，1-对',
  created_at  DATETIME    NOT NULL COMMENT '创建时间',
  updated_at  DATETIME    NOT NULL COMMENT '最后更新时间',
  deleted     TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除标记：0-正常，1-已删除',
  CONSTRAINT uk_learn_practice_answer UNIQUE (tenant_id, practice_id, question_id)
);

-- ----------------------------------------------------------------------------
-- mall_product 商城商品（价格以「分」存 BIGINT，下单时取服务端快照）
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS mall_product (
  id                 BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '商品ID，主键',
  tenant_id          BIGINT       NOT NULL COMMENT '租户ID，关联 sys_tenant.id',
  code               VARCHAR(64)  NOT NULL COMMENT '商品编码，租户内唯一',
  name               VARCHAR(128) NOT NULL COMMENT '商品名称',
  type               VARCHAR(16)  NOT NULL COMMENT '商品类型：MEMBER-平台会员，QUESTION_BANK-题库包，COURSE-课程',
  description        TEXT         NULL COMMENT '商品描述',
  cover_url          VARCHAR(512) NULL COMMENT '封面图地址',
  price_fen          BIGINT       NOT NULL DEFAULT 0 COMMENT '售价（单位：分）',
  original_price_fen BIGINT       NOT NULL DEFAULT 0 COMMENT '原价（单位：分），用于划线展示，0 表示无',
  validity_days      INT          NOT NULL DEFAULT 0 COMMENT '权益有效期（天），0 表示永久有效',
  resource_type      VARCHAR(32)  NULL COMMENT '关联资源类型：COURSE / EXAM_PROJECT / SUBJECT 等',
  resource_id        BIGINT       NULL COMMENT '关联资源ID，与 resource_type 组合使用',
  status             VARCHAR(16)  NOT NULL DEFAULT 'ON_SALE' COMMENT '状态：ON_SALE-在售，OFF_SALE-下架',
  sort_no            INT          NOT NULL DEFAULT 0 COMMENT '排序号，越小越靠前',
  created_at         DATETIME     NOT NULL COMMENT '创建时间',
  updated_at         DATETIME     NOT NULL COMMENT '最后更新时间',
  deleted            TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除标记：0-正常，1-已删除',
  CONSTRAINT uk_mall_product_code UNIQUE (tenant_id, code)
);

-- ----------------------------------------------------------------------------
-- trade_order 订单（金额全部分字段存储，商品名称与价格在下单时快照）
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS trade_order (
  id                  BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '订单ID，主键',
  tenant_id           BIGINT       NOT NULL COMMENT '租户ID，关联 sys_tenant.id',
  order_no            VARCHAR(64)  NOT NULL COMMENT '订单号，租户内唯一，对外展示',
  user_id             BIGINT       NOT NULL COMMENT '下单用户ID，关联 sys_user.id',
  product_id          BIGINT       NOT NULL COMMENT '商品ID，关联 mall_product.id',
  product_name        VARCHAR(128) NOT NULL COMMENT '下单时的商品名称快照',
  quantity            INT          NOT NULL DEFAULT 1 COMMENT '购买数量',
  total_amount_fen    BIGINT       NOT NULL DEFAULT 0 COMMENT '商品总额（分）',
  discount_amount_fen BIGINT       NOT NULL DEFAULT 0 COMMENT '优惠金额（分）',
  pay_amount_fen      BIGINT       NOT NULL DEFAULT 0 COMMENT '应付金额（分）= 总额 - 优惠，支付回调按此校验',
  status              VARCHAR(24)  NOT NULL DEFAULT 'PENDING_PAYMENT' COMMENT '状态：PENDING_PAYMENT-待支付，PAID-已支付（渠道已确认），COMPLETED-已完成（权益已发放），CLOSED-已超时关闭，REFUNDED-已退款',
  expire_at           DATETIME     NULL COMMENT '支付超时时间，超时后订单自动关闭',
  paid_at             DATETIME     NULL COMMENT '支付成功时间',
  remark              VARCHAR(255) NULL COMMENT '订单备注',
  created_at          DATETIME     NOT NULL COMMENT '创建时间（下单时间）',
  updated_at          DATETIME     NOT NULL COMMENT '最后更新时间',
  deleted             TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除标记：0-正常，1-已删除',
  CONSTRAINT uk_trade_order_no UNIQUE (tenant_id, order_no)
);

-- ----------------------------------------------------------------------------
-- trade_payment 支付单（一笔订单可有多条支付单，按 pay_no 幂等）
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS trade_payment (
  id             BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '支付单ID，主键',
  tenant_id      BIGINT       NOT NULL COMMENT '租户ID，关联 sys_tenant.id',
  order_id       BIGINT       NOT NULL COMMENT '订单ID，关联 trade_order.id',
  order_no       VARCHAR(64)  NOT NULL COMMENT '订单号（快照，便于渠道对账查询）',
  pay_no         VARCHAR(64)  NOT NULL COMMENT '支付单号，租户内唯一，作为第三方支付商户订单号',
  channel        VARCHAR(16)  NOT NULL DEFAULT 'WECHAT' COMMENT '支付渠道：WECHAT-微信支付',
  amount_fen     BIGINT       NOT NULL DEFAULT 0 COMMENT '支付金额（分），回调时与订单应付金额核对',
  status         VARCHAR(24)  NOT NULL DEFAULT 'CREATED' COMMENT '状态：CREATED-已创建待支付，PAID-渠道已确认支付，CLOSED-已关闭；PAID 后同一回调按 pay_no 幂等',
  transaction_id VARCHAR(64)  NULL COMMENT '渠道交易号（微信 transaction_id），用于对账与退款',
  prepay_json    TEXT         NULL COMMENT '渠道预下单返回参数 JSON（如 prepay_id），供前端拉起支付',
  paid_at        DATETIME     NULL COMMENT '支付成功时间',
  created_at     DATETIME     NOT NULL COMMENT '创建时间',
  updated_at     DATETIME     NOT NULL COMMENT '最后更新时间',
  deleted        TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除标记：0-正常，1-已删除',
  CONSTRAINT uk_trade_payment_no UNIQUE (tenant_id, pay_no)
);

-- ----------------------------------------------------------------------------
-- trade_payment_notify 支付回调流水（先落库再验签处理，保证可追溯可重放）
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS trade_payment_notify (
  id            BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '回调流水ID，主键',
  tenant_id     BIGINT       NOT NULL COMMENT '租户ID，关联 sys_tenant.id',
  pay_no        VARCHAR(64)  NULL COMMENT '支付单号，关联 trade_payment.pay_no；解析失败时为空',
  channel       VARCHAR(16)  NOT NULL DEFAULT 'WECHAT' COMMENT '支付渠道：WECHAT-微信支付',
  raw_body      TEXT         NULL COMMENT '回调原始报文，原样留存以便排查与重放',
  verify_result VARCHAR(32)  NULL COMMENT '验签结果：PASS-校验通过，SIGN_INVALID-验签失败，TRADE_STATE_xxx-交易状态非成功，CURRENCY_MISMATCH-币种不符，PAYMENT_NOT_FOUND-支付单不存在，AMOUNT_MISMATCH-金额不符，DUPLICATE_NOTIFY-重复回调',
  processed     TINYINT      NOT NULL DEFAULT 0 COMMENT '是否已处理：0-未处理，1-已处理',
  created_at    DATETIME     NOT NULL COMMENT '回调接收时间',
  updated_at    DATETIME     NOT NULL COMMENT '最后更新时间',
  deleted       TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除标记：0-正常，1-已删除'
);

-- ----------------------------------------------------------------------------
-- entitlement_account 权益账户（按用户 + 权益类型 + 资源维度记录余额）
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS entitlement_account (
  id               BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '权益账户ID，主键',
  tenant_id        BIGINT      NOT NULL COMMENT '租户ID，关联 sys_tenant.id',
  user_id          BIGINT      NOT NULL COMMENT '用户ID，关联 sys_user.id',
  entitlement_type VARCHAR(24) NOT NULL COMMENT '权益类型：MEMBER-平台会员，QUESTION_BANK-题库，COURSE-课程，EXAM-考试资格',
  resource_type    VARCHAR(32) NULL COMMENT '资源类型：COURSE / PROJECT / SUBJECT 等',
  resource_id      BIGINT      NULL COMMENT '资源ID，与 resource_type 组合使用；为空表示通用权益',
  total_amount     INT         NOT NULL DEFAULT 0 COMMENT '累计发放数量（课程类为 1，次数类为次数）',
  used_amount      INT         NOT NULL DEFAULT 0 COMMENT '已核销数量',
  status           VARCHAR(16) NOT NULL DEFAULT 'AVAILABLE' COMMENT '状态：AVAILABLE-可用，PART_USED-部分使用，USED-已用完，REVOKED-已作废，EXPIRED-已过期',
  expire_at        DATETIME    NULL COMMENT '权益过期时间，为空表示永久有效',
  source_order_id  BIGINT      NULL COMMENT '来源订单ID，关联 trade_order.id；组织授权时为空',
  created_at       DATETIME    NOT NULL COMMENT '创建时间',
  updated_at       DATETIME    NOT NULL COMMENT '最后更新时间',
  deleted          TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除标记：0-正常，1-已删除',
  CONSTRAINT uk_entitlement_account UNIQUE (tenant_id, user_id, entitlement_type, resource_id)
);

-- ----------------------------------------------------------------------------
-- entitlement_ledger 权益流水（发放 / 核销 / 作废均落流水，event_id 保证幂等）
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS entitlement_ledger (
  id               BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '流水ID，主键',
  tenant_id        BIGINT      NOT NULL COMMENT '租户ID，关联 sys_tenant.id',
  user_id          BIGINT      NOT NULL COMMENT '用户ID，关联 sys_user.id',
  account_id       BIGINT      NOT NULL COMMENT '权益账户ID，关联 entitlement_account.id',
  entitlement_type VARCHAR(24) NOT NULL COMMENT '权益类型，与账户保持一致',
  resource_id      BIGINT      NULL COMMENT '资源ID，与账户保持一致',
  change_type      VARCHAR(24) NOT NULL COMMENT '变动类型：GRANT-发放，USE-核销，REVOKE-退款回收；change_amount 正数增加、负数减少',
  change_amount    INT         NOT NULL DEFAULT 0 COMMENT '变动数量，正数增加、负数减少',
  balance_after    INT         NOT NULL DEFAULT 0 COMMENT '变动后的可用余额（快照）',
  source_order_id  BIGINT      NULL COMMENT '来源订单ID，关联 trade_order.id',
  event_id         VARCHAR(64) NULL COMMENT '业务事件ID，与权益类型、资源ID 组成唯一键，重复回调不重复发放',
  expire_at        DATETIME    NULL COMMENT '本次发放权益的过期时间',
  created_at       DATETIME    NOT NULL COMMENT '创建时间（变动时间）',
  updated_at       DATETIME    NOT NULL COMMENT '最后更新时间',
  deleted          TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除标记：0-正常，1-已删除',
  CONSTRAINT uk_entitlement_ledger_event UNIQUE (tenant_id, event_id, entitlement_type, resource_id)
);

-- ----------------------------------------------------------------------------
-- trade_refund 退款单（首期支持整单全额退款，审核通过后回收未使用权益）
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS trade_refund (
  id           BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '退款单ID，主键',
  tenant_id    BIGINT       NOT NULL COMMENT '租户ID，关联 sys_tenant.id',
  refund_no    VARCHAR(64)  NOT NULL COMMENT '退款单号，租户内唯一',
  order_id     BIGINT       NOT NULL COMMENT '订单ID，关联 trade_order.id',
  order_no     VARCHAR(64)  NOT NULL COMMENT '订单号（快照，便于渠道对账）',
  user_id      BIGINT       NOT NULL COMMENT '申请用户ID，关联 sys_user.id',
  pay_no       VARCHAR(64)  NULL COMMENT '原支付单号，关联 trade_payment.pay_no',
  amount_fen   BIGINT       NOT NULL DEFAULT 0 COMMENT '退款金额（分）',
  reason       VARCHAR(255) NULL COMMENT '用户填写的退款原因',
  status       VARCHAR(24)  NOT NULL DEFAULT 'APPLIED' COMMENT '状态：APPLIED-待审核，SUCCESS-退款成功，REJECTED-已驳回，FAILED-退款失败',
  audit_remark VARCHAR(255) NULL COMMENT '审核备注（驳回原因等）',
  audited_by   BIGINT       NULL COMMENT '审核人用户ID，关联 sys_user.id',
  applied_at   DATETIME     NULL COMMENT '申请时间',
  audited_at   DATETIME     NULL COMMENT '审核时间',
  refunded_at  DATETIME     NULL COMMENT '退款到账时间',
  created_at   DATETIME     NOT NULL COMMENT '创建时间',
  updated_at   DATETIME     NOT NULL COMMENT '最后更新时间',
  deleted      TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除标记：0-正常，1-已删除',
  CONSTRAINT uk_trade_refund_no UNIQUE (tenant_id, refund_no)
);

-- ----------------------------------------------------------------------------
-- exam_paper_rule 规则组卷规则（按题型 / 难度 / 题数 / 单题分值抽题）
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS exam_paper_rule (
  id         BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '规则ID，主键',
  tenant_id  BIGINT      NOT NULL COMMENT '租户ID，关联 sys_tenant.id',
  paper_id   BIGINT      NOT NULL COMMENT '试卷ID，关联 exam_paper.id',
  type       VARCHAR(16) NOT NULL COMMENT '题型：SINGLE-单选，MULTIPLE-多选，JUDGE-判断，ESSAY-主观题',
  count      INT         NOT NULL DEFAULT 0 COMMENT '抽取题数',
  score      INT         NOT NULL DEFAULT 0 COMMENT '单题分值',
  difficulty VARCHAR(16) NULL COMMENT '难度过滤：EASY / NORMAL / HARD；为空表示不限',
  sort_no    INT         NOT NULL DEFAULT 0 COMMENT '规则顺序号，决定组卷时的题目排列顺序',
  created_at DATETIME    NOT NULL COMMENT '创建时间',
  updated_at DATETIME    NOT NULL COMMENT '最后更新时间',
  deleted    TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除标记：0-正常，1-已删除'
);

-- ----------------------------------------------------------------------------
-- exam_marking 人工阅卷记录（主观题逐题打分，同一问答卷仅一条）
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS exam_marking (
  id          BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '阅卷记录ID，主键',
  tenant_id   BIGINT       NOT NULL COMMENT '租户ID，关联 sys_tenant.id',
  attempt_id  BIGINT       NOT NULL COMMENT '答卷ID，关联 exam_attempt.id',
  question_id BIGINT       NOT NULL COMMENT '题目ID，关联 qb_question.id',
  score       INT          NOT NULL DEFAULT 0 COMMENT '本题得分',
  full_score  INT          NOT NULL DEFAULT 0 COMMENT '本题满分，用于校验不得超分',
  remark      VARCHAR(500) NULL COMMENT '阅卷评语',
  marker_id   BIGINT       NULL COMMENT '阅卷人用户ID，关联 sys_user.id',
  marked_at   DATETIME     NULL COMMENT '阅卷时间',
  created_at  DATETIME     NOT NULL COMMENT '创建时间',
  updated_at  DATETIME     NOT NULL COMMENT '最后更新时间',
  deleted     TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除标记：0-正常，1-已删除',
  CONSTRAINT uk_exam_marking UNIQUE (tenant_id, attempt_id, question_id)
);

-- ----------------------------------------------------------------------------
-- exam_result_history 成绩修订历史（成绩更正必须填原因并留存旧版本）
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS exam_result_history (
  id          BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '历史版本ID，主键',
  tenant_id   BIGINT        NOT NULL COMMENT '租户ID，关联 sys_tenant.id',
  attempt_id  BIGINT        NOT NULL COMMENT '答卷ID，关联 exam_attempt.id',
  revision    INT           NOT NULL DEFAULT 1 COMMENT '修订版本号，与 exam_result.revision 对应',
  operation   VARCHAR(24)   NOT NULL COMMENT '操作类型：PUBLISH-成绩发布，CORRECT-成绩更正',
  total_score DECIMAL(10,2) NOT NULL DEFAULT 0 COMMENT '本次修订后的总分',
  detail_json TEXT          NULL COMMENT '本次修订后的逐题判分明细 JSON 快照',
  operator_id BIGINT        NULL COMMENT '操作人用户ID，关联 sys_user.id',
  remark      VARCHAR(500)  NULL COMMENT '更正原因 / 操作说明（必填）',
  created_at  DATETIME      NOT NULL COMMENT '创建时间（修订时间）',
  updated_at  DATETIME      NOT NULL COMMENT '最后更新时间',
  deleted     TINYINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除标记：0-正常，1-已删除'
);

-- ----------------------------------------------------------------------------
-- msg_task 消息任务（业务事件先落任务再投递，失败按退避重试）
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS msg_task (
  id            BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '消息任务ID，主键',
  tenant_id     BIGINT       NOT NULL COMMENT '租户ID，关联 sys_tenant.id',
  user_id       BIGINT       NOT NULL COMMENT '接收用户ID，关联 sys_user.id',
  scene         VARCHAR(32)  NOT NULL COMMENT '业务场景：EXAM_REGISTER-报名成功，PAYMENT_SUCCESS-支付成功，RESULT_PUBLISHED-成绩发布',
  template_code VARCHAR(64)  NOT NULL COMMENT '消息模板编码',
  payload_json  TEXT         NULL COMMENT '模板参数 JSON（如场次名称、时间、分数）',
  biz_key       VARCHAR(128) NULL COMMENT '业务幂等键，同一场景同一业务事件不重复建任务',
  status        VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT '状态：PENDING-待发送，SENT-已发送，FAILED-发送失败，SKIPPED-未授权跳过',
  retry_count   INT          NOT NULL DEFAULT 0 COMMENT '已重试次数',
  next_retry_at DATETIME     NULL COMMENT '下次重试时间（退避策略计算）',
  scheduled_at  DATETIME     NULL COMMENT '计划发送时间，用于定时提醒',
  sent_at       DATETIME     NULL COMMENT '实际发送成功时间',
  error_msg     VARCHAR(500) NULL COMMENT '最近一次失败原因',
  created_at    DATETIME     NOT NULL COMMENT '创建时间',
  updated_at    DATETIME     NOT NULL COMMENT '最后更新时间',
  deleted       TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除标记：0-正常，1-已删除',
  CONSTRAINT uk_msg_task_biz UNIQUE (tenant_id, scene, biz_key, user_id)
);

-- ----------------------------------------------------------------------------
-- msg_send_log 消息发送日志（每次调用渠道都留原始请求与响应）
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS msg_send_log (
  id            BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '发送日志ID，主键',
  tenant_id     BIGINT       NOT NULL COMMENT '租户ID，关联 sys_tenant.id',
  task_id       BIGINT       NOT NULL COMMENT '消息任务ID，关联 msg_task.id',
  user_id       BIGINT       NOT NULL COMMENT '接收用户ID，关联 sys_user.id',
  channel       VARCHAR(16)  NOT NULL DEFAULT 'WECHAT' COMMENT '发送渠道：WECHAT-微信订阅消息',
  template_code VARCHAR(64)  NOT NULL COMMENT '消息模板编码',
  request_json  TEXT         NULL COMMENT '调用渠道的请求报文 JSON',
  response_json TEXT         NULL COMMENT '渠道返回报文 JSON（含错误码与提示）',
  success       TINYINT      NOT NULL DEFAULT 0 COMMENT '是否成功：0-失败，1-成功',
  error_msg     VARCHAR(500) NULL COMMENT '失败原因',
  created_at    DATETIME     NOT NULL COMMENT '创建时间（发送时间）',
  updated_at    DATETIME     NOT NULL COMMENT '最后更新时间',
  deleted       TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除标记：0-正常，1-已删除'
);

-- ----------------------------------------------------------------------------
-- msg_subscription 订阅消息授权次数（微信一次性订阅，按模板累计剩余次数）
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS msg_subscription (
  id            BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '订阅记录ID，主键',
  tenant_id     BIGINT      NOT NULL COMMENT '租户ID，关联 sys_tenant.id',
  user_id       BIGINT      NOT NULL COMMENT '用户ID，关联 sys_user.id',
  template_code VARCHAR(64) NOT NULL COMMENT '消息模板编码',
  remaining     INT         NOT NULL DEFAULT 0 COMMENT '剩余可发送次数，发送成功后扣减',
  created_at    DATETIME    NOT NULL COMMENT '创建时间',
  updated_at    DATETIME    NOT NULL COMMENT '最后更新时间',
  deleted       TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除标记：0-正常，1-已删除',
  CONSTRAINT uk_msg_subscription UNIQUE (tenant_id, user_id, template_code)
);

-- ----------------------------------------------------------------------------
-- course_info 课程（免费直接可看，付费需持有对应权益）
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS course_info (
  id           BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '课程ID，主键',
  tenant_id    BIGINT       NOT NULL COMMENT '租户ID，关联 sys_tenant.id',
  project_id   BIGINT       NULL COMMENT '关联考试项目ID，关联 exam_project.id；通用课程为空',
  subject_id   BIGINT       NULL COMMENT '关联考试科目ID，关联 exam_subject.id；通用课程为空',
  code         VARCHAR(64)  NOT NULL COMMENT '课程编码，租户内唯一',
  title        VARCHAR(128) NOT NULL COMMENT '课程标题',
  summary      TEXT         NULL COMMENT '课程简介',
  cover_url    VARCHAR(512) NULL COMMENT '封面图地址',
  lecturer     VARCHAR(64)  NULL COMMENT '讲师姓名',
  lesson_count INT          NOT NULL DEFAULT 0 COMMENT '课时数量（冗余，便于列表展示）',
  price_fen    BIGINT       NOT NULL DEFAULT 0 COMMENT '课程价格（分），仅作展示，实付以商品为准',
  access_type  VARCHAR(16)  NOT NULL DEFAULT 'FREE' COMMENT '访问类型：FREE-免费，PAID-付费（需持有权益）',
  status       VARCHAR(16)  NOT NULL DEFAULT 'DRAFT' COMMENT '状态：DRAFT-草稿，PUBLISHED-已发布，OFFLINE-已下线',
  sort_no      INT          NOT NULL DEFAULT 0 COMMENT '排序号，越小越靠前',
  created_at   DATETIME     NOT NULL COMMENT '创建时间',
  updated_at   DATETIME     NOT NULL COMMENT '最后更新时间',
  deleted      TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除标记：0-正常，1-已删除',
  CONSTRAINT uk_course_info_code UNIQUE (tenant_id, code)
);

-- ----------------------------------------------------------------------------
-- course_lesson 课时（图文 + 点播地址；付费课程未购买时正文与视频地址不下发）
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS course_lesson (
  id               BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '课时ID，主键',
  tenant_id        BIGINT       NOT NULL COMMENT '租户ID，关联 sys_tenant.id',
  course_id        BIGINT       NOT NULL COMMENT '所属课程ID，关联 course_info.id',
  title            VARCHAR(128) NOT NULL COMMENT '课时标题',
  content          TEXT         NULL COMMENT '课时图文正文',
  video_url        VARCHAR(512) NULL COMMENT '点播视频地址，预留给点播平台接入',
  duration_seconds INT          NOT NULL DEFAULT 0 COMMENT '课时时长（秒）',
  free_preview     TINYINT      NOT NULL DEFAULT 0 COMMENT '是否可免费试看：0-否，1-是',
  sort_no          INT          NOT NULL DEFAULT 0 COMMENT '课时排序号，越小越靠前',
  status           VARCHAR(16)  NOT NULL DEFAULT 'ENABLED' COMMENT '状态：ENABLED-启用，DISABLED-停用',
  created_at       DATETIME     NOT NULL COMMENT '创建时间',
  updated_at       DATETIME     NOT NULL COMMENT '最后更新时间',
  deleted          TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除标记：0-正常，1-已删除'
);

-- ----------------------------------------------------------------------------
-- course_progress 学习进度（同一用户同一课时仅一条，进度只增不减）
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS course_progress (
  id               BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '学习进度ID，主键',
  tenant_id        BIGINT      NOT NULL COMMENT '租户ID，关联 sys_tenant.id',
  user_id          BIGINT      NOT NULL COMMENT '用户ID，关联 sys_user.id',
  course_id        BIGINT      NOT NULL COMMENT '课程ID，关联 course_info.id',
  lesson_id        BIGINT      NOT NULL COMMENT '课时ID，关联 course_lesson.id',
  progress_seconds INT         NOT NULL DEFAULT 0 COMMENT '已观看进度（秒），以较大值合并，不回退',
  finished         TINYINT     NOT NULL DEFAULT 0 COMMENT '是否学完：0-否，1-是',
  last_viewed_at   DATETIME    NULL COMMENT '最近一次观看时间',
  created_at       DATETIME    NOT NULL COMMENT '创建时间',
  updated_at       DATETIME    NOT NULL COMMENT '最后更新时间',
  deleted          TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除标记：0-正常，1-已删除',
  CONSTRAINT uk_course_progress UNIQUE (tenant_id, user_id, lesson_id)
);

-- ----------------------------------------------------------------------------
-- export_task 报表导出任务（异步执行，产物仅提交人可下载）
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS export_task (
  id          BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '导出任务ID，主键',
  tenant_id   BIGINT        NOT NULL COMMENT '租户ID，关联 sys_tenant.id',
  task_no     VARCHAR(64)   NOT NULL COMMENT '任务编号，租户内唯一，用于轮询与幂等',
  type        VARCHAR(24)   NOT NULL COMMENT '导出类型：RESULT-成绩报表，ORDER-订单报表，REGISTRATION-报名报表',
  params_json TEXT          NULL COMMENT '导出筛选条件 JSON（场次、时间区间等）',
  status      VARCHAR(16)   NOT NULL DEFAULT 'PENDING' COMMENT '状态：PENDING-排队中，RUNNING-执行中，SUCCESS-成功，FAILED-失败',
  file_name   VARCHAR(255)  NULL COMMENT '产物文件名（含 BOM，Excel 可直接打开）',
  file_path   VARCHAR(1024) NULL COMMENT '产物存储路径',
  row_count   INT           NOT NULL DEFAULT 0 COMMENT '导出行数',
  error_msg   VARCHAR(500)  NULL COMMENT '失败原因',
  operator_id BIGINT        NULL COMMENT '提交人用户ID，关联 sys_user.id，仅本人可见可下载',
  started_at  DATETIME      NULL COMMENT '开始执行时间',
  finished_at DATETIME      NULL COMMENT '执行结束时间',
  created_at  DATETIME      NOT NULL COMMENT '创建时间（提交时间）',
  updated_at  DATETIME      NOT NULL COMMENT '最后更新时间',
  deleted     TINYINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除标记：0-正常，1-已删除',
  CONSTRAINT uk_export_task_no UNIQUE (tenant_id, task_no)
);

-- ----------------------------------------------------------------------------
-- 二级索引
-- 说明：基线 DDL 只保留主键与唯一约束，二级索引集中在这里。
--   这些语句在 H2(MODE=MySQL) 与 MySQL 8.x 上均可执行；
--   之所以一并放进基线，是为了让「新库 = 完整结构」可复现，
--   而不是让人再记得手工补一份 mysql-indexes.sql。
-- ----------------------------------------------------------------------------

CREATE INDEX idx_sys_user_tenant_role ON sys_user (tenant_id, role_code);

CREATE INDEX idx_exam_project_status_sort ON exam_project (tenant_id, status, sort_no);
CREATE INDEX idx_exam_subject_project ON exam_subject (tenant_id, project_id, sort_no);
CREATE INDEX idx_exam_kp_subject ON exam_knowledge_point (tenant_id, subject_id);

CREATE INDEX idx_qb_question_subject_status ON qb_question (tenant_id, subject_id, status);

CREATE INDEX idx_exam_paper_project ON exam_paper (tenant_id, project_id);
CREATE INDEX idx_exam_snapshot_paper ON exam_paper_snapshot (tenant_id, paper_id);

CREATE INDEX idx_exam_session_status_start ON exam_session (tenant_id, status, start_at);
CREATE INDEX idx_exam_registration_session ON exam_registration (tenant_id, session_id, status);

CREATE INDEX idx_exam_attempt_user ON exam_attempt (tenant_id, user_id, session_id);
CREATE INDEX idx_exam_attempt_status_deadline ON exam_attempt (status, deadline_at);
CREATE INDEX idx_exam_attempt_registration ON exam_attempt (tenant_id, registration_id);

CREATE INDEX idx_exam_answer_attempt ON exam_answer (tenant_id, attempt_id);
CREATE INDEX idx_exam_result_user ON exam_result (tenant_id, user_id, session_id);

CREATE INDEX idx_learn_practice_user ON learn_practice (tenant_id, user_id, subject_id);
CREATE INDEX idx_learn_answer_user_wrong ON learn_practice_answer (tenant_id, user_id, correct);

CREATE INDEX idx_mall_product_status ON mall_product (tenant_id, status, sort_no);
CREATE INDEX idx_trade_order_user ON trade_order (tenant_id, user_id, status);
CREATE INDEX idx_trade_order_status ON trade_order (tenant_id, status, expire_at);
CREATE INDEX idx_trade_payment_order ON trade_payment (tenant_id, order_id);
CREATE INDEX idx_trade_notify_pay_no ON trade_payment_notify (tenant_id, pay_no);

CREATE INDEX idx_entitlement_account_user ON entitlement_account (tenant_id, user_id, entitlement_type);
CREATE INDEX idx_entitlement_ledger_user ON entitlement_ledger (tenant_id, user_id, created_at);

CREATE INDEX idx_sys_audit_log_created ON sys_audit_log (tenant_id, created_at);

