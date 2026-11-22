<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const collapsed = ref(false)
const menuRef = ref()
const scrollbarRef = ref()

interface MenuGroup {
  key: string
  title: string
  icon: string
  items: Array<{ path: string; label: string; icon: string }>
}

/**
 * 二级菜单：一级是业务域（5 组），二级是具体页面（24 个）。
 *
 * 为什么不再用「分组标题 + 平铺」：24 项全部展开约 1344px，1366×768 屏幕上
 * 一次只能看到 13 项，剩下的必须滚动；而分组标题在滚动后会被顶出视野，
 * 「我在哪个域里」这个信息就丢了。二级菜单把常驻高度压到约 400px，
 * 五个域始终一屏可见。
 */
const ADMIN_GROUPS: MenuGroup[] = [
  {
    key: 'content',
    title: '内容运营',
    icon: 'Folder',
    items: [
      { path: '/projects', label: '考试项目', icon: 'Folder' },
      { path: '/questions', label: '题库管理', icon: 'Document' },
      { path: '/knowledge-points', label: '知识点', icon: 'Collection' },
      { path: '/question-usage', label: '使用分析', icon: 'DataAnalysis' },
      { path: '/notices', label: '公告管理', icon: 'Bell' },
      { path: '/courses', label: '课程管理', icon: 'Reading' }
    ]
  },
  {
    key: 'exam',
    title: '考试执行',
    icon: 'AlarmClock',
    items: [
      { path: '/papers', label: '试卷管理', icon: 'Files' },
      { path: '/sessions', label: '考试场次', icon: 'AlarmClock' },
      { path: '/marking', label: '阅卷中心', icon: 'EditPen' }
    ]
  },
  {
    key: 'trade',
    title: '交易与财务',
    icon: 'Wallet',
    items: [
      { path: '/products', label: '商城商品', icon: 'Goods' },
      { path: '/coupons', label: '优惠券', icon: 'Ticket' },
      { path: '/orders', label: '订单管理', icon: 'Wallet' },
      { path: '/refunds', label: '退款审核', icon: 'RefreshLeft' },
      { path: '/reconciliation', label: '对账中心', icon: 'Coin' }
    ]
  },
  {
    key: 'org',
    title: '组织与触达',
    icon: 'OfficeBuilding',
    items: [
      { path: '/orgs', label: '机构 / 企业', icon: 'OfficeBuilding' },
      { path: '/messages', label: '消息中心', icon: 'ChatDotRound' }
    ]
  },
  {
    key: 'system',
    title: '系统管理',
    icon: 'Setting',
    items: [
      { path: '/exports', label: '报表导出', icon: 'Download' },
      { path: '/users', label: '用户管理', icon: 'User' },
      { path: '/roles', label: '角色权限', icon: 'Lock' },
      { path: '/audit-logs', label: '审计日志', icon: 'Tickets' },
      { path: '/settings', label: '系统参数', icon: 'Setting' }
    ]
  }
]

// 机构管理员只有这一个入口：平台后台的接口对他一律 403，菜单里露出来只会点一次错一次
const PORTAL_GROUPS: MenuGroup[] = [
  {
    key: 'portal',
    title: '机构门户',
    icon: 'OfficeBuilding',
    items: [{ path: '/org-portal', label: '机构门户', icon: 'OfficeBuilding' }]
  }
]

const isOrgAdmin = computed(() => auth.user?.roleCode === 'ORG_ADMIN')
const menuGroups = computed(() => (isOrgAdmin.value ? PORTAL_GROUPS : ADMIN_GROUPS))

const activeMenu = computed(() => '/' + (route.path.split('/')[1] || 'dashboard'))

/** 当前页所属的一级组：用于自动展开与面包屑 */
const activeGroup = computed(() =>
  menuGroups.value.find((group) => group.items.some((item) => item.path === activeMenu.value)) ?? null
)

/**
 * 手风琴只允许一个组展开：24 项同时展开等于退回平铺，把纵向空间又吃掉了。
 * 当前页所在组由路由自动展开，用户手动切换时由 el-menu 的 unique-opened 收掉上一组。
 */
const openeds = ref<string[]>([])

/**
 * 把侧栏里的目标元素滚进可视区。
 *
 * 两个坑：子菜单展开有 300ms 高度过渡，过渡中量到的位置是假的；折叠态下子项高度为 0，
 * 此时算出来的可滚动距离也是假的。所以按 100ms 重试，覆盖完整个过渡期再收敛。
 */
function revealInMenu(selector: string, attempt = 0) {
  const wrap = scrollbarRef.value?.wrapRef as HTMLElement | undefined
  const target = wrap?.querySelector(selector) as HTMLElement | null
  if (wrap && target) {
    const wrapRect = wrap.getBoundingClientRect()
    const rect = target.getBoundingClientRect()
    if (rect.bottom > wrapRect.bottom) {
      wrap.scrollTop += rect.bottom - wrapRect.bottom + 8
    } else if (rect.top < wrapRect.top) {
      wrap.scrollTop += rect.top - wrapRect.top - 8
    }
  }
  if (attempt < 6) {
    window.setTimeout(() => revealInMenu(selector, attempt + 1), 100)
  }
}

function syncOpenKey() {
  // 当前页在一级组内 → 自动展开该组；在工作台等一级页面 → 保持用户当前展开的组
  if (!activeGroup.value) return
  openeds.value = [activeGroup.value.key]
  // default-openeds 只在初始化生效，路由切换后要靠命令式 open 才能真的展开
  menuRef.value?.open(activeGroup.value.key)
  revealInMenu('.el-menu-item.is-active')
}

watch(activeGroup, syncOpenKey)

// 手动展开某组时，新展开的子项会向下顶出可视区，用户点了却看不到内容
function onMenuOpen() {
  revealInMenu('.el-sub-menu.is-opened')
}

// el-menu 在折叠时会清空已展开的组（见 menu.mjs 的 collapse watcher），
// 展开回来不会自动恢复——不补这一步，收起再展开后当前页所在的组就"消失"了。
watch(collapsed, (value) => {
  if (!value) nextTick(syncOpenKey)
})

const currentLabel = computed(() => {
  for (const group of menuGroups.value) {
    const hit = group.items.find((item) => item.path === activeMenu.value)
    if (hit) return hit.label
  }
  return (route.meta.title as string) || '运营后台'
})

onMounted(async () => {
  if (!auth.user) await auth.loadProfile().catch(() => undefined)
  // 机构管理员手输 /users 之类的地址时，先把人拉回门户，别让他对着 403 找原因
  if (isOrgAdmin.value && !route.path.startsWith('/org-portal')) {
    router.replace('/org-portal')
  }
  syncOpenKey()
})

function logout() {
  auth.logout()
  router.push('/login')
}
</script>

<template>
  <el-container class="layout">
    <el-aside :width="collapsed ? '68px' : '224px'" class="aside" :class="{ collapsed }">
      <div class="brand" :class="{ collapsed }">
        <span class="dot" />
        <div v-if="!collapsed" class="brand-text">
          <div class="brand-title">综合考试平台</div>
          <div class="brand-sub">运营后台</div>
        </div>
      </div>

      <el-scrollbar ref="scrollbarRef" class="menu-scroll" always>
        <el-menu
          ref="menuRef"
          :default-active="activeMenu"
          :default-openeds="openeds"
          :collapse="collapsed"
          unique-opened
          router
          class="menu"
          @open="onMenuOpen"
        >
          <!-- 工作台是一级常驻入口，不属于任何业务域 -->
          <el-menu-item index="/dashboard">
            <el-icon><Odometer /></el-icon>
            <template #title>工作台</template>
          </el-menu-item>

          <el-sub-menu v-for="group in menuGroups" :key="group.key" :index="group.key">
            <template #title>
              <el-icon><component :is="group.icon" /></el-icon>
              <span class="group-title">{{ group.title }}</span>
              <span class="group-count">{{ group.items.length }}</span>
            </template>
            <el-menu-item v-for="item in group.items" :key="item.path" :index="item.path">
              <el-icon><component :is="item.icon" /></el-icon>
              <template #title>{{ item.label }}</template>
            </el-menu-item>
          </el-sub-menu>
        </el-menu>
      </el-scrollbar>

      <button type="button" class="collapse-btn" @click="collapsed = !collapsed">
        <el-icon :size="15">
          <component :is="collapsed ? 'DArrowRight' : 'DArrowLeft'" />
        </el-icon>
        <span v-if="!collapsed">收起菜单</span>
      </button>
    </el-aside>

    <el-container>
      <el-header class="header">
        <div class="header-left">
          <!-- 面包屑承担「我在哪」；页面标题由卡片内的 h3 提供，两处重复只会互相抢注意力 -->
          <el-breadcrumb separator="/" class="header-path">
            <el-breadcrumb-item>首页</el-breadcrumb-item>
            <el-breadcrumb-item v-if="activeGroup">{{ activeGroup.title }}</el-breadcrumb-item>
            <el-breadcrumb-item>{{ currentLabel }}</el-breadcrumb-item>
          </el-breadcrumb>
        </div>
        <div class="header-right">
          <el-tag type="info" effect="plain">租户 #{{ auth.user?.tenantId ?? '-' }}</el-tag>
          <el-dropdown>
            <span class="user">
              <span class="avatar">{{ (auth.user?.nickname || auth.user?.username || '管').slice(0, 1).toUpperCase() }}</span>
              {{ auth.user?.nickname || auth.user?.username || '管理员' }}
              <el-icon :size="12"><ArrowDown /></el-icon>
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
  background: #0f172a;
  display: flex;
  flex-direction: column;
  transition: width 0.2s ease;
  overflow: hidden;
}

.brand {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 18px;
  color: #fff;
  flex-shrink: 0;
}

.brand.collapsed {
  justify-content: center;
  padding: 18px 0;
}

.dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  background: #3b82f6;
  box-shadow: 0 0 0 4px rgba(59, 130, 246, 0.25);
  flex-shrink: 0;
}

.brand-title {
  font-size: 15px;
  font-weight: 600;
  white-space: nowrap;
}

.brand-sub {
  font-size: 12px;
  color: #94a3b8;
  white-space: nowrap;
}

/*
 * el-scrollbar 自带 height:100%，与 flex:1 冲突：它会把滚动区撑成整个侧栏的高度，
 * 子项溢出到「收起菜单」按钮下方再被裁掉，滚动条看起来有却滚不动。
 * 这里把高度交还给 flex 算法（basis 0 + height:auto），滚动才真正生效。
 */
.menu-scroll {
  flex: 1 1 0;
  min-height: 0;
  height: auto;
  overflow: hidden;
}

.menu {
  border-right: none;
  background: transparent;
  padding: 4px 0 8px;
  --el-menu-bg-color: transparent;
  --el-menu-text-color: #cbd5e1;
  --el-menu-hover-bg-color: rgba(59, 130, 246, 0.14);
  --el-menu-active-color: #ffffff;
  --el-menu-item-height: 40px;
  --el-menu-sub-item-height: 34px;
}

.menu:not(.el-menu--collapse) {
  width: 100%;
}

.menu :deep(.el-menu-item),
.menu :deep(.el-sub-menu__title) {
  height: 40px;
  line-height: 40px;
  margin: 2px 10px;
  border-radius: 8px;
  color: #cbd5e1;
  font-size: 14px;
}

.menu :deep(.el-menu-item:hover),
.menu :deep(.el-sub-menu__title:hover) {
  background: rgba(59, 130, 246, 0.14);
  color: #fff;
}

/* 二级项：缩进 + 更小字号，视觉上与一级拉开层级 */
.menu :deep(.el-sub-menu .el-menu-item) {
  height: 34px;
  line-height: 34px;
  min-width: auto;
  margin: 1px 10px 1px 18px;
  padding-left: 32px !important;
  font-size: 14px;
  color: #94a3b8;
}

/* 当前页是二级项，用实心渐变给足对比 */
.menu :deep(.el-sub-menu .el-menu-item.is-active) {
  color: #fff;
  background: linear-gradient(90deg, rgba(37, 99, 235, 0.95), rgba(79, 70, 229, 0.8));
  box-shadow: 0 2px 8px rgba(37, 99, 235, 0.35);
}

/* 一级「组」内含当前页：仅浅底提示，不与二级选中项抢焦点 */
.menu :deep(.el-sub-menu.is-active > .el-sub-menu__title) {
  color: #fff;
  background: rgba(59, 130, 246, 0.12);
}

.group-title {
  flex: 1;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.group-count {
  font-size: 11px;
  color: #64748b;
  background: rgba(148, 163, 184, 0.14);
  border-radius: 999px;
  padding: 0 7px;
  line-height: 16px;
  margin-left: 6px;
  flex-shrink: 0;
}

/* 折叠态：图标居中，隐藏组内数量徽标 */
.menu.el-menu--collapse :deep(.el-menu-item),
.menu.el-menu--collapse :deep(.el-sub-menu__title) {
  justify-content: center;
  padding: 0 !important;
}

.menu.el-menu--collapse .group-count {
  display: none;
}

/* 折叠态弹出的子菜单是 teleport 到 body 的，这里补一层深色皮肤 */
:global(.el-menu--popup-container .el-menu--popup) {
  background: #111827;
  padding: 6px;
  border-radius: 10px;
}

:global(.el-menu--popup-container .el-menu--popup .el-menu-item) {
  height: 34px;
  line-height: 34px;
  border-radius: 7px;
  color: #cbd5e1;
  font-size: 13px;
}

:global(.el-menu--popup-container .el-menu--popup .el-menu-item:hover) {
  background: rgba(59, 130, 246, 0.16);
  color: #fff;
}

:global(.el-menu--popup-container .el-menu--popup .el-menu-item.is-active) {
  color: #fff;
  background: linear-gradient(90deg, rgba(37, 99, 235, 0.95), rgba(79, 70, 229, 0.8));
}

.collapse-btn {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  width: 100%;
  height: 44px;
  border: none;
  border-top: 1px solid rgba(148, 163, 184, 0.15);
  background: transparent;
  color: #94a3b8;
  font-size: 12px;
  font-family: inherit;
  cursor: pointer;
  transition: color 0.15s, background 0.15s;
}

.collapse-btn:hover {
  color: #fff;
  background: rgba(59, 130, 246, 0.14);
}

.collapse-btn:focus-visible {
  outline: 2px solid #3b82f6;
  outline-offset: -2px;
}

.header {
  background: #fff;
  display: flex;
  align-items: center;
  justify-content: space-between;
  border-bottom: 1px solid #eef1f6;
  height: 56px;
}

.header-left {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
}

.header-path {
  font-size: 13px;
}

.header-path :deep(.el-breadcrumb__inner) {
  color: #8a94a6;
  font-weight: 400;
}

.header-path :deep(.el-breadcrumb__item:last-child .el-breadcrumb__inner) {
  color: #1f2937;
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
  gap: 8px;
  cursor: pointer;
  color: #374151;
  font-size: 14px;
}

.avatar {
  width: 28px;
  height: 28px;
  border-radius: 50%;
  background: linear-gradient(135deg, #2563eb, #7c3aed);
  color: #fff;
  font-size: 12px;
  font-weight: 600;
  display: flex;
  align-items: center;
  justify-content: center;
}

.main {
  padding: 18px;
  /* 只允许纵向滚动：横向溢出交给 el-table 自己的滚动条处理，
     否则表格会把整页撑宽，右侧固定列跟着跑到视口外，看起来像"操作列消失" */
  overflow-y: auto;
  overflow-x: hidden;
  background: #f5f7fa;
}
</style>
