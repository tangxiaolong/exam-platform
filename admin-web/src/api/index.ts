import { request, type PageResult } from './request'

export interface UserView {
  id: number
  username: string
  nickname?: string
  avatar?: string
  phone?: string
  roleCode: string
  status?: string
  tenantId: number
}

export interface LoginResponse {
  token: string
  tokenType: string
  expiresIn: number
  user: UserView
}

export interface Overview {
  userCount: number
  projectCount: number
  questionCount: number
  sessionCount: number
  registrationCount: number
  attemptCount: number
  orderCount: number
  paidOrderCount: number
  paidAmountFen: number
}

export interface Project {
  id?: number
  code: string
  name: string
  intro?: string
  coverUrl?: string
  targetAudience?: string
  status?: string
  sortNo?: number
}

export interface Subject {
  id?: number
  projectId: number
  code: string
  name: string
  intro?: string
  status?: string
  sortNo?: number
}

export interface OptionItem {
  key: string
  text: string
}

export interface Question {
  id: number
  subjectId: number
  /** 知识点：为空表示未标注，组卷时按知识点抽题不会抽到 */
  knowledgePointId?: number | null
  type: string
  stem: string
  options: OptionItem[]
  answer?: string[]
  analysis?: string
  difficulty?: string
  tags?: string
  source?: string
  status?: string
  version?: number
}

export interface Paper {
  id?: number
  projectId: number
  subjectId?: number
  name: string
  mode?: string
  totalScore?: number
  questionJson?: string
  status?: string
  version?: number
}

export interface ExamSession {
  id?: number
  projectId: number
  paperId: number
  snapshotId?: number
  name: string
  registerStartAt?: string
  registerEndAt?: string
  startAt: string
  endAt: string
  durationMinutes?: number
  allowEnterBeforeMinutes?: number
  lateLimitMinutes?: number
  resultPublishAt?: string
  quota?: number
  noticeVersion?: string
  noticeContent?: string
  status?: string
}

export interface Order {
  id: number
  orderNo: string
  userId: number
  productId: number
  productName: string
  quantity: number
  /** 明细原价合计（分），未扣券 */
  totalAmountFen?: number
  /** 优惠券抵扣（分） */
  discountAmountFen?: number
  /** 实付（分） */
  payAmountFen: number
  status: string
  /** 未支付订单的自动关闭时间 */
  expireAt?: string
  couponGrantId?: number | null
  itemCount?: number
  remark?: string
  paidAt?: string
  createdAt?: string
}

/** 订单明细：名称与单价都是下单时的快照，退款按行计算 */
export interface OrderItem {
  id: number
  orderId: number
  productId: number
  skuId: number
  productName: string
  skuName: string
  unitPriceFen: number
  quantity: number
  amountFen: number
  refundedAmountFen: number
  durationDays?: number | null
}

/**
 * 交易与财务概览：五个二级页面顶部指标条的唯一数据源。
 *
 * <p>金额单位是分。一次性取回四组状态计数，避免每页各发四五个
 * `pageSize=1` 的分页请求去凑 total。</p>
 */
export interface TradeOverview {
  orderTotal: number
  orderPendingPayment: number
  orderPaid: number
  orderCompleted: number
  orderClosed: number
  orderRefunded: number
  paidAmountFen: number
  todayPaidCount: number
  todayPaidAmountFen: number
  refundPending: number
  refundSuccess: number
  refundRejected: number
  refundedAmountFen: number
  productOnSale: number
  productOffSale: number
  couponActive: number
  couponStopped: number
  couponClaimed: number
}

export interface Product {
  id?: number
  code: string
  name: string
  type: string
  description?: string
  priceFen: number
  originalPriceFen?: number
  validityDays?: number
  resourceType?: string
  resourceId?: number
  status?: string
  sortNo?: number
}

export interface Refund {
  id: number
  refundNo: string
  orderId: number
  orderNo: string
  userId: number
  amountFen: number
  reason?: string
  status: string
  auditRemark?: string
  appliedAt?: string
  auditedAt?: string
  refundedAt?: string
}

export const authApi = {
  login: (data: { username: string; password: string }) =>
    request<LoginResponse>({ url: '/auth/login', method: 'post', data }),
  me: () => request<UserView>({ url: '/users/me' })
}

export const dashboardApi = {
  overview: () => request<Overview>({ url: '/admin/dashboard/overview' }),
  // 近 N 日趋势：每日报名/答卷/已付订单/收入（分）
  trends: (days = 7) => request<any[]>({ url: '/admin/dashboard/trends', params: { days } })
}

export const configApi = {
  // 系统参数：对接既有 /admin/configs（NoticeController 中的 configs 端点）
  list: () => request<any[]>({ url: '/admin/configs' }),
  save: (key: string, data: { value: string; remark?: string }) =>
    request<void>({ url: `/admin/configs/${key}`, method: 'put', data })
}

export const projectApi = {
  page: (params: any) => request<PageResult<Project>>({ url: '/admin/projects', params }),
  create: (data: Project) => request<Project>({ url: '/admin/projects', method: 'post', data }),
  update: (id: number, data: Project) =>
    request<Project>({ url: `/admin/projects/${id}`, method: 'put', data }),
  remove: (id: number) => request<void>({ url: `/admin/projects/${id}`, method: 'delete' }),
  subjects: (id: number) => request<Subject[]>({ url: `/admin/projects/${id}/subjects` }),
  createSubject: (data: Subject) => request<Subject>({ url: '/admin/subjects', method: 'post', data }),
  updateSubject: (id: number, data: Subject) =>
    request<Subject>({ url: `/admin/subjects/${id}`, method: 'put', data }),
  removeSubject: (id: number) => request<void>({ url: `/admin/subjects/${id}`, method: 'delete' })
}

export interface ImportRow {
  lineNo: number
  status: 'SUCCESS' | 'FAILED'
  errorCode?: string | null
  errorMessage?: string | null
  questionId?: number | null
  stem?: string | null
}

/** 与后端 `ImportReport` 对齐：整批全过才落库（committed=false 表示整批未写入） */
export interface ImportReport {
  totalRows: number
  successCount: number
  failureCount: number
  committed: boolean
  rows: ImportRow[]
}

export const questionApi = {
  page: (params: any) => request<PageResult<Question>>({ url: '/admin/questions', params }),
  detail: (id: number) => request<Question>({ url: `/admin/questions/${id}` }),
  create: (data: any) => request<Question>({ url: '/admin/questions', method: 'post', data }),
  update: (id: number, data: any) =>
    request<Question>({ url: `/admin/questions/${id}`, method: 'put', data }),
  remove: (id: number) => request<void>({ url: `/admin/questions/${id}`, method: 'delete' }),
  changeStatus: (id: number, status: string) =>
    request<void>({ url: `/admin/questions/${id}/status`, method: 'put', params: { status } }),
  // 批量启停：一次最多 200 条，服务端逐条走单条校验路径
  batchStatus: (ids: number[], status: string) =>
    request<number>({ url: '/admin/questions/batch-status', method: 'put', data: { ids, status } }),
  // 批量移动科目：一次最多 200 条，服务端校验目标科目存在
  batchSubject: (ids: number[], subjectId: number) =>
    request<number>({ url: '/admin/questions/batch-subject', method: 'put', data: { ids, subjectId } }),
  // 导入复用既有的 CSV 子系统：上传文件 → 逐行校验 → 全过才提交
  importCsv: (file: File) => {
    const form = new FormData()
    form.append('file', file)
    return request<ImportReport>({ url: '/admin/questions/imports', method: 'post', data: form })
  },
  // 模板里的示例用当前租户真实科目编码，可以原样导一遍先验证链路
  templateBlob: () =>
    request<Blob>({ url: '/admin/questions/imports/template', responseType: 'blob' })
}

export const paperApi = {
  page: (params: any) => request<PageResult<Paper>>({ url: '/admin/papers', params }),
  detail: (id: number) => request<Paper>({ url: `/admin/papers/${id}` }),
  snapshots: (id: number) => request<any[]>({ url: `/admin/papers/${id}/snapshots` }),
  create: (data: any) => request<Paper>({ url: '/admin/papers', method: 'post', data }),
  update: (id: number, data: any) => request<Paper>({ url: `/admin/papers/${id}`, method: 'put', data }),
  remove: (id: number) => request<void>({ url: `/admin/papers/${id}`, method: 'delete' }),
  preview: (id: number) => request<any>({ url: `/admin/papers/${id}/preview`, method: 'post' }),
  rules: (id: number) => request<any[]>({ url: `/admin/papers/${id}/rules` }),
  // 复制试卷：沿用组卷规则，只换名称，生成新草稿
  clone: (id: number, name: string) =>
    request<Paper>({ url: `/admin/papers/${id}/clone`, method: 'post', params: { name } })
}

export interface QuestionUsageItem {
  questionId: number
  stem: string
  type: string
  subjectId: number | null
  appearedCount: number
  correctCount: number
  accuracy: number
}

/**
 * 题库使用分析（跨场次）。
 *
 * <p>口径：最近 N 天已判分答卷里，每道题被抽中多少次、答对多少次；正确率最低的在前。
 * 出现次数很少的题，正确率再低也只说明样本不足——两张数字要一起看。</p>
 */
export const questionUsageApi = {
  analyze: (params: any) =>
    request<QuestionUsageItem[]>({ url: '/admin/question-usage', params })
}

export interface AnalyticsBand {
  label: string
  count: number
}

/** 与后端 `ExamReportView` 对齐：只统计已判分/已发布成绩，阅卷中的答卷不参与汇总 */
export interface SessionAnalytics {
  participantCount: number
  avgScore: number
  maxScore: number
  minScore: number
  passRate: number
  fullScore: number
  passPercent: number
  distribution: AnalyticsBand[]
}

export const sessionApi = {
  page: (params: any) => request<PageResult<ExamSession>>({ url: '/admin/sessions', params }),
  create: (data: any) => request<ExamSession>({ url: '/admin/sessions', method: 'post', data }),
  update: (id: number, data: any) =>
    request<ExamSession>({ url: `/admin/sessions/${id}`, method: 'put', data }),
  remove: (id: number) => request<void>({ url: `/admin/sessions/${id}`, method: 'delete' }),
  // 多场次成绩横向对比：1-10 个场次，无成绩场次也返回（count=0）
  compare: (ids: number[]) =>
    request<any[]>({ url: '/admin/sessions/compare', params: { ids: ids.join(',') } }),
  // 对比表 CSV 同步下载（与抽屉同源同口径）
  compareExport: (ids: number[]) =>
    request<Blob>({ url: '/admin/sessions/compare/export', params: { ids: ids.join(',') }, responseType: 'blob' }),
  publish: (id: number) => request<ExamSession>({ url: `/admin/sessions/${id}/publish`, method: 'post' }),
  close: (id: number) => request<void>({ url: `/admin/sessions/${id}/close`, method: 'post' }),
  // 复制场次：沿用源场次规则，只换名称与时间窗，生成新草稿
  clone: (id: number, data: any) =>
    request<ExamSession>({ url: `/admin/sessions/${id}/clone`, method: 'post', data }),
  registrations: (id: number) => request<any[]>({ url: `/admin/sessions/${id}/registrations` }),
  attempts: (id: number) => request<any[]>({ url: `/admin/sessions/${id}/attempts` }),
  forceSubmit: (attemptId: number) =>
    request<any>({ url: `/admin/attempts/${attemptId}/force-submit`, method: 'post' }),
  // 成绩分析：人数/均分/最高最低/及格率/五段分布；passPercent 由后端返回，前端不写死
  analytics: (id: number) =>
    request<SessionAnalytics>({ url: `/admin/sessions/${id}/analytics` }),
  // 逐题正确率：正确率最低的题在最前
  questionAccuracy: (id: number) =>
    request<QuestionAccuracyItem[]>({ url: `/admin/sessions/${id}/question-accuracy` }),
  // 场次成绩排行：同分并列、后续跳号（1,1,3 式）
  leaderboard: (id: number, limit = 50) =>
    request<LeaderboardEntry[]>({ url: `/admin/sessions/${id}/leaderboard`, params: { limit } })
}

export interface QuestionAccuracyItem {
  questionId: number
  stem: string
  type: string
  answered: number
  correct: number
  accuracy: number
}

export interface LeaderboardEntry {
  rank: number
  userId: number
  userName: string
  totalScore: number
}

export const tradeApi = {
  /** 概览指标：订单/退款/商品/券四组状态计数，四个页面顶部共用 */
  overview: () => request<TradeOverview>({ url: '/admin/trade/overview' }),
  /** 商品分页：status / type 为可选精确筛选，keyword 同时匹配编码与名称 */
  products: (params: any) => request<PageResult<Product>>({ url: '/admin/products', params }),
  createProduct: (data: Product) => request<Product>({ url: '/admin/products', method: 'post', data }),
  updateProduct: (id: number, data: Product) =>
    request<Product>({ url: `/admin/products/${id}`, method: 'put', data }),
  orders: (params: any) => request<PageResult<Order>>({ url: '/admin/orders', params }),
  // 订单明细：核对「这一单买的是哪几行、各自退了多少」
  orderItems: (id: number) => request<OrderItem[]>({ url: `/admin/orders/${id}/items` }),
  refunds: (params: any) => request<PageResult<Refund>>({ url: '/admin/refunds', params }),
  approveRefund: (id: number, remark?: string) =>
    request<Refund>({ url: `/admin/refunds/${id}/approve`, method: 'post', params: { remark } }),
  rejectRefund: (id: number, remark?: string) =>
    request<Refund>({ url: `/admin/refunds/${id}/reject`, method: 'post', params: { remark } })
}

export interface MarkingItem {
  questionId: number
  type: string
  stem: string
  referenceAnswer?: string[]
  givenAnswer?: string[]
  fullScore: number
  score?: number
  remark?: string
  marked: boolean
}

export const markingApi = {
  queue: (params: any) => request<PageResult<any>>({ url: '/admin/marking/queue', params }),
  list: (attemptId: number) =>
    request<MarkingItem[]>({ url: `/admin/attempts/${attemptId}/marking` }),
  mark: (attemptId: number, questionId: number, score: number, remark?: string) =>
    request<MarkingItem>({
      url: `/admin/attempts/${attemptId}/marking/${questionId}`,
      method: 'put',
      params: { score, remark }
    }),
  finish: (attemptId: number) =>
    request<any>({ url: `/admin/attempts/${attemptId}/marking/finish`, method: 'post' }),
  // 答卷全明细（客观题+主观题），供成绩申诉/客服核对
  detail: (attemptId: number) =>
    request<any>({ url: `/admin/attempts/${attemptId}/results` }),
  correct: (attemptId: number, questionId: number, score: number, reason: string) =>
    request<any>({
      url: `/admin/attempts/${attemptId}/results/correct`,
      method: 'post',
      params: { questionId, score, reason }
    }),
  history: (attemptId: number) =>
    request<any[]>({ url: `/admin/attempts/${attemptId}/results/history` })
}

export interface CouponItem {
  id: number
  code: string
  name: string
  type: string
  thresholdFen: number
  valueFen: number
  discountPercent: number
  maxDiscountFen: number | null
  totalQuantity: number
  perUserLimit: number
  claimedQuantity: number
  scopeType: string
  scopeId: number | null
  validFrom: string
  validTo: string
  status: string
}

/**
 * 管理端优惠券。
 *
 * <p>金额单位为分；折扣百分比 85 表示 85 折。创建后立即上架（ACTIVE），
 * 停发用 STOPPED——考生侧领取按同一状态校验，不存在「后台建了却领不了」的分叉。</p>
 */
export const couponAdminApi = {
  page: (params: any) => request<PageResult<CouponItem>>({ url: '/admin/coupons', params }),
  create: (data: any) => request<CouponItem>({ url: '/admin/coupons', method: 'post', data }),
  updateStatus: (id: number, status: string) =>
    request<CouponItem>({ url: `/admin/coupons/${id}/status`, method: 'put', params: { status } }),
  // 领取记录：这张券被谁领了、用到哪一步
  grants: (id: number, params: any) =>
    request<PageResult<CouponGrantAdminRow>>({ url: `/admin/coupons/${id}/grants`, params })
}

export interface CouponGrantAdminRow {
  grantId: number
  userId: number
  username: string
  status: string
  discountFen: number | null
  orderId: number | null
  grantedAt: string | null
  usedAt: string | null
}

export interface MsgTask {
  id: number
  userId: number
  scene: string
  templateCode: string
  payloadJson?: string
  bizKey?: string
  status: string
  retryCount: number
  nextRetryAt?: string
  sentAt?: string
  errorMsg?: string
  createdAt?: string
}

export const messageApi = {
  page: (params: any) => request<PageResult<MsgTask>>({ url: '/admin/messages', params }),
  // 消息模板：场景文案在线编辑（模板码维度 upsert，后端审计 MESSAGE_TEMPLATE_UPDATE）
  templates: () => request<any[]>({ url: '/admin/messages/templates' }),
  upsertTemplate: (code: string, data: { title: string; content: string; enabled?: boolean }) =>
    request<any>({ url: `/admin/messages/templates/${code}`, method: 'put', data }),
  logs: (taskId: number) => request<any[]>({ url: `/admin/messages/${taskId}/logs` }),
  resend: (taskId: number) =>
    request<MsgTask>({ url: `/admin/messages/${taskId}/resend`, method: 'post' }),
  // 站内信群发：userIds 缺省 = 全体非管理员账号，返回触达人数
  sendInbox: (data: { title: string; content: string; userIds?: number[] }) =>
    request<number>({ url: '/admin/inbox', method: 'post', data }),
  // 发送历史：按时间倒序的收件记录（含收件人与已读状态）
  inboxHistory: (params: any) =>
    request<PageResult<any>>({ url: '/admin/inbox/history', params }),
  // 批次聚合：一行 = 一次群发（含收件总数与已读数；历史无批次行按单行成组）
  inboxBatches: (params: any) =>
    request<PageResult<any>>({ url: '/admin/inbox/batches', params })
}

export interface Course {
  id?: number
  projectId?: number
  subjectId?: number
  code: string
  title: string
  summary?: string
  coverUrl?: string
  lecturer?: string
  lessonCount?: number
  priceFen?: number
  accessType?: string
  status?: string
  sortNo?: number
}

export interface CourseLesson {
  id?: number
  courseId?: number
  title: string
  content?: string
  videoUrl?: string
  durationSeconds?: number
  freePreview?: number
  sortNo?: number
  status?: string
}

export const courseApi = {
  page: (params: any) => request<PageResult<Course>>({ url: '/admin/courses', params }),
  detail: (id: number) => request<Course>({ url: `/admin/courses/${id}` }),
  create: (data: any) => request<Course>({ url: '/admin/courses', method: 'post', data }),
  update: (id: number, data: any) => request<Course>({ url: `/admin/courses/${id}`, method: 'put', data }),
  remove: (id: number) => request<void>({ url: `/admin/courses/${id}`, method: 'delete' }),
  lessons: (id: number) => request<CourseLesson[]>({ url: `/admin/courses/${id}/lessons` }),
  createLesson: (id: number, data: any) =>
    request<CourseLesson>({ url: `/admin/courses/${id}/lessons`, method: 'post', data }),
  updateLesson: (lessonId: number, data: any) =>
    request<CourseLesson>({ url: `/admin/courses/lessons/${lessonId}`, method: 'put', data }),
  removeLesson: (lessonId: number) =>
    request<void>({ url: `/admin/courses/lessons/${lessonId}`, method: 'delete' })
}

export interface Org {
  id?: number
  code: string
  name: string
  type?: string
  contactName?: string
  contactPhone?: string
  status?: string
  remark?: string
}

export interface OrgMember {
  id?: number
  orgId?: number
  userId: number
  role?: string
  status?: string
}

export const orgApi = {
  page: (params: any) => request<PageResult<Org>>({ url: '/admin/orgs', params }),
  detail: (id: number) => request<Org>({ url: `/admin/orgs/${id}` }),
  create: (data: Org) => request<Org>({ url: '/admin/orgs', method: 'post', data }),
  update: (id: number, data: Org) => request<Org>({ url: `/admin/orgs/${id}`, method: 'put', data }),
  remove: (id: number) => request<void>({ url: `/admin/orgs/${id}`, method: 'delete' }),
  members: (id: number) => request<OrgMember[]>({ url: `/admin/orgs/${id}/members` }),
  addMember: (id: number, data: { userId: number; role?: string }) =>
    request<OrgMember>({ url: `/admin/orgs/${id}/members`, method: 'post', data }),
  removeMember: (id: number, userId: number) =>
    request<void>({ url: `/admin/orgs/${id}/members/${userId}`, method: 'delete' }),
  grant: (id: number, data: any) =>
    request<any>({ url: `/admin/orgs/${id}/grants`, method: 'post', data }),
  quotas: (id: number) => request<OrgQuota[]>({ url: `/admin/orgs/${id}/quotas` }),
  quotaRecharges: (id: number) =>
    request<OrgQuotaRecharge[]>({ url: `/admin/orgs/${id}/quota-recharges` }),
  /** 已过期的额度账户（含未用余额）：过期只影响能否继续消耗，账户本身不删除。 */
  expiredQuotas: () => request<ExpiredQuotaView[]>({ url: '/admin/orgs/expired-quotas' })
}

export interface ExpiredQuotaView {
  orgId: number
  orgName: string
  entitlementType: string
  resourceId?: number
  balance: number
  expireAt?: string | null
}

export interface OrgPortalSummary {
  orgId: number
  orgCode: string
  orgName: string
  orgType?: string
  orgStatus: string
  myRole: string
}

export interface OrgQuota {
  id: number
  orgId: number
  entitlementType: string
  resourceId?: number
  totalAmount: number
  usedAmount: number
  remark?: string
  expireAt?: string | null
}

export interface OrgPortalGrantResult {
  batchNo: string
  orgId: number
  targetCount: number
  grantedCount: number
  skippedCount: number
  skippedUserIds: number[]
  entitlementType: string
  resourceId?: number
  expireAt?: string
}

export interface OrgQuotaRecharge {
  id: number
  orgId: number
  entitlementType: string
  resourceId?: number
  amount: number
  expireAt?: string | null
  remark?: string
  operatorId?: number | null
  createdAt?: string
}

/**
 * 机构侧门户（`/v1/org/**`）。
 *
 * 与 orgApi 分开：orgApi 面向平台运营（管理机构本身），
 * 这里面向机构管理员（看自己的人和自己的额度），两者角色与路径都不共用。
 */
export const orgPortalApi = {
  myOrgs: () => request<OrgPortalSummary[]>({ url: '/org/my-orgs' }),
  profile: (orgId: number) => request<OrgPortalSummary>({ url: `/org/orgs/${orgId}/profile` }),
  members: (orgId: number) => request<OrgMember[]>({ url: `/org/orgs/${orgId}/members` }),
  quotas: (orgId: number) => request<OrgQuota[]>({ url: `/org/orgs/${orgId}/quotas` }),
  quotaRecharges: (orgId: number) =>
    request<OrgQuotaRecharge[]>({ url: `/org/orgs/${orgId}/quota-recharges` }),
  grant: (orgId: number, data: any) =>
    request<OrgPortalGrantResult>({ url: `/org/orgs/${orgId}/grants`, method: 'post', data })
}

export interface ExportTask {
  id: number
  taskNo: string
  type: string
  status: string
  fileName?: string
  downloadUrl?: string
  rowCount?: number
  errorMsg?: string
  startedAt?: string
  finishedAt?: string
  createdAt?: string
}

export const exportApi = {
  submit: (data: { type: string; sessionId?: number; status?: string }) =>
    request<ExportTask>({ url: '/admin/exports', method: 'post', data }),
  page: (params: any) => request<PageResult<ExportTask>>({ url: '/admin/exports', params }),
  detail: (id: number) => request<ExportTask>({ url: `/admin/exports/${id}` }),
  download: (id: number) =>
    request<Blob>({ url: `/admin/exports/${id}/download`, responseType: 'blob' })
}

export interface NoticeItem {
  id?: number
  title?: string
  content?: string
  status?: string
  pinned?: number
  type?: string
  publishAt?: string
  expireAt?: string
  createdAt?: string
  updatedAt?: string
}

export interface NoticeSavePayload {
  title: string
  content: string
  pinned: boolean
  publishAt?: string | null
  expireAt?: string | null
}

/**
 * 公告维护（管理端）。
 *
 * <p>状态机是 DRAFT → PUBLISHED → ARCHIVED：新建一律落草稿，发布/归档是独立动作。
 * 列表接口返回的是纯数组（不带 total），公告数量不大，页面直接加载一页看全。</p>
 */
export const noticeApi = {
  page: (params: any) => request<NoticeItem[]>({ url: '/admin/notices', params }),
  create: (data: NoticeSavePayload) =>
    request<number>({ url: '/admin/notices', method: 'post', data }),
  update: (id: number, data: NoticeSavePayload) =>
    request<any>({ url: `/admin/notices/${id}`, method: 'put', data }),
  publish: (id: number) => request<any>({ url: `/admin/notices/${id}/publish`, method: 'post' }),
  archive: (id: number) => request<any>({ url: `/admin/notices/${id}/archive`, method: 'post' }),
  remove: (id: number) => request<any>({ url: `/admin/notices/${id}`, method: 'delete' })
}

export interface ProductItem {
  id?: number
  code?: string
  name?: string
  type?: string
  description?: string
  coverUrl?: string
  priceFen?: number
  originalPriceFen?: number
  validityDays?: number
  resourceType?: string
  resourceId?: number
  status?: string
  sortNo?: number
}

/**
 * 商城商品维护。
 *
 * <p>{@code status} 只有 ON_SALE / OFF_SALE 两个取值；下架只是从考生端商城消失，
 * 已售出的权益不受影响——所以页面上的「下架」比「删除」更常用、也更安全。</p>
 */
export const productApi = {
  page: (params: any) => request<PageResult<ProductItem>>({ url: '/admin/products', params }),
  create: (data: ProductItem) =>
    request<any>({ url: '/admin/products', method: 'post', data }),
  update: (id: number, data: ProductItem) =>
    request<any>({ url: `/admin/products/${id}`, method: 'put', data }),
  remove: (id: number) =>
    request<any>({ url: `/admin/products/${id}`, method: 'delete' })
}

export interface RefundItem {
  id?: number
  refundNo?: string
  orderId?: number
  orderNo?: string
  userId?: number
  payNo?: string
  amountFen?: number
  reason?: string
  status?: string
  auditRemark?: string
  auditedBy?: number
  appliedAt?: string
  auditedAt?: string
  refundedAt?: string
  /** 部分退款对应的订单明细；为空表示整单退款 */
  orderItemId?: number | null
  quantity?: number
}

/**
 * 退款审核。
 *
 * <p>审批是动钱动作，页面上用 {@code auditedAt} 判断「是否已处理」——
 * 它比解读 {@code status} 的取值更可靠：申请时为空，审核后才落值。</p>
 */
export const refundApi = {
  page: (params: any) => request<PageResult<RefundItem>>({ url: '/admin/refunds', params }),
  approve: (id: number, remark?: string) =>
    request<any>({ url: `/admin/refunds/${id}/approve`, method: 'post', params: { remark } }),
  reject: (id: number, remark?: string) =>
    request<any>({ url: `/admin/refunds/${id}/reject`, method: 'post', params: { remark } })
}

export const userApi = {
  page: (params: any) => request<PageResult<UserView>>({ url: '/admin/users', params }),
  changeStatus: (id: number, status: string) =>
    request<UserView>({ url: `/admin/users/${id}/status`, method: 'put', params: { status } }),
  // 用户角色绑定：与角色侧的 grant/revoke 相反——这里撤的是「用户身上的角色」，
  // 而 /admin/roles/{code}/permissions 撤的是「角色身上的权限」，两者路径相似、语义不同
  roles: (id: number) => request<string[]>({ url: `/admin/users/${id}/roles` }),
  grantRole: (id: number, roleCode: string) =>
    request<any>({ url: `/admin/users/${id}/roles`, method: 'post', params: { roleCode } }),
  revokeRole: (id: number, code: string) =>
    request<any>({ url: `/admin/users/${id}/roles/${code}`, method: 'delete' }),
  // 强制下线：把该用户的 token_invalid_before 推到当前时刻，已签发的 token 立刻作废
  revokeSessions: (id: number) =>
    request<any>({ url: `/admin/users/${id}/sessions/revoke`, method: 'post' }),
  // 重置他人密码：后端会同时作废该用户已有会话，不需要再调一次 revokeSessions
  resetPassword: (id: number, newPassword: string) =>
    request<any>({ url: `/admin/users/${id}/password`, method: 'put', data: { newPassword } })
}

export interface RoleItem {
  code: string
  name: string
  description?: string
  builtIn?: boolean
  status?: string
  userCount?: number
}

/**
 * 角色维护。
 *
 * <p>{@code grant} 的权限参数走 **query**，不是 body——后端签名是
 * {@code @RequestParam String permission}。写成 {@code data: { permission }}
 * 会得到一个空的请求体，接口直接 400。</p>
 */
export const roleApi = {
  list: () => request<RoleItem[]>({ url: '/admin/roles' }),
  permissions: (code: string) => request<string[]>({ url: `/admin/roles/${code}/permissions` }),
  create: (data: { code: string; name: string; description?: string }) =>
    request<any>({ url: '/admin/roles', method: 'post', data }),
  grant: (code: string, permission: string) =>
    request<any>({
      url: `/admin/roles/${code}/permissions`,
      method: 'post',
      params: { permission }
    }),
  // 与 grant 对称：同样走 query，后端签名是 @RequestParam String permission
  revoke: (code: string, permission: string) =>
    request<any>({
      url: `/admin/roles/${code}/permissions`,
      method: 'delete',
      params: { permission }
    }),
  remove: (code: string) => request<any>({ url: `/admin/roles/${code}`, method: 'delete' })
}

export interface KnowledgePoint {
  id?: number
  subjectId: number
  parentId?: number | null
  name: string
  sortNo?: number
}

/**
 * 知识点维护。
 *
 * <p>三级上下文：项目 → 科目 → 知识点。知识点挂在科目下（`subjectId`），
 * 而科目只能在项目里查，所以页面必须先选项目才能列出科目。</p>
 */
export const knowledgeApi = {
  projects: () => request<PageResult<any>>({ url: '/admin/projects', params: { pageNo: 1, pageSize: 100 } }),
  subjects: (projectId: number) => request<any[]>({ url: `/admin/projects/${projectId}/subjects` }),
  list: (subjectId: number) =>
    request<KnowledgePoint[]>({ url: `/admin/subjects/${subjectId}/knowledge-points` }),
  create: (data: KnowledgePoint) =>
    request<any>({ url: '/admin/knowledge-points', method: 'post', data }),
  update: (id: number, data: KnowledgePoint) =>
    request<any>({ url: `/admin/knowledge-points/${id}`, method: 'put', data }),
  remove: (id: number) =>
    request<any>({ url: `/admin/knowledge-points/${id}`, method: 'delete' }),
  // 知识点题库覆盖率：无题知识点显式返回 question_count=0
  coverage: (subjectId: number) =>
    request<any[]>({ url: '/admin/knowledge-points/coverage', params: { subjectId } })
}

export interface ReconAnomaly {
  id: number
  anomalyType: string
  bizType: string
  bizId: number
  bizNo?: string
  expectedValue?: string
  actualValue?: string
  detail?: string
  handleStatus: string
  handleRemark?: string
  handledBy?: number
  handledAt?: string
}

export interface ReconReport {
  reportNo: string
  scope: string
  status: string
  checkedOrders: number
  checkedPayments: number
  checkedAccounts: number
  anomalyCount: number
  startedAt?: string
  finishedAt?: string
  errorMsg?: string
  anomalies: ReconAnomaly[]
}

export const reconApi = {
  run: () => request<ReconReport>({ url: '/admin/reconciliation/runs', method: 'post' }),
  latest: () => request<ReconReport | null>({ url: '/admin/reconciliation/runs/latest' }),
  recent: () => request<string[]>({ url: '/admin/reconciliation/runs/recent' }),
  handle: (id: number, status: string, remark?: string) =>
    request<ReconAnomaly>({
      url: `/admin/reconciliation/anomalies/${id}/handle`,
      method: 'post',
      params: { status, remark }
    }),
  anomalies: (params: any) =>
    request<PageResult<ReconAnomaly>>({ url: '/admin/reconciliation/anomalies', params }),
  exportAnomalies: (params: any) =>
    request<Blob>({ url: '/admin/reconciliation/anomalies/export', params, responseType: 'blob' })
}

export interface AuditLogView {
  id: number
  userId?: number
  username?: string
  action: string
  target?: string
  detail?: string
  ip?: string
  createdAt?: string
}

export const auditLogApi = {
  page: (params: any) => request<PageResult<AuditLogView>>({ url: '/admin/audit-logs', params }),
  actions: () => request<string[]>({ url: '/admin/audit-logs/actions' }),
  // 导出 CSV：以 blob 接收，由页面负责触发下载
  export: (params: any) =>
    request<Blob>({ url: '/admin/audit-logs/export', params, responseType: 'blob' })
}

