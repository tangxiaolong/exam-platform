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
  type: string
  stem: string
  options: OptionItem[]
  answer?: string[]
  analysis?: string
  difficulty?: string
  tags?: string
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
  payAmountFen: number
  status: string
  paidAt?: string
  createdAt?: string
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
  overview: () => request<Overview>({ url: '/admin/dashboard/overview' })
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

export const questionApi = {
  page: (params: any) => request<PageResult<Question>>({ url: '/admin/questions', params }),
  detail: (id: number) => request<Question>({ url: `/admin/questions/${id}` }),
  create: (data: any) => request<Question>({ url: '/admin/questions', method: 'post', data }),
  update: (id: number, data: any) =>
    request<Question>({ url: `/admin/questions/${id}`, method: 'put', data }),
  remove: (id: number) => request<void>({ url: `/admin/questions/${id}`, method: 'delete' }),
  changeStatus: (id: number, status: string) =>
    request<void>({ url: `/admin/questions/${id}/status`, method: 'put', params: { status } })
}

export const paperApi = {
  page: (params: any) => request<PageResult<Paper>>({ url: '/admin/papers', params }),
  detail: (id: number) => request<Paper>({ url: `/admin/papers/${id}` }),
  create: (data: any) => request<Paper>({ url: '/admin/papers', method: 'post', data }),
  update: (id: number, data: any) => request<Paper>({ url: `/admin/papers/${id}`, method: 'put', data }),
  remove: (id: number) => request<void>({ url: `/admin/papers/${id}`, method: 'delete' }),
  preview: (id: number) => request<any>({ url: `/admin/papers/${id}/preview`, method: 'post' }),
  rules: (id: number) => request<any[]>({ url: `/admin/papers/${id}/rules` })
}

export const sessionApi = {
  page: (params: any) => request<PageResult<ExamSession>>({ url: '/admin/sessions', params }),
  create: (data: any) => request<ExamSession>({ url: '/admin/sessions', method: 'post', data }),
  update: (id: number, data: any) =>
    request<ExamSession>({ url: `/admin/sessions/${id}`, method: 'put', data }),
  remove: (id: number) => request<void>({ url: `/admin/sessions/${id}`, method: 'delete' }),
  publish: (id: number) => request<ExamSession>({ url: `/admin/sessions/${id}/publish`, method: 'post' }),
  close: (id: number) => request<void>({ url: `/admin/sessions/${id}/close`, method: 'post' }),
  registrations: (id: number) => request<any[]>({ url: `/admin/sessions/${id}/registrations` }),
  attempts: (id: number) => request<any[]>({ url: `/admin/sessions/${id}/attempts` }),
  forceSubmit: (attemptId: number) =>
    request<any>({ url: `/admin/attempts/${attemptId}/force-submit`, method: 'post' })
}

export const tradeApi = {
  products: (params: any) => request<PageResult<Product>>({ url: '/admin/products', params }),
  createProduct: (data: Product) => request<Product>({ url: '/admin/products', method: 'post', data }),
  updateProduct: (id: number, data: Product) =>
    request<Product>({ url: `/admin/products/${id}`, method: 'put', data }),
  orders: (params: any) => request<PageResult<Order>>({ url: '/admin/orders', params }),
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
  correct: (attemptId: number, questionId: number, score: number, reason: string) =>
    request<any>({
      url: `/admin/attempts/${attemptId}/results/correct`,
      method: 'post',
      params: { questionId, score, reason }
    }),
  history: (attemptId: number) =>
    request<any[]>({ url: `/admin/attempts/${attemptId}/results/history` })
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
  logs: (taskId: number) => request<any[]>({ url: `/admin/messages/${taskId}/logs` }),
  resend: (taskId: number) =>
    request<MsgTask>({ url: `/admin/messages/${taskId}/resend`, method: 'post' })
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

export const userApi = {
  page: (params: any) => request<PageResult<UserView>>({ url: '/admin/users', params }),
  changeStatus: (id: number, status: string) =>
    request<UserView>({ url: `/admin/users/${id}/status`, method: 'put', params: { status } })
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
  actions: () => request<string[]>({ url: '/admin/audit-logs/actions' })
}

