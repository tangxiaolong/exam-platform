<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { messageApi, type MsgTask } from '@/api'

const loading = ref(false)
const list = ref<MsgTask[]>([])
const total = ref(0)
const query = reactive({ pageNo: 1, pageSize: 10, status: '', scene: '' })

const drawerVisible = ref(false)
const currentTask = ref<MsgTask | null>(null)
const logs = ref<any[]>([])

type TagType = 'primary' | 'success' | 'info' | 'warning' | 'danger'

const statusLabel: Record<string, { text: string; type: TagType }> = {
  PENDING: { text: '待发送', type: 'warning' },
  SENT: { text: '已发送', type: 'success' },
  FAILED: { text: '发送失败', type: 'danger' },
  SKIPPED: { text: '用户未授权', type: 'info' }
}

const sceneLabel: Record<string, string> = {
  EXAM_REGISTER: '报名成功',
  PAYMENT_SUCCESS: '支付成功',
  RESULT_PUBLISHED: '成绩发布'
}

async function load() {
  loading.value = true
  try {
    const page = await messageApi.page(query)
    list.value = page.list
    total.value = page.total
  } finally {
    loading.value = false
  }
}

async function openLogs(row: MsgTask) {
  currentTask.value = row
  logs.value = await messageApi.logs(row.id)
  drawerVisible.value = true
}

async function resend(row: MsgTask) {
  await messageApi.resend(row.id)
  ElMessage.success('已重新投递')
  load()
  if (currentTask.value?.id === row.id) {
    logs.value = await messageApi.logs(row.id)
    currentTask.value = list.value.find((t) => t.id === row.id) || currentTask.value
  }
}

function pretty(json?: string) {
  if (!json) return '—'
  try {
    return JSON.stringify(JSON.parse(json), null, 2)
  } catch {
    return json
  }
}

onMounted(load)
</script>

<template>
  <div class="page-card">
    <h3 class="page-title">消息中心</h3>
    <p class="page-desc">
      业务事件先落任务表再投递：消息送达不作为业务成功依据。未授权订阅的任务会被跳过，补授权后可手动重发。
    </p>

    <div class="toolbar">
      <el-select v-model="query.scene" placeholder="业务场景" style="width: 160px" clearable
                 @change="load">
        <el-option v-for="(label, key) in sceneLabel" :key="key" :label="label" :value="key" />
      </el-select>
      <el-select v-model="query.status" placeholder="发送状态" style="width: 160px" clearable
                 @change="load">
        <el-option v-for="(item, key) in statusLabel" :key="key" :label="item.text" :value="key" />
      </el-select>
      <el-button type="primary" @click="load">查询</el-button>
    </div>

    <el-table v-loading="loading" :data="list" border stripe>
      <el-table-column prop="id" label="ID" width="80" />
      <el-table-column label="业务场景" width="130">
        <template #default="{ row }">{{ sceneLabel[row.scene] || row.scene }}</template>
      </el-table-column>
      <el-table-column prop="templateCode" label="模板" width="200" />
      <el-table-column prop="userId" label="接收人" width="100" />
      <el-table-column prop="bizKey" label="业务键" width="160" />
      <el-table-column label="状态" width="120">
        <template #default="{ row }">
          <el-tag :type="statusLabel[row.status]?.type || 'info'" effect="plain">
            {{ statusLabel[row.status]?.text || row.status }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="retryCount" label="重试" width="80" />
      <el-table-column prop="sentAt" label="发送时间" width="180" />
      <el-table-column label="操作" width="150" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openLogs(row)">发送日志</el-button>
          <el-button v-if="row.status !== 'SENT'" link type="warning" @click="resend(row)">重发</el-button>
        </template>
      </el-table-column>
    </el-table>

    <div class="pager">
      <el-pagination v-model:current-page="query.pageNo" v-model:page-size="query.pageSize"
                     :total="total" layout="total, prev, pager, next" @current-change="load" />
    </div>

    <el-drawer v-model="drawerVisible" title="发送日志" size="620px">
      <div v-if="currentTask" class="summary">
        <div class="row"><span class="label">模板</span><span>{{ currentTask.templateCode }}</span></div>
        <div class="row"><span class="label">状态</span><span>{{ statusLabel[currentTask.status]?.text }}</span></div>
        <div class="row" v-if="currentTask.errorMsg">
          <span class="label">错误</span><span class="error">{{ currentTask.errorMsg }}</span>
        </div>
        <div class="row">
          <span class="label">参数</span>
          <pre class="payload">{{ pretty(currentTask.payloadJson) }}</pre>
        </div>
      </div>

      <el-divider>投递记录（{{ logs.length }} 次）</el-divider>
      <div v-if="!logs.length" class="empty">尚未尝试投递</div>
      <div v-for="log in logs" :key="log.id" class="log-card">
        <div class="row">
          <el-tag :type="log.success ? 'success' : 'danger'" effect="plain" size="small">
            {{ log.success ? '成功' : '失败' }}
          </el-tag>
          <span class="muted">{{ log.channel }} · {{ log.createdAt }}</span>
        </div>
        <div class="row" v-if="log.errorMsg"><span class="label">错误</span><span class="error">{{ log.errorMsg }}</span></div>
        <pre class="payload">{{ pretty(log.responseJson) }}</pre>
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
.summary {
  background: #f8fafc;
  border-radius: 10px;
  padding: 14px 16px;
}

.row {
  display: flex;
  gap: 10px;
  align-items: center;
  margin-bottom: 6px;
  font-size: 13px;
}

.label {
  color: #6b7280;
  min-width: 48px;
}

.error {
  color: #dc2626;
}

.muted {
  color: #9ca3af;
  font-size: 12px;
}

.payload {
  margin: 6px 0 0;
  padding: 8px 10px;
  background: #fff;
  border: 1px solid #eef1f6;
  border-radius: 8px;
  font-size: 12px;
  color: #374151;
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 180px;
  overflow: auto;
}

.log-card {
  border: 1px solid #eef1f6;
  border-radius: 10px;
  padding: 12px 14px;
  margin-bottom: 10px;
}

.empty {
  color: #9ca3af;
  padding: 12px 0;
}
</style>
