<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const ADMIN_MENUS = [
  { path: '/dashboard', label: '工作台', icon: 'Odometer' },
  { path: '/projects', label: '考试项目', icon: 'Folder' },
  { path: '/questions', label: '题库管理', icon: 'Document' },
  { path: '/papers', label: '试卷管理', icon: 'Files' },
  { path: '/sessions', label: '考试场次', icon: 'AlarmClock' },
  { path: '/marking', label: '阅卷中心', icon: 'EditPen' },
  { path: '/courses', label: '课程管理', icon: 'Reading' },
  { path: '/messages', label: '消息中心', icon: 'Bell' },
  { path: '/orders', label: '订单管理', icon: 'Wallet' },
  { path: '/reconciliation', label: '对账中心', icon: 'Coin' },
  { path: '/orgs', label: '机构 / 企业', icon: 'OfficeBuilding' },
  { path: '/exports', label: '报表导出', icon: 'Download' },
  { path: '/users', label: '用户管理', icon: 'User' },
  { path: '/audit-logs', label: '审计日志', icon: 'Tickets' }
]

// 机构管理员只有这一个入口：平台后台的接口对他一律 403，菜单里露出来只会点一次错一次
const PORTAL_MENUS = [{ path: '/org-portal', label: '机构门户', icon: 'OfficeBuilding' }]

const isOrgAdmin = computed(() => auth.user?.roleCode === 'ORG_ADMIN')
const menus = computed(() => (isOrgAdmin.value ? PORTAL_MENUS : ADMIN_MENUS))

const activeMenu = computed(() => '/' + (route.path.split('/')[1] || 'dashboard'))
const pageTitle = computed(() => (route.meta.title as string) || '运营后台')

onMounted(async () => {
  if (!auth.user) await auth.loadProfile().catch(() => undefined)
  // 机构管理员手输 /users 之类的地址时，先把人拉回门户，别让他对着 403 找原因
  if (isOrgAdmin.value && !route.path.startsWith('/org-portal')) {
    router.replace('/org-portal')
  }
})

function logout() {
  auth.logout()
  router.push('/login')
}
</script>

<template>
  <el-container class="layout">
    <el-aside width="216px" class="aside">
      <div class="brand">
        <span class="dot" />
        <div>
          <div class="brand-title">综合考试平台</div>
          <div class="brand-sub">运营后台</div>
        </div>
      </div>
      <el-menu :default-active="activeMenu" router class="menu">
        <el-menu-item v-for="menu in menus" :key="menu.path" :index="menu.path">
          <el-icon><component :is="menu.icon" /></el-icon><span>{{ menu.label }}</span>
        </el-menu-item>
      </el-menu>
    </el-aside>

    <el-container>
      <el-header class="header">
        <div class="header-title">{{ pageTitle }}</div>
        <div class="header-right">
          <el-tag type="info" effect="plain">租户 #{{ auth.user?.tenantId ?? '-' }}</el-tag>
          <el-dropdown>
            <span class="user">
              <el-icon><Avatar /></el-icon>
              {{ auth.user?.nickname || auth.user?.username || '管理员' }}
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item @click="logout">退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </el-header>
      <el-main class="main">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<style scoped>
.layout {
  height: 100%;
}

.aside {
  background: #111827;
  display: flex;
  flex-direction: column;
}

.brand {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 18px 16px;
  color: #fff;
}

.dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  background: #3b82f6;
  box-shadow: 0 0 0 4px rgba(59, 130, 246, 0.25);
}

.brand-title {
  font-size: 15px;
  font-weight: 600;
}

.brand-sub {
  font-size: 12px;
  color: #9ca3af;
}

.menu {
  border-right: none;
  background: transparent;
  --el-menu-bg-color: transparent;
  --el-menu-text-color: #cbd5e1;
  --el-menu-hover-bg-color: rgba(59, 130, 246, 0.12);
  --el-menu-active-color: #ffffff;
}

.menu :deep(.el-menu-item.is-active) {
  background: rgba(59, 130, 246, 0.22);
  border-radius: 8px;
  margin: 0 10px;
}

.menu :deep(.el-menu-item) {
  margin: 2px 10px;
  border-radius: 8px;
  height: 42px;
}

.header {
  background: #fff;
  display: flex;
  align-items: center;
  justify-content: space-between;
  border-bottom: 1px solid #eef1f6;
}

.header-title {
  font-size: 16px;
  font-weight: 600;
}

.header-right {
  display: flex;
  align-items: center;
  gap: 14px;
}

.user {
  display: flex;
  align-items: center;
  gap: 6px;
  cursor: pointer;
  color: #374151;
  font-size: 14px;
}

.main {
  padding: 18px;
  overflow-y: auto;
}
</style>
