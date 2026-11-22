<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { reconApi, type ReconAnomaly, type ReconReport } from '@/api'

/**
 * 对账中心。
 *
 * <p>原页面有上下两张几乎一样的异常表：上半「最近一次报告」，下半「跨轮次待处置池」。
 * 两张表列相同、操作相同，运营每次都要先判断「我该看哪一张」——这本身就是设计缺陷。
 * 现在合并成一张表，用「数据范围」切换：默认跨轮次待处置（这才是每天要清零的池子），
 * 需要复盘某一轮时再切到最近报告。</p>
 */
const loading = ref(false)
const running = ref(false)
const report = ref<ReconReport | null>(null)

type Scope = 'POOL' | 'LATEST'
const scope = ref<Scope>('POOL')

const typeLabel: Record<string, string> = {
  ORDER_PAID_WITHOUT_ENTITLEMENT: '已支付无权益',
  ORDER_COMPLETED_WITHOUT_ENTITLEMENT: '已完成无权益',
  PAYMENT_PAID_ORDER_NOT_PAID: '支付成功订单未推进',
  PAYMENT_AMOUNT_MISMATCH: '金额不一致',
  ACCOUNT_LEDGER_DRIFT: '账户与流水不符'
}

type TagType = 'primary' | 'success' | 'info' | 'warning' | 'danger'

const handleLabel: Record<string, { text: string; type: TagType }> = {
  PENDING: { text: '待处置', type: 'danger' },
  HANDLED: { text: '已处理', type: 'success' },
  IGNORED: { text: '确认误报', type: 'info' }
}

const bizLabel: Record<string, string> = {
  ORDER: '订单',
  PAYMENT: '支付单',
  ACCOUNT: '权益账户'
}

/**
 * 异常 → 业务页面定位。
 *
 * <p>定位键统一是订单号：订单类异常的 bizNo 本身就是订单号，支付金额不一致的 bizNo
 * 也由后端刻意落成关联订单号（支付单号放在 detail 里）。</p>
 *
 * <p>历史数据里存在 bizNo 仍为支付单号（PY 结尾）的旧异常，直接跳过去只会得到一张空表。
 * 这里按单号后缀判断，只有订单号（…OD）才给链接——宁可不可点，也不做假跳转。</p>
 */
const router = useRouter()

function canLocate(row: ReconAnomaly) {
  if (row.bizType === 'ACCOUNT' || !row.bizNo) return false
  return row.bizNo.endsWith('OD')
}

function locate(row: ReconAnomaly) {
  if (!canLocate(row)) return
  router.push({ path: '/orders', query: { keyword: row.bizNo as string } })
}

// ---------------------------------------------------------------- 数据源

const filter = reactive({ handleStatus: 'PENDING', bizType: '' })
const pool = reactive({
  list: [] as ReconAnomaly[],
  total: 0,
  pageNo: 1,
  pageSize: 10
})

const latestAnomalies = computed(() => {
  let rows = report.value?.anomalies ?? []
  if (filter.handleStatus) rows = rows.filter((a) => a.handleStatus === filter.handleStatus)
  if (filter.bizType) rows = rows.filter((a) => a.bizType === filter.bizType)
  return rows
})

const latestPending = computed(
  () => (report.value?.anomalies ?? []).filter((a) => a.handleStatus === 'PENDING').length
)

const latestHandled = computed(
  () => (report.value?.anomalies ?? []).filter((a) => a.handleStatus !== 'PENDING').length
)

const checkedText = computed(() => {
  if (!report.value) return '-'
  return `订单 ${report.value.checkedOrders} / 支付 ${report.value.checkedPayments} / 权益账户 ${report.value.checkedAccounts}`
})

const reportVerdict = computed(() => {
  if (!report.value) return { text: '尚未对账', type: 'info' as TagType }
  if (report.value.status === 'PASSED') return { text: '数据一致', type: 'success' as TagType }
  if (report.value.status === 'FAILED') return { text: '执行失败', type: 'danger' as TagType }
  return { text: '存在不一致', type: 'warning' as TagType }
})

async function loadReport() {
  loading.value = true
  try {
    report.value = await reconApi.latest()
  } finally {
    loading.value = false
  }
}

async function loadPool() {
  loading.value = true
  try {
    const params: Record<string, any> = { pageNo: pool.pageNo, pageSize: pool.pageSize }
    if (filter.handleStatus) params.handleStatus = filter.handleStatus
    if (filter.bizType) params.bizType = filter.bizType
    const page = await reconApi.anomalies(params)
    pool.list = page.list
    pool.total = page.total
  } finally {
    loading.value = false
  }
}

function reload() {
  if (scope.value === 'POOL') loadPool()
  else loadReport()
}

function switchScope(next: Scope) {
  if (scope.value === next) return
  scope.value = next
  reload()
}

function onFilterChange() {
  pool.pageNo = 1
  reload()
}

async function run() {
  try {
    await ElMessageBox.confirm(
      '对账会按当前数据全量比对订单、支付与权益流水，检出结果只做留痕、不会自动改数据。确认立即执行一次？',
      '立即对账',
      { type: 'warning', confirmButtonText: '开始对账', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  running.value = true
  try {
    const result = await reconApi.run()
    report.value = result
    // 跑完自动切到本轮结果：运营点「立即对账」就是想看这次查出什么
    scope.value = 'LATEST'
    pool.pageNo = 1
    if (result.status === 'PASSED') {
      ElMessage.success(`对账通过（${result.reportNo}），三项数据一致`)
    } else if (result.status === 'FAILED') {
      ElMessage.error(`对账执行失败：${result.errorMsg || '未知原因'}`)
    } else {
      ElMessage.warning(`检出 ${result.anomalyCount} 条不一致，请在下表逐条处置`)
    }
    await loadPool()
  } finally {
    running.value = false
  }
}

// ---------------------------------------------------------------- 处置

const dialog = reactive({
  visible: false,
  status: 'HANDLED',
  remark: '',
  row: undefined as ReconAnomaly | undefined
})

function openHandle(row: ReconAnomaly, status: string) {
  if (row.handleStatus !== 'PENDING') {
    ElMessage.warning('该异常已处置，同一轮对账内不可重复修改')
    return
  }
  dialog.row = row
  dialog.status = status
  dialog.remark = ''
  dialog.visible = true
}

async function submitHandle() {
  if (!dialog.row) return
  if (!dialog.remark.trim()) {
    ElMessage.warning('请填写处置说明：写清修复方式或误报原因，下次对账复盘时要用到')
    return
  }
  const target = dialog.row
  const saved = await reconApi.handle(target.id, dialog.status, dialog.remark.trim())
  // 就地更新而不是整页重拉：重拉会丢掉运营正在看的筛选与滚动位置
  if (report.value) {
    const index = report.value.anomalies.findIndex((a) => a.id === target.id)
    if (index >= 0) report.value.anomalies[index] = saved
  }
  const index = pool.list.findIndex((a) => a.id === target.id)
  if (index >= 0) pool.list[index] = saved
  dialog.visible = false
  ElMessage.success(dialog.status === 'HANDLED' ? '已标记为处理完成' : '已标记为确认误报')
  // 处置会改变「平台还剩多少笔没对上」这个数字，池子必须跟着刷新
  await loadPool()
}

async function exportCsv() {
  const params: Record<string, any> = {}
  if (filter.handleStatus) params.handleStatus = filter.handleStatus
  if (filter.bizType) params.bizType = filter.bizType
  const blob = await reconApi.exportAnomalies(params)
  const url = window.URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = `recon-anomalies-${new Date().toISOString().slice(0, 10)}.csv`
  document.body.appendChild(link)
  link.click()
  document.body.removeChild(link)
  window.URL.revokeObjectURL(url)
  ElMessage.success('已导出异常清单，Excel 可直接打开')
}

onMounted(() => {
  loadReport()
  loadPool()
})
</script>

<template>
  <div class="page-card">
    <h3 class="page-title">对账中心</h3>
    <p class="page-desc">
      支付链路是多步写（回调 → 落流水 → 置已支付 → 发权益），任何一步失败都会留下「钱到了、权益没到」的静默不一致。
      对账把这类问题每天主动检出，<b>只留痕、不自动修数据</b>，处置动作由运营确认后手工执行。
    </p>

    <div class="stat-strip">
      <div class="stat-item" :class="latestPending > 0 ? 'is-danger' : 'is-ok'">
        <div class="stat-value">{{ latestPending }}</div>
        <div class="stat-label">本轮待处置</div>
      </div>
      <div class="stat-item">
        <div class="stat-value">{{ latestHandled }}</div>
        <div class="stat-label">本轮已处置</div>
      </div>
      <div class="stat-item">
        <div class="stat-value">{{ pool.total }}</div>
        <div class="stat-label">
          跨轮次{{ filter.handleStatus === 'PENDING' ? '待处置' : '异常' }}总数
        </div>
      </div>
      <div class="stat-item">
        <div class="stat-value">{{ report?.anomalyCount ?? '—' }}</div>
        <div class="stat-label">最近一轮检出条数</div>
      </div>
    </div>

    <div class="run-bar">
      <el-button type="primary" :loading="running" @click="run">立即对账</el-button>
      <el-button @click="() => { loadReport(); loadPool() }">刷新</el-button>
      <div class="run-meta">
        <span class="run-label">最近一次</span>
        <template v-if="report">
          <el-tag :type="reportVerdict.type" effect="plain" size="small">{{ reportVerdict.text }}</el-tag>
          <span class="run-text">{{ report.reportNo }} · {{ report.scope === 'MANUAL' ? '人工触发' : '定时任务' }}</span>
          <span class="run-text muted">{{ report.startedAt?.replace('T', ' ') }} → {{ report.finishedAt?.replace('T', ' ') || '进行中' }}</span>
        </template>
        <span v-else class="run-text muted">还没有对账记录，点击「立即对账」跑一次</span>
      </div>
    </div>

    <el-descriptions v-if="report" :column="4" border size="small" class="summary">
      <el-descriptions-item label="检查范围" :span="2">{{ checkedText }}</el-descriptions-item>
      <el-descriptions-item label="异常条数">
        <span :class="{ danger: report.anomalyCount > 0 }">{{ report.anomalyCount }}</span>
      </el-descriptions-item>
      <el-descriptions-item label="失败原因">{{ report.errorMsg || '-' }}</el-descriptions-item>
    </el-descriptions>

    <div class="scope-bar">
      <div class="scope-switch">
        <button type="button" class="scope-item" :class="{ active: scope === 'POOL' }"
                @click="switchScope('POOL')">
          跨轮次待处置池
          <span class="scope-hint">平台上现在还有多少笔没对上</span>
        </button>
        <button type="button" class="scope-item" :class="{ active: scope === 'LATEST' }"
                @click="switchScope('LATEST')">
          最近一次对账结果
          <span class="scope-hint">这一轮查出了什么</span>
        </button>
      </div>

      <div class="scope-filters">
        <el-select v-model="filter.handleStatus" style="width: 140px" @change="onFilterChange">
          <el-option label="只看待处置" value="PENDING" />
          <el-option label="全部状态" value="" />
          <el-option label="已处理" value="HANDLED" />
          <el-option label="确认误报" value="IGNORED" />
        </el-select>
        <el-select v-model="filter.bizType" style="width: 140px" clearable placeholder="全部对象"
                   @change="onFilterChange">
          <el-option label="订单" value="ORDER" />
          <el-option label="支付单" value="PAYMENT" />
          <el-option label="权益账户" value="ACCOUNT" />
        </el-select>
        <el-button type="success" plain @click="exportCsv">导出 CSV</el-button>
      </div>
    </div>

    <p v-if="scope === 'POOL'" class="scope-note">
      跨所有历史报告：上一轮检出、这一轮因数据变动没再检出的问题，只在这里看得见。
    </p>
    <p v-else class="scope-note">
      只含最近一次对账检出的异常；处置按报告轮次闭环，下一轮仍检出会生成新的待处置记录。
    </p>

    <!-- 跨轮次池：服务端分页 -->
    <template v-if="scope === 'POOL'">
      <el-table v-loading="loading" :data="pool.list" border stripe>
        <el-table-column label="异常类型" width="170">
          <template #default="{ row }">
            <span>{{ typeLabel[row.anomalyType] || row.anomalyType }}</span>
          </template>
        </el-table-column>
        <el-table-column label="对象" width="190">
          <template #default="{ row }">
            <div class="biz">
              <el-tag effect="plain" size="small">{{ bizLabel[row.bizType] || row.bizType }}</el-tag>
              <span v-if="canLocate(row)" class="mono link" :title="'在订单管理中定位 ' + row.bizNo"
                    @click="locate(row)">{{ row.bizNo }}</span>
              <span v-else class="mono">{{ row.bizNo || '#' + row.bizId }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="expectedValue" label="应有" min-width="150" show-overflow-tooltip />
        <el-table-column prop="actualValue" label="实际" min-width="150" show-overflow-tooltip />
        <el-table-column label="处置" width="110">
          <template #default="{ row }">
            <el-tag :type="handleLabel[row.handleStatus]?.type || 'info'" effect="plain" size="small">
              {{ handleLabel[row.handleStatus]?.text || row.handleStatus }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="处置信息" min-width="190" show-overflow-tooltip>
          <template #default="{ row }">
            <span v-if="row.handleStatus === 'PENDING'" class="pending">尚未处置</span>
            <span v-else>
              {{ row.handleRemark || '（未填写原因）' }}
              <span class="meta">· {{ row.handledAt?.replace('T', ' ') }}</span>
            </span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="180" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" :disabled="row.handleStatus !== 'PENDING'"
                       @click="openHandle(row, 'HANDLED')">标记已处理</el-button>
            <el-button link type="warning" :disabled="row.handleStatus !== 'PENDING'"
                       @click="openHandle(row, 'IGNORED')">确认误报</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <div class="empty-wrap">
            <el-empty :description="filter.handleStatus === 'PENDING'
              ? '待处置池已清空——当前没有对不上的账'
              : '没有符合条件的异常记录'" :image-size="72" />
          </div>
        </template>
      </el-table>

      <div class="pager">
        <el-pagination v-model:current-page="pool.pageNo" v-model:page-size="pool.pageSize"
                       :total="pool.total" :page-sizes="[10, 20, 50]"
                       layout="total, sizes, prev, pager, next" @current-change="loadPool"
                       @size-change="loadPool" />
      </div>
    </template>

    <!-- 最近一轮：后端一次性返回全量，前端筛选 -->
    <template v-else>
      <el-table v-loading="loading" :data="latestAnomalies" border stripe>
        <el-table-column label="异常类型" width="170">
          <template #default="{ row }">
            <span>{{ typeLabel[row.anomalyType] || row.anomalyType }}</span>
          </template>
        </el-table-column>
        <el-table-column label="对象" width="190">
          <template #default="{ row }">
            <div class="biz">
              <el-tag effect="plain" size="small">{{ bizLabel[row.bizType] || row.bizType }}</el-tag>
              <span class="mono">{{ row.bizNo || '#' + row.bizId }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="expectedValue" label="应有" min-width="150" show-overflow-tooltip />
        <el-table-column prop="actualValue" label="实际" min-width="150" show-overflow-tooltip />
        <el-table-column prop="detail" label="说明" min-width="200" show-overflow-tooltip />
        <el-table-column label="处置" width="110">
          <template #default="{ row }">
            <el-tag :type="handleLabel[row.handleStatus]?.type || 'info'" effect="plain" size="small">
              {{ handleLabel[row.handleStatus]?.text || row.handleStatus }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="处置信息" min-width="190" show-overflow-tooltip>
          <template #default="{ row }">
            <span v-if="row.handleStatus === 'PENDING'" class="pending">尚未处置</span>
            <span v-else>
              {{ row.handleRemark || '（未填写原因）' }}
              <span class="meta">· {{ row.handledAt?.replace('T', ' ') }} · 用户 #{{ row.handledBy ?? '-' }}</span>
            </span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="180" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" :disabled="row.handleStatus !== 'PENDING'"
                       @click="openHandle(row, 'HANDLED')">标记已处理</el-button>
            <el-button link type="warning" :disabled="row.handleStatus !== 'PENDING'"
                       @click="openHandle(row, 'IGNORED')">确认误报</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <div class="empty-wrap">
            <el-empty :description="report ? '本轮没有符合条件的异常' : '还没有对账记录'"
                      :image-size="72" />
          </div>
        </template>
      </el-table>
    </template>

    <el-dialog v-model="dialog.visible" :title="dialog.status === 'HANDLED' ? '标记已处理' : '确认误报'" width="520px">
      <el-alert
        :title="dialog.status === 'HANDLED'
          ? '请先在业务侧完成修复（补发权益、推进订单状态等），再回到这里标记。'
          : '确认账实相符属于对账规则误判时选择此项，误报率会用于后续规则优化。'"
        :type="dialog.status === 'HANDLED' ? 'warning' : 'info'"
        :closable="false"
        class="dialog-tip"
      />
      <el-form label-width="80px">
        <el-form-item label="处置说明" required>
          <el-input v-model="dialog.remark" type="textarea" :rows="3" maxlength="200" show-word-limit
                    placeholder="写清修复方式或误报原因，例如：已手工补发权益，GRANT 流水 #12345" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog.visible = false">取消</el-button>
        <el-button type="primary" @click="submitHandle">确认提交</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.run-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  padding: 10px 14px;
  margin-bottom: 14px;
  background: #f7f9fc;
  border: 1px solid #e8eef7;
  border-radius: 8px;
}

.run-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  margin-left: auto;
}

.run-label {
  font-size: 12px;
  color: #94a3b8;
}

.run-text {
  font-size: 12px;
  color: #475569;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
}

.summary {
  margin-bottom: 14px;
}

.scope-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
  margin-bottom: 8px;
}

.scope-switch {
  display: flex;
  gap: 4px;
  padding: 3px;
  background: #f1f5f9;
  border-radius: 10px;
}

.scope-item {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 1px;
  padding: 6px 14px;
  border: none;
  border-radius: 8px;
  background: transparent;
  color: #64748b;
  font-size: 13px;
  font-family: inherit;
  cursor: pointer;
  transition: background 0.15s, color 0.15s;
}

.scope-item.active {
  background: #fff;
  color: #1d4ed8;
  font-weight: 600;
  box-shadow: 0 1px 3px rgba(15, 23, 42, 0.08);
}

.scope-hint {
  font-size: 11px;
  font-weight: 400;
  color: #94a3b8;
}

.scope-filters {
  display: flex;
  gap: 10px;
  align-items: center;
  flex-wrap: wrap;
}

.scope-note {
  margin: 0 0 12px;
  font-size: 12px;
  color: #94a3b8;
}

.danger {
  color: #dc2626;
  font-weight: 600;
}

.pending {
  color: #f59e0b;
}

.muted {
  color: #b0b7c3;
}

.biz {
  display: flex;
  align-items: center;
  gap: 6px;
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

.meta {
  color: #9ca3af;
  font-size: 12px;
}

.dialog-tip {
  margin-bottom: 14px;
}
</style>
