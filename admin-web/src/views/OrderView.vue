<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { tradeApi, type Order, type OrderItem, type Refund } from '@/api'
import { money, ORDER_STATUS, REFUND_STATUS, useTradeOverview } from '@/composables/useTrade'

const route = useRoute()
const router = useRouter()

const { overview, loadOverview } = useTradeOverview()

const loading = ref(false)
const list = ref<Order[]>([])
const total = ref(0)
const query = reactive({ pageNo: 1, pageSize: 10, status: '', keyword: '' })

/**
 * 从 URL 带入筛选条件。
 *
 * <p>退款审核、对账中心都会用「查看该订单」跳到本页并带上订单号——
 * 落地后如果还要人再手动粘贴一遍单号，跳转就失去了意义。</p>
 */
function applyRouteQuery() {
  const kw = route.query.keyword
  const st = route.query.status
  if (typeof kw === 'string' && kw) query.keyword = kw
  if (typeof st === 'string' && st) query.status = st
}

/**
 * 状态筛选做成带计数的胶囊而不是下拉框。
 *
 * 订单页最高频的动作是「看还有多少待支付 / 有多少退了款」，下拉框要先展开再找，
 * 而计数本来就已经从概览接口拿回来了——直接摆在按钮上，一次点击就完成筛选。
 */
const statusTabs = computed(() => [
  { key: '', label: '全部', count: overview.value?.orderTotal ?? 0 },
  { key: 'PENDING_PAYMENT', label: '待支付', count: overview.value?.orderPendingPayment ?? 0 },
  { key: 'PAID', label: '已支付', count: overview.value?.orderPaid ?? 0 },
  { key: 'COMPLETED', label: '已完成', count: overview.value?.orderCompleted ?? 0 },
  // 已退款与部分退款是两个枚举；页签合成一个视图，后端按逗号分隔的状态集合过滤
  { key: 'REFUNDED,PARTIALLY_REFUNDED', label: '退款', count: overview.value?.orderRefunded ?? 0 },
  { key: 'CLOSED', label: '已关闭', count: overview.value?.orderClosed ?? 0 }
])

async function load() {
  loading.value = true
  try {
    const params: Record<string, any> = { pageNo: query.pageNo, pageSize: query.pageSize }
    if (query.status) params.status = query.status
    if (query.keyword.trim()) params.keyword = query.keyword.trim()
    const page = await tradeApi.orders(params)
    list.value = page.list
    total.value = page.total
  } finally {
    loading.value = false
  }
}

function switchStatus(key: string) {
  if (query.status === key) return
  query.status = key
  query.pageNo = 1
  syncUrl()
  load()
}

/**
 * 手动改条件时把筛选同步进 URL。
 *
 * <p>不这么做会有个隐蔽的坑：从退款页带着 keyword 跳进来，运营随后清掉关键词重新查，
 * 地址栏里旧 keyword 还在——刷新页面筛选条件又「复活」，看起来像查询没生效。</p>
 *
 * <p>用 selfSync 标记避免自己触发自己：replace 会改变 route.query，
 * 若不标记，下面的 watcher 会再跑一遍 applyRouteQuery + load，多打一次请求。</p>
 */
let selfSync = false

function syncUrl() {
  const next: Record<string, string> = {}
  if (query.keyword.trim()) next.keyword = query.keyword.trim()
  if (query.status) next.status = query.status

  const current: Record<string, string> = {}
  if (typeof route.query.keyword === 'string' && route.query.keyword) current.keyword = route.query.keyword
  if (typeof route.query.status === 'string' && route.query.status) current.status = route.query.status

  if (JSON.stringify(next) !== JSON.stringify(current)) {
    selfSync = true
    router.replace({ path: '/orders', query: next }).finally(() => { selfSync = false })
  }
}

function search() {
  query.pageNo = 1
  syncUrl()
  load()
}

function reset() {
  query.keyword = ''
  query.status = ''
  query.pageNo = 1
  syncUrl()
  load()
}

function statusOf(status?: string) {
  return ORDER_STATUS[status || ''] || { text: status || '—', type: 'info' as const }
}

/** 实付与标价不一致才显示优惠列，否则一排「-¥0.00」纯属噪音 */
function hasDiscount(row: Order) {
  return (row.discountAmountFen || 0) > 0
}

async function copyOrderNo(orderNo: string) {
  try {
    await navigator.clipboard.writeText(orderNo)
    ElMessage.success('订单号已复制')
  } catch {
    ElMessage.warning('浏览器不允许自动复制，请手动选中')
  }
}

// ---------------------------------------------------------------- 详情抽屉

const drawerVisible = ref(false)
const detail = ref<Order | null>(null)
const items = ref<OrderItem[]>([])
const refunds = ref<Refund[]>([])
const detailLoading = ref(false)

async function openDetail(row: Order) {
  detail.value = row
  items.value = []
  refunds.value = []
  drawerVisible.value = true
  detailLoading.value = true
  try {
    // 明细与退款记录并行拉取；退款按订单号反查，客服核对「这单退过几次」不用再切页面
    const [itemRows, refundPage] = await Promise.all([
      tradeApi.orderItems(row.id).catch(() => [] as OrderItem[]),
      tradeApi.refunds({ pageNo: 1, pageSize: 20, keyword: row.orderNo }).catch(() => null)
    ])
    items.value = itemRows
    refunds.value = refundPage?.list ?? []
  } finally {
    detailLoading.value = false
  }
}

const itemTotal = computed(() => items.value.reduce((sum, item) => sum + (item.amountFen || 0), 0))
const itemRefunded = computed(() => items.value.reduce((sum, item) => sum + (item.refundedAmountFen || 0), 0))

/** 跳到退款审核并按订单号预筛：客服核对完订单常要顺手处理退款，不该让人重放单号 */
function gotoRefunds(orderNo: string) {
  drawerVisible.value = false
  router.push({ path: '/refunds', query: { keyword: orderNo } })
}

onMounted(() => {
  applyRouteQuery()
  load()
  loadOverview()
})

/**
 * 同一路由只变 query 时组件不会重建，onMounted 不再触发。
 * 对账中心、退款页都可能在订单页已经打开的情况下继续跳转过来带新单号，
 * 不监听 query 就会停在旧筛选上，看起来像「跳转没生效」。
 */
watch(
  () => [route.query.keyword, route.query.status],
  () => {
    if (route.path !== '/orders') return
    // 自己 replace 引起的变化已经由 syncUrl 的调用方负责 load，跳过避免重复请求
    if (selfSync) return
    applyRouteQuery()
    query.pageNo = 1
    load()
  }
)
</script>

<template>
  <div class="page-card">
    <h3 class="page-title">订单管理</h3>
    <p class="page-desc">
      金额以「分」存储，支付成功只以服务端回调（验签 + 金额核对）为准。
      退款审核与商品维护已拆到各自的菜单，这里只负责查单与核对。
    </p>

    <div class="stat-strip">
      <div class="stat-item">
        <div class="stat-value">{{ overview?.orderTotal ?? 0 }}</div>
        <div class="stat-label">订单总数</div>
      </div>
      <div class="stat-item" :class="(overview?.orderPendingPayment ?? 0) > 0 ? 'is-warn' : ''">
        <div class="stat-value">{{ overview?.orderPendingPayment ?? 0 }}</div>
        <div class="stat-label">待支付（超时未付自动关闭）</div>
      </div>
      <div class="stat-item is-ok">
        <div class="stat-value">{{ overview?.todayPaidCount ?? 0 }}</div>
        <div class="stat-label">今日成交单数</div>
      </div>
      <div class="stat-item">
        <div class="stat-value">{{ money(overview?.todayPaidAmountFen) }}</div>
        <div class="stat-label">今日成交金额</div>
      </div>
      <div class="stat-item">
        <div class="stat-value">{{ money(overview?.paidAmountFen) }}</div>
        <div class="stat-label">累计成交金额</div>
      </div>
    </div>

    <div class="status-tabs">
      <button v-for="tab in statusTabs" :key="tab.key" type="button" class="status-tab"
              :class="{ active: query.status === tab.key }" @click="switchStatus(tab.key)">
        {{ tab.label }}
        <span class="tab-count">{{ tab.count }}</span>
      </button>
    </div>

    <div class="toolbar">
      <el-input v-model="query.keyword" placeholder="订单号 / 商品名称" style="width: 240px" clearable
                @keyup.enter="search" />
      <el-button type="primary" @click="search">查询</el-button>
      <el-button @click="reset">重置</el-button>
      <div class="spacer" />
      <el-button @click="() => { load(); loadOverview() }">刷新</el-button>
    </div>

    <el-table v-loading="loading" :data="list" border stripe>
      <el-table-column label="订单号" min-width="210">
        <template #default="{ row }">
          <span class="mono link" @click="copyOrderNo(row.orderNo)">{{ row.orderNo }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="productName" label="商品" min-width="170" show-overflow-tooltip />
      <el-table-column label="实付金额" width="130" align="right">
        <template #default="{ row }">
          <div class="amount">{{ money(row.payAmountFen) }}</div>
          <div v-if="hasDiscount(row)" class="amount-sub">
            原价 {{ money(row.totalAmountFen) }} · 优惠 -{{ money(row.discountAmountFen) }}
          </div>
        </template>
      </el-table-column>
      <el-table-column label="用户" width="96">
        <template #default="{ row }">#{{ row.userId }}</template>
      </el-table-column>
      <el-table-column label="状态" width="104">
        <template #default="{ row }">
          <el-tag :type="statusOf(row.status).type" effect="plain">{{ statusOf(row.status).text }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="支付时间" width="170">
        <template #default="{ row }">{{ row.paidAt || '—' }}</template>
      </el-table-column>
      <el-table-column label="创建时间" width="170">
        <template #default="{ row }">{{ row.createdAt || '—' }}</template>
      </el-table-column>
      <el-table-column label="操作" width="90" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openDetail(row)">详情</el-button>
        </template>
      </el-table-column>
      <template #empty>
        <div class="empty-wrap">
          <el-empty :description="query.keyword || query.status
            ? '没有符合条件的订单，换个关键词或状态试试'
            : '还没有订单——考生在商城下单后会出现在这里'" :image-size="72" />
        </div>
      </template>
    </el-table>

    <div class="pager">
      <el-pagination v-model:current-page="query.pageNo" v-model:page-size="query.pageSize"
                     :total="total" :page-sizes="[10, 20, 50]"
                     layout="total, sizes, prev, pager, next" @current-change="load"
                     @size-change="search" />
    </div>

    <el-drawer v-model="drawerVisible" :title="`订单详情 · ${detail?.orderNo || ''}`" size="620px">
      <div v-loading="detailLoading">
        <el-descriptions v-if="detail" :column="2" border size="small">
          <el-descriptions-item label="状态">
            <el-tag :type="statusOf(detail.status).type" effect="plain">
              {{ statusOf(detail.status).text }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="用户">#{{ detail.userId }}</el-descriptions-item>
          <el-descriptions-item label="商品">{{ detail.productName }}</el-descriptions-item>
          <el-descriptions-item label="数量">{{ detail.quantity }}</el-descriptions-item>
          <el-descriptions-item label="原价合计">{{ money(detail.totalAmountFen) }}</el-descriptions-item>
          <el-descriptions-item label="券抵扣">{{ money(detail.discountAmountFen) }}</el-descriptions-item>
          <el-descriptions-item label="实付金额">
            <b class="amount">{{ money(detail.payAmountFen) }}</b>
          </el-descriptions-item>
          <el-descriptions-item label="创建时间">{{ detail.createdAt || '—' }}</el-descriptions-item>
          <el-descriptions-item label="支付时间">{{ detail.paidAt || '—' }}</el-descriptions-item>
          <el-descriptions-item label="超时关闭">
            {{ detail.status === 'PENDING_PAYMENT' ? (detail.expireAt || '—') : '—' }}
          </el-descriptions-item>
          <el-descriptions-item v-if="detail.remark" label="下单备注" :span="2">
            {{ detail.remark }}
          </el-descriptions-item>
        </el-descriptions>

        <el-divider content-position="left">订单明细</el-divider>
        <el-table :data="items" border size="small">
          <el-table-column prop="productName" label="商品" min-width="130" show-overflow-tooltip />
          <el-table-column prop="skuName" label="规格" min-width="90" show-overflow-tooltip />
          <el-table-column label="单价" width="90" align="right">
            <template #default="{ row }">{{ money(row.unitPriceFen) }}</template>
          </el-table-column>
          <el-table-column prop="quantity" label="数量" width="60" align="center" />
          <el-table-column label="金额" width="90" align="right">
            <template #default="{ row }">{{ money(row.amountFen) }}</template>
          </el-table-column>
          <el-table-column label="已退" width="90" align="right">
            <template #default="{ row }">
              <span :class="{ muted: !row.refundedAmountFen }">
                {{ row.refundedAmountFen ? money(row.refundedAmountFen) : '—' }}
              </span>
            </template>
          </el-table-column>
        </el-table>
        <div v-if="items.length" class="drawer-sum">
          <span>明细合计 <b>{{ money(itemTotal) }}</b></span>
          <span v-if="itemRefunded">已退合计 <b class="danger">{{ money(itemRefunded) }}</b></span>
        </div>
        <p v-else class="page-desc" style="margin: 6px 0 0;">暂无明细记录。</p>

        <el-divider content-position="left">退款记录</el-divider>
        <el-table v-if="refunds.length" :data="refunds" border size="small">
          <el-table-column prop="refundNo" label="退款单号" min-width="150" show-overflow-tooltip />
          <el-table-column label="金额" width="86" align="right">
            <template #default="{ row }">{{ money(row.amountFen) }}</template>
          </el-table-column>
          <el-table-column label="状态" width="92">
            <template #default="{ row }">
              <el-tag :type="REFUND_STATUS[row.status]?.type || 'info'" effect="plain" size="small">
                {{ REFUND_STATUS[row.status]?.text || row.status }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="appliedAt" label="申请时间" min-width="150" />
          <el-table-column label="操作" width="80" fixed="right">
            <template #default>
              <!-- 退款处理是动钱动作，跳转只负责带上订单号定位，审批仍要回到退款页确认 -->
              <el-button link type="primary" @click="gotoRefunds(detail!.orderNo)">去处理</el-button>
            </template>
          </el-table-column>
        </el-table>
        <p v-else class="page-desc" style="margin: 6px 0 0;">该订单没有退款申请。</p>

        <div v-if="overview && (overview.refundPending > 0 || overview.refundSuccess > 0)" class="drawer-tip">
          <span>待审核退款 {{ overview.refundPending }} 笔、已退款 {{ overview.refundSuccess }} 笔。</span>
          <el-button link type="primary" size="small" @click="gotoRefunds(detail!.orderNo)">
            查看本单退款 →
          </el-button>
        </div>
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
.status-tabs {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  margin-bottom: 14px;
}

.status-tab {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 6px 14px;
  border: 1px solid #e5e9f2;
  border-radius: 999px;
  background: #fff;
  color: #4b5563;
  font-size: 13px;
  font-family: inherit;
  cursor: pointer;
  transition: all 0.15s;
}

.status-tab:hover {
  border-color: #bfdbfe;
  color: #1d4ed8;
  background: #f5f9ff;
}

.status-tab.active {
  border-color: transparent;
  color: #fff;
  background: linear-gradient(90deg, #2563eb, #4f46e5);
  box-shadow: 0 2px 8px rgba(37, 99, 235, 0.28);
}

.tab-count {
  font-size: 12px;
  font-variant-numeric: tabular-nums;
  padding: 0 6px;
  border-radius: 999px;
  background: rgba(148, 163, 184, 0.16);
}

.status-tab.active .tab-count {
  background: rgba(255, 255, 255, 0.24);
}

.mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 12px;
}

.link {
  color: #2563eb;
  cursor: pointer;
}

.link:hover {
  text-decoration: underline;
}

.amount {
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}

.amount-sub {
  font-size: 11px;
  color: #94a3b8;
  margin-top: 2px;
}

.danger {
  color: #dc2626;
}

.muted {
  color: #b0b7c3;
}

.drawer-sum {
  display: flex;
  gap: 20px;
  justify-content: flex-end;
  margin-top: 8px;
  font-size: 13px;
  color: #64748b;
}

.drawer-tip {
  margin-top: 16px;
  padding: 10px 12px;
  border-radius: 8px;
  background: #fffbeb;
  color: #b45309;
  font-size: 12px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  flex-wrap: wrap;
}
</style>
