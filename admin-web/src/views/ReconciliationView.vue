<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { reconApi, type ReconAnomaly, type ReconReport } from '@/api'

const loading = ref(false)
const running = ref(false)
const report = ref<ReconReport | null>(null)
const filter = reactive({ handleStatus: 'PENDING' })
const dialog = reactive({
  visible: false,
  status: 'HANDLED',
  remark: '',
  row: undefined as ReconAnomaly | undefined
})

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

const anomalies = computed(() => {
  const all = report.value?.anomalies ?? []
  return filter.handleStatus ? all.filter((a) => a.handleStatus === filter.handleStatus) : all
})

const pendingCount = computed(() => (report.value?.anomalies ?? []).filter((a) => a.handleStatus === 'PENDING').length)

const checkedText = computed(() => {
  if (!report.value) return '-'
  return `订单 ${report.value.checkedOrders} / 支付 ${report.value.checkedPayments} / 权益账户 ${report.value.checkedAccounts}`
})

async function load() {
  loading.value = true
  try {
    report.value = await reconApi.latest()
  } finally {
    loading.value = false
  }
}

async function run() {
  await ElMessageBox.confirm(
    '对账会按当前数据全量比对订单、支付与权益流水，检出结果只做留痕、不会自动改数据。确认立即执行一次？',
    '立即对账',
    { type: 'warning', confirmButtonText: '开始对账', cancelButtonText: '取消' }
  )
  running.value = true
  try {
    const result = await reconApi.run()
    report.value = result
    if (result.status === 'PASSED') {
      ElMessage.success(`对账通过（${result.reportNo}），三项数据一致`)
    } else if (result.status === 'FAILED') {
      ElMessage.error(`对账执行失败：${result.errorMsg || '未知原因'}`)
    } else {
      ElMessage.warning(`检出 ${result.anomalyCount} 条不一致，请在下表逐条处置`)
    }
  } finally {
    running.value = false
  }
}

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
  dialog.visible = false
  ElMessage.success(dialog.status === 'HANDLED' ? '已标记为处理完成' : '已标记为确认误报')
  // 处置会改变「平台还剩多少笔没对上」这个数字，池子必须跟着刷新
  await loadPool()
}

// ---------------------------------------------------------------- 跨轮次待处置池

const pool = reactive({
  loading: false,
  list: [] as ReconAnomaly[],
  total: 0,
  pageNo: 1,
  pageSize: 10,
  handleStatus: 'PENDING',
  bizType: ''
})

async function loadPool() {
  pool.loading = true
  try {
    const params: any = { pageNo: pool.pageNo, pageSize: pool.pageSize }
    if (pool.handleStatus) params.handleStatus = pool.handleStatus
    if (pool.bizType) params.bizType = pool.bizType
    const page = await reconApi.anomalies(params)
    pool.list = page.list
    pool.total = page.total
  } finally {
    pool.loading = false
  }
}

function resetPool() {
  pool.pageNo = 1
  loadPool()
}

async function exportPool() {
  const params: any = {}
  if (pool.handleStatus) params.handleStatus = pool.handleStatus
  if (pool.bizType) params.bizType = pool.bizType
  const blob = await reconApi.exportAnomalies(params)
  const url = window.URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = `recon-anomalies-${new Date().toISOString().slice(0, 10)}.csv`
  document.body.appendChild(link)
  link.click()
  document.body.removeChild(link)
  window.URL.revokeObjectURL(url)
  ElMessage.success(`已导出 ${pool.handleStatus || '全部'} 异常清单`)
}

onMounted(() => {
  load()
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

    <div class="toolbar">
      <el-button type="primary" :loading="running" @click="run">立即对账</el-button>
      <el-button @click="load">刷新结果</el-button>
      <el-select v-model="filter.handleStatus" style="width: 150px">
        <el-option label="只看待处置" value="PENDING" />
        <el-option label="全部异常" value="" />
        <el-option label="已处理" value="HANDLED" />
        <el-option label="确认误报" value="IGNORED" />
      </el-select>
    </div>

    <el-descriptions v-if="report" :column="4" border class="summary">
      <el-descriptions-item label="报告号">{{ report.reportNo }}</el-descriptions-item>
      <el-descriptions-item label="触发方式">{{ report.scope === 'MANUAL' ? '人工触发' : '定时任务' }}</el-descriptions-item>
      <el-descriptions-item label="结论">
        <el-tag :type="report.status === 'PASSED' ? 'success' : report.status === 'FAILED' ? 'info' : 'danger'" effect="plain">
          {{ report.status === 'PASSED' ? '数据一致' : report.status === 'FAILED' ? '执行失败' : '存在不一致' }}
        </el-tag>
      </el-descriptions-item>
      <el-descriptions-item label="异常条数">
        <span :class="{ danger: report.anomalyCount > 0 }">{{ report.anomalyCount }}</span>
        <span v-if="pendingCount" class="pending">（待处置 {{ pendingCount }}）</span>
      </el-descriptions-item>
      <el-descriptions-item label="检查范围" :span="2">{{ checkedText }}</el-descriptions-item>
      <el-descriptions-item label="开始 / 结束">{{ report.startedAt }} → {{ report.finishedAt || '进行中' }}</el-descriptions-item>
      <el-descriptions-item label="失败原因">{{ report.errorMsg || '-' }}</el-descriptions-item>
    </el-descriptions>

    <el-empty v-else description="还没有对账记录，点击「立即对账」跑一次" />

    <el-table v-if="report && report.anomalies.length" v-loading="loading" :data="anomalies" border stripe>
      <el-table-column label="异常类型" width="180">
        <template #default="{ row }">
          <span>{{ typeLabel[row.anomalyType] || row.anomalyType }}</span>
        </template>
      </el-table-column>
      <el-table-column label="对象" width="200">
        <template #default="{ row }">
          <span class="mono">{{ row.bizType }} · {{ row.bizNo || '#' + row.bizId }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="expectedValue" label="应有" min-width="160" show-overflow-tooltip />
      <el-table-column prop="actualValue" label="实际" min-width="160" show-overflow-tooltip />
      <el-table-column prop="detail" label="说明" min-width="220" show-overflow-tooltip />
      <el-table-column label="处置" width="120">
        <template #default="{ row }">
          <el-tag :type="handleLabel[row.handleStatus]?.type || 'info'" effect="plain">
            {{ handleLabel[row.handleStatus]?.text || row.handleStatus }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="处置信息" min-width="200" show-overflow-tooltip>
        <template #default="{ row }">
          <span v-if="row.handleStatus === 'PENDING'" class="pending">尚未处置</span>
          <span v-else>
            {{ row.handleRemark || '（未填写原因）' }}
            <span class="meta">· {{ row.handledAt }} · 用户 #{{ row.handledBy ?? '-' }}</span>
          </span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="190" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" :disabled="row.handleStatus !== 'PENDING'"
                     @click="openHandle(row, 'HANDLED')">标记已处理</el-button>
          <el-button link type="warning" :disabled="row.handleStatus !== 'PENDING'"
                     @click="openHandle(row, 'IGNORED')">确认误报</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-divider content-position="left">跨轮次待处置池</el-divider>
    <p class="page-desc">
      上面的表格回答「最近一次对账查出什么」，这里回答「平台上现在还有多少笔没对上」。
      后者跨所有历史报告——上一轮检出、这一轮因数据变动没再检出的问题，只在这里看得见。
    </p>

    <div class="toolbar">
      <el-select v-model="pool.handleStatus" style="width: 150px" @change="resetPool">
        <el-option label="待处置" value="PENDING" />
        <el-option label="全部状态" value="" />
        <el-option label="已处理" value="HANDLED" />
        <el-option label="确认误报" value="IGNORED" />
      </el-select>
      <el-select v-model="pool.bizType" style="width: 150px" @change="resetPool">
        <el-option label="全部业务对象" value="" />
        <el-option label="订单" value="ORDER" />
        <el-option label="支付单" value="PAYMENT" />
        <el-option label="权益账户" value="ACCOUNT" />
      </el-select>
      <el-button @click="loadPool">刷新</el-button>
      <div class="spacer" />
      <el-button type="success" @click="exportPool">导出 CSV</el-button>
    </div>

    <el-table v-loading="pool.loading" :data="pool.list" border stripe>
      <el-table-column label="异常类型" width="180">
        <template #default="{ row }">
          <span>{{ typeLabel[row.anomalyType] || row.anomalyType }}</span>
        </template>
      </el-table-column>
      <el-table-column label="对象" width="200">
        <template #default="{ row }">
          <span class="mono">{{ row.bizType }} · {{ row.bizNo || '#' + row.bizId }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="expectedValue" label="应有" min-width="150" show-overflow-tooltip />
      <el-table-column prop="actualValue" label="实际" min-width="150" show-overflow-tooltip />
      <el-table-column label="处置" width="110">
        <template #default="{ row }">
          <el-tag :type="handleLabel[row.handleStatus]?.type || 'info'" effect="plain">
            {{ handleLabel[row.handleStatus]?.text || row.handleStatus }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="处置信息" min-width="180" show-overflow-tooltip>
        <template #default="{ row }">
          <span v-if="row.handleStatus === 'PENDING'" class="pending">尚未处置</span>
          <span v-else>{{ row.handleRemark || '（未填写原因）' }} · {{ row.handledAt }}</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="190" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" :disabled="row.handleStatus !== 'PENDING'"
                     @click="openHandle(row, 'HANDLED')">标记已处理</el-button>
          <el-button link type="warning" :disabled="row.handleStatus !== 'PENDING'"
                     @click="openHandle(row, 'IGNORED')">确认误报</el-button>
        </template>
      </el-table-column>
    </el-table>

    <div class="pager">
      <el-pagination v-model:current-page="pool.pageNo" v-model:page-size="pool.pageSize"
                     :total="pool.total" layout="total, prev, pager, next" @current-change="loadPool" />
    </div>

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
.summary {
  margin-bottom: 16px;
}

.danger {
  color: #dc2626;
  font-weight: 600;
}

.pending {
  color: #f59e0b;
}

.mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 12px;
}

.meta {
  color: #9ca3af;
  font-size: 12px;
}

.dialog-tip {
  margin-bottom: 14px;
}
</style>
