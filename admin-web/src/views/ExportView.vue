<script setup lang="ts">
import { onMounted, onUnmounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { exportApi, sessionApi, type ExportTask } from '@/api'

const loading = ref(false)
const list = ref<ExportTask[]>([])
const total = ref(0)
const query = reactive({ pageNo: 1, pageSize: 10 })
const sessions = ref<any[]>([])
let pollTimer: number | undefined

const form = reactive({
  type: 'ORDER',
  sessionId: undefined as number | undefined,
  status: ''
})

const typeOptions = [
  { label: '订单报表', value: 'ORDER' },
  { label: '成绩报表', value: 'RESULT' },
  { label: '报名报表', value: 'REGISTRATION' }
]

type TagType = 'primary' | 'success' | 'info' | 'warning' | 'danger'

const statusLabel: Record<string, { text: string; type: TagType }> = {
  PENDING: { text: '排队中', type: 'info' },
  RUNNING: { text: '生成中', type: 'warning' },
  SUCCESS: { text: '已完成', type: 'success' },
  FAILED: { text: '失败', type: 'danger' }
}

async function load() {
  loading.value = true
  try {
    const page = await exportApi.page(query)
    list.value = page.list
    total.value = page.total
  } finally {
    loading.value = false
  }
}

async function submit() {
  const payload: any = { type: form.type }
  if (form.status) payload.status = form.status
  if (form.type !== 'ORDER' && form.sessionId) payload.sessionId = form.sessionId

  const task = await exportApi.submit(payload)
  ElMessage.success(`导出任务已提交：${task.taskNo}，生成中请稍候`)
  await load()
  startPolling()
}

/** 有未完成任务时轮询状态 */
function startPolling() {
  stopPolling()
  pollTimer = window.setInterval(async () => {
    const running = list.value.some((t) => t.status === 'PENDING' || t.status === 'RUNNING')
    if (!running) {
      stopPolling()
      return
    }
    await load()
  }, 2000)
}

function stopPolling() {
  if (pollTimer) {
    window.clearInterval(pollTimer)
    pollTimer = undefined
  }
}

async function download(row: ExportTask) {
  if (row.status !== 'SUCCESS') {
    ElMessage.warning('任务尚未完成，暂不能下载')
    return
  }
  const blob = await exportApi.download(row.id)
  const url = window.URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = row.fileName || `${row.type.toLowerCase()}-${row.taskNo}.csv`
  document.body.appendChild(link)
  link.click()
  document.body.removeChild(link)
  window.URL.revokeObjectURL(url)
}

onMounted(async () => {
  const page = await sessionApi.page({ pageNo: 1, pageSize: 50 })
  sessions.value = page.list
  await load()
  startPolling()
})

onUnmounted(stopPolling)
</script>

<template>
  <div class="page-card">
    <h3 class="page-title">报表导出</h3>
    <p class="page-desc">
      导出走异步任务：提交后由后台生成文件，页面每 2 秒刷新一次状态，完成即可下载（CSV，Excel 可直接打开）。
    </p>

    <div class="toolbar">
      <el-select v-model="form.type" style="width: 160px">
        <el-option v-for="opt in typeOptions" :key="opt.value" :label="opt.label" :value="opt.value" />
      </el-select>
      <el-select v-if="form.type !== 'ORDER'" v-model="form.sessionId" placeholder="全部场次"
                 style="width: 260px" clearable>
        <el-option v-for="s in sessions" :key="s.id" :label="s.name" :value="s.id" />
      </el-select>
      <el-button type="primary" @click="submit">提交导出</el-button>
      <div class="spacer" />
      <el-button @click="load">刷新</el-button>
    </div>

    <el-table v-loading="loading" :data="list" border stripe>
      <el-table-column prop="taskNo" label="任务号" width="240" />
      <el-table-column label="报表类型" width="120">
        <template #default="{ row }">
          {{ typeOptions.find((o) => o.value === row.type)?.label || row.type }}
        </template>
      </el-table-column>
      <el-table-column label="状态" width="110">
        <template #default="{ row }">
          <el-tag :type="statusLabel[row.status]?.type || 'info'" effect="plain">
            {{ statusLabel[row.status]?.text || row.status }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="rowCount" label="记录数" width="100" />
      <el-table-column prop="fileName" label="文件名" min-width="200" show-overflow-tooltip />
      <el-table-column prop="errorMsg" label="错误信息" min-width="160" show-overflow-tooltip />
      <el-table-column prop="finishedAt" label="完成时间" width="180" />
      <el-table-column label="操作" width="110" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" :disabled="row.status !== 'SUCCESS'"
                     @click="download(row)">下载</el-button>
        </template>
      </el-table-column>
    </el-table>

    <div class="pager">
      <el-pagination v-model:current-page="query.pageNo" v-model:page-size="query.pageSize"
                     :total="total" layout="total, prev, pager, next" @current-change="load" />
    </div>
  </div>
</template>
