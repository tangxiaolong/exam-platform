<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { refundApi, type RefundItem } from '@/api'
import { money, REFUND_STATUS, useTradeOverview } from '@/composables/useTrade'

/**
 * 退款审核。
 *
 * <p>这是动钱页面，三件事必须一眼可见：待审多少笔、涉及多少钱、每一笔退的是哪张单。
 * 之前状态列直接吐英文枚举、没有搜索框，客服拿着订单号来问只能翻页找。</p>
 */
const route = useRoute()
const router = useRouter()

const { overview, loadOverview } = useTradeOverview()

const list = ref<RefundItem[]>([])
const total = ref(0)
const loading = ref(false)
const query = reactive({ pageNo: 1, pageSize: 10, status: 'APPLIED', keyword: '' })

const statusTabs = computed(() => [
  { key: 'APPLIED', label: '待审核', count: overview.value?.refundPending ?? 0 },
  { key: 'SUCCESS', label: '已退款', count: overview.value?.refundSuccess ?? 0 },
  { key: 'REJECTED', label: '已驳回', count: overview.value?.refundRejected ?? 0 },
  { key: '', label: '全部', count: 0 }
])

/**
 * 从订单详情跳过来时带 keyword，此时默认落在「全部」页签：
 * 待审的退款可能早就处理完了，停留在「待审核」只会显示空表，让人以为跳转丢了数据。
 */
function applyRouteQuery() {
  const kw = route.query.keyword
  const st = route.query.status
  if (typeof kw === 'string' && kw) {
    query.keyword = kw
    query.status = typeof st === 'string' && st ? st : ''
  } else if (typeof st === 'string' && st) {
    query.status = st
  }
}

async function load() {
  loading.value = true
  try {
    const params: Record<string, any> = { pageNo: query.pageNo, pageSize: query.pageSize }
    if (query.status) params.status = query.status
    if (query.keyword.trim()) params.keyword = query.keyword.trim()
    const page = await refundApi.page(params)
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

/** 手动改条件同步回 URL，避免地址栏残留旧 keyword 让刷新后筛选「复活」 */
let selfSync = false

function syncUrl() {
  const next: Record<string, string> = {}
  if (query.keyword.trim()) next.keyword = query.keyword.trim()
  if (query.status && query.status !== 'APPLIED') next.status = query.status

  const current: Record<string, string> = {}
  if (typeof route.query.keyword === 'string' && route.query.keyword) current.keyword = route.query.keyword
  if (typeof route.query.status === 'string' && route.query.status) current.status = route.query.status

  if (JSON.stringify(next) !== JSON.stringify(current)) {
    selfSync = true
    router.replace({ path: '/refunds', query: next }).finally(() => { selfSync = false })
  }
}

function search() {
  query.pageNo = 1
  syncUrl()
  load()
}

function reset() {
  query.keyword = ''
  query.status = 'APPLIED'
  query.pageNo = 1
  syncUrl()
  load()
}

/** 已处理与否以 auditedAt 为准：申请时为空，审核后才落值，比解读 status 更稳 */
function isPending(row: RefundItem) {
  return !row.auditedAt && row.status === 'APPLIED'
}

function statusOf(status?: string) {
  return REFUND_STATUS[status || ''] || { text: status || '—', type: 'info' as const }
}

function refundType(row: RefundItem) {
  return row.orderItemId ? `部分退款 · ${row.quantity ?? 1} 件` : '整单退款'
}

async function copyText(text?: string) {
  if (!text) return
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success('已复制')
  } catch {
    ElMessage.warning('浏览器不允许自动复制，请手动选中')
  }
}

/** 跳到订单管理并按订单号预筛：核对「这单买了什么、退了多少」不用重新粘贴单号 */
function gotoOrder(orderNo?: string) {
  if (!orderNo) return
  router.push({ path: '/orders', query: { keyword: orderNo } })
}

// ---------------------------------------------------------------- 审核弹窗

const auditVisible = ref(false)
const auditing = ref(false)
const auditForm = reactive({ approve: true, remark: '', row: undefined as RefundItem | undefined })

function openAudit(row: RefundItem, approve: boolean) {
  auditForm.row = row
  auditForm.approve = approve
  auditForm.remark = ''
  auditVisible.value = true
}

async function submitAudit() {
  const row = auditForm.row
  if (!row?.id) return
  if (!auditForm.approve && !auditForm.remark.trim()) {
    ElMessage.warning('驳回必须填写原因：考生侧会收到这条说明')
    return
  }
  auditing.value = true
  try {
    if (auditForm.approve) {
      await refundApi.approve(row.id, auditForm.remark.trim() || undefined)
      ElMessage.success('已同意退款，整单退款会同时回收未使用的权益')
    } else {
      await refundApi.reject(row.id, auditForm.remark.trim())
      ElMessage.success('已驳回退款')
    }
    auditVisible.value = false
    await Promise.all([load(), loadOverview()])
  } finally {
    auditing.value = false
  }
}

onMounted(() => {
  applyRouteQuery()
  load()
  loadOverview()
})

// 同一路由只变 query 时组件不会重建：从订单详情再次跳过来必须重新按新单号筛
watch(
  () => [route.query.keyword, route.query.status],
  () => {
    if (route.path !== '/refunds') return
    if (selfSync) return
    applyRouteQuery()
    query.pageNo = 1
    load()
  }
)
</script>

<template>
  <div class="page-card">
    <h3 class="page-title">退款审核</h3>
    <p class="page-desc">
      同意退款会调用原路退回并回收未使用的权益；已处理的单子不再显示审批按钮。
    </p>

    <div class="stat-strip">
      <div class="stat-item" :class="(overview?.refundPending ?? 0) > 0 ? 'is-danger' : 'is-ok'">
        <div class="stat-value">{{ overview?.refundPending ?? 0 }}</div>
        <div class="stat-label">待审核退款</div>
      </div>
      <div class="stat-item">
        <div class="stat-value">{{ overview?.refundSuccess ?? 0 }}</div>
        <div class="stat-label">已退款笔数</div>
      </div>
      <div class="stat-item">
        <div class="stat-value">{{ money(overview?.refundedAmountFen) }}</div>
        <div class="stat-label">累计退款金额</div>
      </div>
      <div class="stat-item">
        <div class="stat-value">{{ overview?.refundRejected ?? 0 }}</div>
        <div class="stat-label">已驳回</div>
      </div>
    </div>

    <div class="status-tabs">
      <button v-for="tab in statusTabs" :key="tab.key" type="button" class="status-tab"
              :class="{ active: query.status === tab.key }" @click="switchStatus(tab.key)">
        {{ tab.label }}
        <span v-if="tab.key" class="tab-count">{{ tab.count }}</span>
      </button>
    </div>

    <div class="toolbar">
      <el-input v-model="query.keyword" placeholder="退款单号 / 订单号" style="width: 240px" clearable
                @keyup.enter="search" />
      <el-button type="primary" @click="search">查询</el-button>
      <el-button @click="reset">重置</el-button>
      <div class="spacer" />
      <el-button @click="() => { load(); loadOverview() }">刷新</el-button>
    </div>

    <el-table v-loading="loading" :data="list" border stripe>
      <el-table-column label="退款单号" min-width="180">
        <template #default="{ row }">
          <span class="mono link" @click="copyText(row.refundNo)">{{ row.refundNo }}</span>
        </template>
      </el-table-column>
      <el-table-column label="订单号" min-width="180">
        <template #default="{ row }">
          <span class="mono link" :title="'在订单管理中查看 ' + row.orderNo"
                @click="gotoOrder(row.orderNo)">{{ row.orderNo }}</span>
        </template>
      </el-table-column>
      <el-table-column label="退款金额" width="120" align="right">
        <template #default="{ row }">
          <span class="amount">{{ money(row.amountFen) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="退款范围" width="130">
        <template #default="{ row }">
          <el-tag :type="row.orderItemId ? 'warning' : 'info'" effect="plain" size="small">
            {{ refundType(row) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="reason" label="申请原因" min-width="150" show-overflow-tooltip />
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="statusOf(row.status).type" effect="plain">
            {{ statusOf(row.status).text }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="appliedAt" label="申请时间" width="165" />
      <el-table-column label="审核信息" min-width="200">
        <template #default="{ row }">
          <div v-if="row.auditedAt" class="audit-info">
            <span>{{ row.auditedAt }}</span>
            <span v-if="row.auditRemark" class="audit-remark">{{ row.auditRemark }}</span>
          </div>
          <span v-else class="pending">等待审核</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="140" fixed="right">
        <template #default="{ row }">
          <template v-if="isPending(row)">
            <el-button link type="success" @click="openAudit(row, true)">通过</el-button>
            <el-button link type="danger" @click="openAudit(row, false)">驳回</el-button>
          </template>
          <span v-else class="muted">已处理</span>
        </template>
      </el-table-column>
      <template #empty>
        <div class="empty-wrap">
          <el-empty :description="query.keyword
            ? '没有找到匹配的退款单，核对一下单号'
            : query.status === 'APPLIED'
              ? '没有待审核的退款，当前无需处理'
              : '暂无退款记录'" :image-size="72" />
        </div>
      </template>
    </el-table>

    <div class="pager">
      <el-pagination v-model:current-page="query.pageNo" v-model:page-size="query.pageSize"
                     :total="total" :page-sizes="[10, 20, 50]"
                     layout="total, sizes, prev, pager, next" @current-change="load"
                     @size-change="search" />
    </div>

    <el-dialog v-model="auditVisible" :title="auditForm.approve ? '同意退款' : '驳回退款'" width="560px">
      <el-alert
        :type="auditForm.approve ? 'warning' : 'info'"
        :closable="false"
        show-icon
        class="audit-tip"
        :title="auditForm.approve
          ? '同意后将调用原路退回；整单退款会同时回收该订单未使用的权益，操作不可撤回。'
          : '驳回后考生侧会收到这条原因说明，请写清楚不符合哪条退款规则。'"
      />
      <el-descriptions :column="2" border size="small" class="audit-desc">
        <el-descriptions-item label="退款单号">{{ auditForm.row?.refundNo }}</el-descriptions-item>
        <el-descriptions-item label="订单号">
          <span class="mono link" @click="gotoOrder(auditForm.row?.orderNo)">{{ auditForm.row?.orderNo }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="退款金额">
          <b class="amount">{{ money(auditForm.row?.amountFen) }}</b>
        </el-descriptions-item>
        <el-descriptions-item label="退款范围">{{ auditForm.row ? refundType(auditForm.row) : '—' }}</el-descriptions-item>
        <el-descriptions-item label="申请原因" :span="2">{{ auditForm.row?.reason || '未填写' }}</el-descriptions-item>
      </el-descriptions>
      <el-form label-width="80px" class="audit-form">
        <el-form-item :label="auditForm.approve ? '审核备注' : '驳回原因'" :required="!auditForm.approve">
          <el-input v-model="auditForm.remark" type="textarea" :rows="3" maxlength="200" show-word-limit
                    :placeholder="auditForm.approve
                      ? '选填，会记入审核记录'
                      : '必填，例如：已开考且已使用权益，不符合退款规则'" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="auditVisible = false">取消</el-button>
        <el-button :type="auditForm.approve ? 'primary' : 'danger'" :loading="auditing" @click="submitAudit">
          {{ auditForm.approve ? '确认退款' : '确认驳回' }}
        </el-button>
      </template>
    </el-dialog>
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

.audit-info {
  display: flex;
  flex-direction: column;
  gap: 2px;
  font-size: 12px;
  color: #64748b;
}

.audit-remark {
  color: #4b5563;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.pending {
  color: #e6a23c;
  font-size: 12px;
}

.muted {
  color: #b0b7c3;
  font-size: 12px;
}

.audit-tip {
  margin-bottom: 14px;
}

.audit-desc {
  margin-bottom: 16px;
}

.audit-form {
  margin-top: 4px;
}
</style>
