import { createRouter, createWebHashHistory, type RouteRecordRaw } from 'vue-router'
import { getToken } from '@/api/request'

const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'login',
    component: () => import('@/views/LoginView.vue'),
    meta: { public: true }
  },
  {
    path: '/',
    component: () => import('@/layout/AppLayout.vue'),
    redirect: '/dashboard',
    children: [
      {
        path: 'dashboard',
        name: 'dashboard',
        component: () => import('@/views/DashboardView.vue'),
        meta: { title: '工作台' }
      },
      {
        path: 'roles',
        name: 'roles',
        component: () => import('@/views/RoleView.vue')
      },
      {
        path: 'knowledge-points',
        name: 'knowledge-points',
        component: () => import('@/views/KnowledgeView.vue'),
        meta: { title: '知识点' }
      },
      {
        path: 'refunds',
        name: 'refunds',
        component: () => import('@/views/RefundView.vue'),
        meta: { title: '退款审核' }
      },
      {
        path: 'products',
        name: 'products',
        component: () => import('@/views/ProductView.vue'),
        meta: { title: '商城商品' }
      },
      {
        path: 'notices',
        name: 'notices',
        component: () => import('@/views/NoticeView.vue'),
        meta: { title: '公告管理' }
      },
      {
        path: 'question-usage',
        name: 'question-usage',
        component: () => import('@/views/QuestionUsageView.vue'),
        meta: { title: '使用分析' }
      },
      {
        path: 'marking',
        name: 'marking',
        component: () => import('@/views/MarkingView.vue'),
        meta: { title: '阅卷工作台' }
      },
      {
        path: 'coupons',
        name: 'coupons',
        component: () => import('@/views/CouponView.vue'),
        meta: { title: '优惠券' }
      },
      {
        path: 'projects',
        name: 'projects',
        component: () => import('@/views/ProjectView.vue'),
        meta: { title: '考试项目' }
      },
      {
        path: 'questions',
        name: 'questions',
        component: () => import('@/views/QuestionView.vue'),
        meta: { title: '题库管理' }
      },
      {
        path: 'papers',
        name: 'papers',
        component: () => import('@/views/PaperView.vue'),
        meta: { title: '试卷管理' }
      },
      {
        path: 'sessions',
        name: 'sessions',
        component: () => import('@/views/SessionView.vue'),
        meta: { title: '考试场次' }
      },
      {
        path: 'courses',
        name: 'courses',
        component: () => import('@/views/CourseView.vue'),
        meta: { title: '课程管理' }
      },
      {
        path: 'marking',
        name: 'marking',
        component: () => import('@/views/MarkingView.vue'),
        meta: { title: '阅卷中心' }
      },
      {
        path: 'messages',
        name: 'messages',
        component: () => import('@/views/MessageView.vue'),
        meta: { title: '消息中心' }
      },
      {
        path: 'orders',
        name: 'orders',
        component: () => import('@/views/OrderView.vue'),
        meta: { title: '订单管理' }
      },
      {
        path: 'reconciliation',
        name: 'reconciliation',
        component: () => import('@/views/ReconciliationView.vue'),
        meta: { title: '对账中心' }
      },
      {
        path: 'orgs',
        name: 'orgs',
        component: () => import('@/views/OrgView.vue'),
        meta: { title: '机构 / 企业' }
      },
      {
        path: 'org-portal',
        name: 'org-portal',
        component: () => import('@/views/OrgPortalView.vue'),
        meta: { title: '机构门户' }
      },
      {
        path: 'exports',
        name: 'exports',
        component: () => import('@/views/ExportView.vue'),
        meta: { title: '报表导出' }
      },
      {
        path: 'users',
        name: 'users',
        component: () => import('@/views/UserView.vue'),
        meta: { title: '用户管理' }
      },
      {
        path: 'audit-logs',
        name: 'audit-logs',
        component: () => import('@/views/AuditView.vue'),
        meta: { title: '审计日志' }
      },
      {
        path: 'settings',
        name: 'settings',
        component: () => import('@/views/SettingsView.vue'),
        meta: { title: '系统参数' }
      }
    ]
  },
  { path: '/:pathMatch(.*)*', redirect: '/dashboard' }
]

const router = createRouter({
  history: createWebHashHistory(),
  routes
})

router.beforeEach((to) => {
  if (to.meta.public) return true
  if (!getToken()) return { path: '/login', query: { redirect: to.fullPath } }
  return true
})

export default router
