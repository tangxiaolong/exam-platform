<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { dashboardApi, exportApi, markingApi, refundApi, sessionApi, type Overview } from '@/api'

const router = useRouter()

/** 待办中心：工作台第一优先级——运营每天先看「有什么要处理」，而不是先看总数。 */
const todos = ref([
  { key: 'marking', label: '待阅卷答卷', count: 0, path: '/marking', hint: '主观题人工阅卷', icon: 'EditPen', color: '#6366f1', attention: true },
  { key: 'refund', label: '待审核退款', count: 0, path: '/refunds', hint: '退款单流转', icon: 'RefreshLeft', color: '#ef4444', attention: true },
  { key: 'session', label: '进行中场次', count: 0, path: '/sessions', hint: '已发布场次监控', icon: 'AlarmClock', color: '#10b981', attention: false },
  { key: 'export', label: '我的导出任务', count: 0, path: '/exports', hint: '报表导出记录', icon: 'Download', color: '#0ea5e9', attention: false }
])

/** 四个分页接口各取 1 条，只用 total——不为统计拉全量数据；单个失败不拖垮整个工作台。 */
async function loadTodos() {
  const results = await Promise.allSettled([
    markingApi.queue({ status: 'REVIEWING', pageNo: 1, pageSize: 1 }),
    refundApi.page({ status: 'APPLIED', pageNo: 1, pageSize: 1 }),
    sessionApi.page({ status: 'PUBLISHED', pageNo: 1, pageSize: 1 }),
    exportApi.page({ pageNo: 1, pageSize: 1 })
  ])
  results.forEach((result, index) => {
    if (result.status === 'fulfilled') {
      todos.value[index].count = result.value.total
    }
  })
}

/** 待办合计只算真正「需要人处理」的两类：阅卷与退款。 */
const pendingTotal = computed(() => todos.value.filter((item) => item.attention).reduce((sum, item) => sum + item.count, 0))

const loading = ref(false)
const data = ref<Overview | null>(null)
const trends = ref<Array<{ date: string; registrations: number; attempts: number; paidOrders: number; revenueFen: number }>>([])
const trendRange = ref(7)
const trendLoading = ref(false)

const trendMax = computed(() =>
  Math.max(1, ...trends.value.map((t) => Math.max(t.registrations, t.attempts, t.paidOrders)))
)

const trendSummary = computed(() =>
  trends.value.reduce(
    (acc, t) => ({
      registrations: acc.registrations + t.registrations,
      attempts: acc.attempts + t.attempts,
      paidOrders: acc.paidOrders + t.paidOrders,
      revenueFen: acc.revenueFen + t.revenueFen
    }),
    { registrations: 0, attempts: 0, paidOrders: 0, revenueFen: 0 }
  )
)

/** 今天的数据取趋势数组最后一天：同一份接口即可回答「今天业务怎么样」。 */
const todayStat = computed(() => trends.value[trends.value.length - 1])

function barPct(value: number, max: number) {
  return Math.max(2, Math.round((value / max) * 100)) + '%'
}

async function loadTrends(days: number) {
  trendLoading.value = true
  try {
    trends.value = await dashboardApi.trends(days)
  } finally {
    trendLoading.value = false
  }
}

function switchRange(days: number) {
  if (days === trendRange.value) return
  trendRange.value = days
  loadTrends(days)
}

const kpis = [
  { key: 'userCount', label: '注册用户', icon: 'User', color: '#3b82f6', path: '/users', unit: '人' },
  { key: 'projectCount', label: '考试项目', icon: 'Folder', color: '#8b5cf6', path: '/projects', unit: '个' },
  { key: 'questionCount', label: '题库题目', icon: 'Document', color: '#10b981', path: '/questions', unit: '题' },
  { key: 'sessionCount', label: '考试场次', icon: 'AlarmClock', color: '#f59e0b', path: '/sessions', unit: '场' }
] as const

const metrics = [
  { key: 'registrationCount', label: '报名人次', icon: 'Tickets', path: '/sessions' },
  { key: 'attemptCount', label: '考试记录', icon: 'EditPen', path: '/sessions' },
  { key: 'orderCount', label: '订单总数', icon: 'Wallet', path: '/orders' },
  { key: 'paidOrderCount', label: '已支付订单', icon: 'CircleCheck', path: '/orders' }
] as const

const shortcuts = [
  { label: '考试项目', icon: 'Folder', path: '/projects', color: '#3b82f6' },
  { label: '题库管理', icon: 'Document', path: '/questions', color: '#10b981' },
  { label: '试卷管理', icon: 'Files', path: '/papers', color: '#8b5cf6' },
  { label: '考试场次', icon: 'AlarmClock', path: '/sessions', color: '#f59e0b' },
  { label: '阅卷中心', icon: 'EditPen', path: '/marking', color: '#6366f1' },
  { label: '订单管理', icon: 'Wallet', path: '/orders', color: '#0ea5e9' },
  { label: '退款审核', icon: 'RefreshLeft', path: '/refunds', color: '#ef4444' },
  { label: '报表导出', icon: 'Download', path: '/exports', color: '#14b8a6' }
]

const greeting = computed(() => {
  const hour = new Date().getHours()
  if (hour < 6) return '夜深了'
  if (hour < 12) return '上午好'
  if (hour < 18) return '下午好'
  return '晚上好'
})

const todayLabel = computed(() =>
  new Date().toLocaleDateString('zh-CN', { month: 'long', day: 'numeric', weekday: 'long' })
)

function money(fen?: number) {
  return '¥' + ((fen || 0) / 100).toFixed(2)
}

onMounted(async () => {
  loading.value = true
  try {
    const [overview] = await Promise.all([dashboardApi.overview(), loadTodos(), loadTrends(trendRange.value)])
    data.value = overview
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <div v-loading="loading" class="dashboard">
    <!-- 欢迎条：问候 + 今日业务一览，让运营打开就知道「今天过得怎么样」 -->
    <div class="banner">
      <div class="banner-left">
        <div class="banner-title">{{ greeting }}，今天也要顺利</div>
        <div class="banner-date">{{ todayLabel }}</div>
      </div>
      <div class="banner-right">
        <div class="today-chip">
          <span class="today-value">{{ todayStat?.registrations ?? 0 }}</span>
          <span class="today-label">今日报名</span>
        </div>
        <div class="today-chip">
          <span class="today-value">{{ todayStat?.attempts ?? 0 }}</span>
          <span class="today-label">今日答卷</span>
        </div>
        <div class="today-chip">
          <span class="today-value">{{ todayStat?.paidOrders ?? 0 }}</span>
          <span class="today-label">今日已付订单</span>
        </div>
        <div class="today-chip">
          <span class="today-value">{{ money(todayStat?.revenueFen) }}</span>
          <span class="today-label">今日成交</span>
        </div>
      </div>
    </div>

    <!-- 核心指标：可点击直达对应管理页 -->
    <div class="kpi-grid">
      <div v-for="card in kpis" :key="card.key" class="kpi" @click="router.push(card.path)">
        <div class="kpi-icon" :style="{ background: card.color + '1a', color: card.color }">
          <el-icon :size="22"><component :is="card.icon" /></el-icon>
        </div>
        <div class="kpi-body">
          <div class="kpi-value">
            {{ data?.[card.key] ?? 0 }}<span class="kpi-unit">{{ card.unit }}</span>
          </div>
          <div class="kpi-label">{{ card.label }}</div>
        </div>
        <el-icon class="kpi-arrow" :size="14"><ArrowRight /></el-icon>
      </div>
    </div>

    <!-- 主区：趋势图（左，宽）+ 待办中心（右，第一优先级） -->
    <div class="main-grid">
      <div class="page-card trend-card">
        <div class="card-head">
          <div>
            <h3 class="page-title">业务趋势</h3>
            <p class="page-desc">每日报名 / 答卷 / 已付订单对比，柱下为当日成交金额</p>
          </div>
          <div class="range-switch">
            <span v-for="day in [7, 14, 30]" :key="day" class="range-item"
                  :class="{ active: trendRange === day }" @click="switchRange(day)">
              近 {{ day }} 天
            </span>
          </div>
        </div>

        <div class="trend-summary">
          <span>报名 <b>{{ trendSummary.registrations }}</b></span>
          <span>答卷 <b>{{ trendSummary.attempts }}</b></span>
          <span>已付订单 <b>{{ trendSummary.paidOrders }}</b></span>
          <span>成交 <b class="green">{{ money(trendSummary.revenueFen) }}</b></span>
        </div>

        <div v-loading="trendLoading" class="trend">
          <div v-if="trends.length === 0" class="trend-empty">暂无趋势数据</div>
          <div v-for="t in trends" :key="t.date" class="trend-day">
            <el-tooltip placement="top" :show-after="80">
              <template #content>
                <div class="tip-title">{{ t.date }}</div>
                <div>报名：{{ t.registrations }}</div>
                <div>答卷：{{ t.attempts }}</div>
                <div>已付订单：{{ t.paidOrders }}</div>
                <div>成交金额：{{ money(t.revenueFen) }}</div>
              </template>
              <div class="trend-bars">
                <div class="bar reg" :style="{ height: barPct(t.registrations, trendMax) }" />
                <div class="bar att" :style="{ height: barPct(t.attempts, trendMax) }" />
                <div class="bar ord" :style="{ height: barPct(t.paidOrders, trendMax) }" />
              </div>
            </el-tooltip>
            <div class="trend-date">{{ t.date.slice(5) }}</div>
            <div class="trend-revenue">{{ money(t.revenueFen) }}</div>
          </div>
        </div>

        <div class="legend">
          <span><i class="dot reg" /> 报名</span>
          <span><i class="dot att" /> 答卷</span>
          <span><i class="dot ord" /> 已付订单</span>
        </div>
      </div>

      <div class="page-card todo-card">
        <div class="card-head">
          <div>
            <h3 class="page-title">待我处理</h3>
            <p class="page-desc">
              待阅卷与待审核退款合计
              <b :class="pendingTotal > 0 ? 'warn' : ''">{{ pendingTotal }}</b> 项
            </p>
          </div>
        </div>
        <div class="todo-list">
          <div v-for="item in todos" :key="item.key" class="todo" @click="router.push(item.path)">
            <div class="todo-icon" :style="{ background: item.color + '1a', color: item.color }">
              <el-icon :size="18"><component :is="item.icon" /></el-icon>
            </div>
            <div class="todo-body">
              <div class="todo-label">{{ item.label }}</div>
              <div class="todo-hint">{{ item.hint }}</div>
            </div>
            <div class="todo-count" :class="{ highlight: item.attention && item.count > 0 }">{{ item.count }}</div>
            <el-icon class="todo-arrow" :size="14"><ArrowRight /></el-icon>
          </div>
        </div>
        <div class="todo-foot" :class="{ attention: pendingTotal > 0 }">
          {{ pendingTotal > 0 ? '有待处理事项，建议优先处理阅卷与退款' : '暂无待处理事项，状态良好' }}
        </div>
      </div>
    </div>

    <!-- 次区：运营指标 + 快捷入口 -->
    <div class="bottom-grid">
      <div class="page-card">
        <h3 class="page-title">累计运营指标</h3>
        <p class="page-desc">金额单位为分，已支付订单计入</p>
        <div class="metric-grid">
          <div v-for="item in metrics" :key="item.key" class="metric" @click="router.push(item.path)">
            <div class="metric-head">
              <el-icon :size="15" class="metric-icon"><component :is="item.icon" /></el-icon>
              <span>{{ item.label }}</span>
            </div>
            <div class="metric-value">{{ data?.[item.key] ?? 0 }}</div>
          </div>
        </div>
        <div class="revenue">
          <div>
            <div class="revenue-label">累计成交金额</div>
            <div class="revenue-value">{{ money(data?.paidAmountFen) }}</div>
          </div>
          <el-tag type="success" effect="plain">已支付订单</el-tag>
        </div>
      </div>

      <div class="page-card">
        <h3 class="page-title">快捷入口</h3>
        <p class="page-desc">高频操作一步直达，减少菜单层级跳转</p>
        <div class="shortcut-grid">
          <div v-for="item in shortcuts" :key="item.path" class="shortcut" @click="router.push(item.path)">
            <div class="shortcut-icon" :style="{ background: item.color + '1a', color: item.color }">
              <el-icon :size="20"><component :is="item.icon" /></el-icon>
            </div>
            <span class="shortcut-label">{{ item.label }}</span>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.dashboard {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

/* ---------- 欢迎条 ---------- */
.banner {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  flex-wrap: wrap;
  border-radius: 12px;
  padding: 20px 24px;
  color: #fff;
  background: linear-gradient(120deg, #1d4ed8 0%, #4f46e5 55%, #7c3aed 100%);
  box-shadow: 0 8px 24px rgba(29, 78, 216, 0.22);
}

.banner-title {
  font-size: 20px;
  font-weight: 600;
}

.banner-date {
  font-size: 13px;
  opacity: 0.82;
  margin-top: 6px;
}

.banner-left {
  min-width: max-content;
}

.banner-right {
  display: grid;
  grid-template-columns: repeat(4, minmax(88px, 1fr));
  gap: 10px;
}

.today-chip {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 3px;
  background: rgba(255, 255, 255, 0.14);
  border: 1px solid rgba(255, 255, 255, 0.22);
  border-radius: 10px;
  padding: 10px 8px;
}

.today-chip.revenue {
  background: rgba(255, 255, 255, 0.2);
}

.today-value {
  font-size: 17px;
  font-weight: 700;
  line-height: 1.2;
  white-space: nowrap;
}

.today-label {
  font-size: 11px;
  opacity: 0.85;
  white-space: nowrap;
}

/* ---------- 核心指标 ---------- */
.kpi-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16px;
}

.kpi {
  position: relative;
  display: flex;
  align-items: center;
  gap: 12px;
  background: #fff;
  border-radius: 12px;
  padding: 16px;
  cursor: pointer;
  box-shadow: 0 1px 3px rgba(15, 23, 42, 0.06);
  transition: transform 0.15s, box-shadow 0.15s;
}

.kpi:hover {
  transform: translateY(-2px);
  box-shadow: 0 8px 20px rgba(15, 23, 42, 0.1);
}

.kpi-icon {
  width: 46px;
  height: 46px;
  border-radius: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.kpi-value {
  font-size: 24px;
  font-weight: 700;
  line-height: 1.2;
}

.kpi-unit {
  font-size: 12px;
  font-weight: 400;
  color: #8a94a6;
  margin-left: 3px;
}

.kpi-label {
  font-size: 12px;
  color: #8a94a6;
  margin-top: 2px;
}

.kpi-arrow {
  position: absolute;
  right: 14px;
  top: 14px;
  color: #cbd5e1;
  transition: transform 0.15s, color 0.15s;
}

.kpi:hover .kpi-arrow {
  color: #2563eb;
  transform: translateX(2px);
}

/* ---------- 主区 ---------- */
.card-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

.range-switch {
  display: flex;
  background: #f1f5f9;
  border-radius: 8px;
  padding: 3px;
  flex-shrink: 0;
}

.range-item {
  font-size: 12px;
  color: #64748b;
  padding: 4px 12px;
  border-radius: 6px;
  cursor: pointer;
  transition: background 0.15s, color 0.15s;
}

.range-item.active {
  background: #fff;
  color: #1d4ed8;
  font-weight: 600;
  box-shadow: 0 1px 2px rgba(15, 23, 42, 0.08);
}

.trend-summary {
  display: flex;
  gap: 18px;
  flex-wrap: wrap;
  font-size: 12px;
  color: #8a94a6;
  margin: 4px 0 10px;
}

.trend-summary b {
  color: #1f2937;
  font-size: 14px;
}

.trend-summary b.green {
  color: #16a34a;
}

.trend {
  display: flex;
  gap: 10px;
  align-items: flex-end;
  overflow-x: auto;
  padding: 4px 2px 0;
  background-image: repeating-linear-gradient(
    to top,
    #f1f5f9 0,
    #f1f5f9 1px,
    transparent 1px,
    transparent 40px
  );
  background-size: 100% 120px;
  background-position: bottom;
  background-repeat: no-repeat;
}

.trend-empty {
  width: 100%;
  text-align: center;
  color: #b0b8c4;
  font-size: 13px;
  padding: 48px 0;
}

.trend-day {
  flex: 1;
  min-width: 40px;
  text-align: center;
}

.trend-bars {
  display: flex;
  align-items: flex-end;
  justify-content: center;
  gap: 3px;
  height: 120px;
  cursor: pointer;
}

.bar {
  width: 9px;
  min-height: 2px;
  border-radius: 3px 3px 0 0;
  transition: filter 0.15s;
}

.trend-bars:hover .bar {
  filter: brightness(1.15);
}

.bar.reg {
  background: #3b82f6;
}

.bar.att {
  background: #8b5cf6;
}

.bar.ord {
  background: #10b981;
}

.trend-date {
  font-size: 11px;
  color: #8a94a6;
  margin-top: 6px;
  white-space: nowrap;
}

.trend-revenue {
  font-size: 10px;
  color: #16a34a;
  white-space: nowrap;
}

.legend {
  display: flex;
  gap: 16px;
  margin-top: 12px;
  font-size: 12px;
  color: #64748b;
}

.dot {
  display: inline-block;
  width: 8px;
  height: 8px;
  border-radius: 2px;
  margin-right: 4px;
}

.dot.reg {
  background: #3b82f6;
}

.dot.att {
  background: #8b5cf6;
}

.dot.ord {
  background: #10b981;
}

/* ---------- 待办 ---------- */
/* 待办是运营第一优先级：优先显示在趋势图上方；宽屏时右侧并排 */
.todo-card {
  display: flex;
  flex-direction: column;
  order: -1;
  grid-column: 1 / -1;
}

.main-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  gap: 16px;
  align-items: stretch;
}

@media (min-width: 1280px) {
  .main-grid {
    grid-template-columns: minmax(0, 1.9fr) minmax(300px, 1fr);
  }

  .todo-card {
    order: 0;
    grid-column: auto;
  }
}

.todo-card .page-desc b {
  color: #16a34a;
  font-size: 14px;
}

.todo-card .page-desc b.warn {
  color: #dc2626;
}

.todo-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
  flex: 1;
}

.todo {
  display: flex;
  align-items: center;
  gap: 12px;
  background: #f8fafc;
  border: 1px solid transparent;
  border-radius: 10px;
  padding: 12px 14px;
  cursor: pointer;
  transition: background 0.15s, border-color 0.15s, transform 0.15s;
}

.todo:hover {
  background: #eff6ff;
  border-color: #bfdbfe;
  transform: translateX(2px);
}

.todo-icon {
  width: 36px;
  height: 36px;
  border-radius: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.todo-body {
  flex: 1;
  min-width: 0;
}

.todo-label {
  font-size: 14px;
  font-weight: 600;
}

.todo-hint {
  font-size: 12px;
  color: #8a94a6;
  margin-top: 1px;
}

.todo-count {
  font-size: 20px;
  font-weight: 700;
  color: #94a3b8;
}

.todo-count.highlight {
  color: #dc2626;
}

.todo-arrow {
  color: #cbd5e1;
}

.todo-foot {
  margin-top: 12px;
  font-size: 12px;
  color: #8a94a6;
  background: #f8fafc;
  border-radius: 8px;
  padding: 8px 12px;
  text-align: center;
}

.todo-foot.attention {
  color: #b45309;
  background: #fffbeb;
}

/* ---------- 次区 ---------- */
.bottom-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: 16px;
}

.metric-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 10px;
}

.metric {
  background: #f8fafc;
  border-radius: 10px;
  padding: 12px;
  cursor: pointer;
  transition: background 0.15s;
}

.metric:hover {
  background: #eff6ff;
}

.metric-head {
  display: flex;
  align-items: center;
  gap: 5px;
  font-size: 12px;
  color: #8a94a6;
}

.metric-icon {
  color: #64748b;
}

.metric-value {
  font-size: 20px;
  font-weight: 700;
  margin-top: 6px;
}

.revenue {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 14px;
  padding-top: 14px;
  border-top: 1px solid #f1f5f9;
}

.revenue-label {
  font-size: 13px;
  color: #8a94a6;
}

.revenue-value {
  font-size: 26px;
  font-weight: 700;
  color: #16a34a;
}

.shortcut-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 10px;
}

.shortcut {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  background: #f8fafc;
  border-radius: 10px;
  padding: 14px 8px;
  cursor: pointer;
  transition: background 0.15s, transform 0.15s;
}

.shortcut:hover {
  background: #eff6ff;
  transform: translateY(-2px);
}

.shortcut-icon {
  width: 38px;
  height: 38px;
  border-radius: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.shortcut-label {
  font-size: 12px;
  color: #374151;
}

.tip-title {
  font-weight: 600;
  margin-bottom: 2px;
}

@media (max-width: 1200px) {
  .bottom-grid {
    grid-template-columns: 1fr;
  }

  /* 中等宽度下欢迎条左右分栏会把数据胶囊挤扁：改为上下堆叠、胶囊平分整行 */
  .banner {
    flex-direction: column;
    align-items: stretch;
  }

  .banner-right {
    width: 100%;
  }
}

@media (max-width: 900px) {
  .kpi-grid {
    grid-template-columns: repeat(2, 1fr);
  }
}

@media (max-width: 720px) {
  .metric-grid,
  .shortcut-grid {
    grid-template-columns: repeat(2, 1fr);
  }

  .banner-right {
    grid-template-columns: repeat(2, 1fr);
  }
}
</style>
