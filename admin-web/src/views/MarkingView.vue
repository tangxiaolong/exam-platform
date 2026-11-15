<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { markingApi, type MarkingItem } from '@/api'

const loading = ref(false)
const queue = ref<any[]>([])
const total = ref(0)
const query = reactive({ pageNo: 1, pageSize: 10, status: 'REVIEWING' })

const drawerVisible = ref(false)
const currentAttempt = ref<any>(null)
const items = ref<MarkingItem[]>([])
const scores = reactive<Record<number, number>>({})
const remarks = reactive<Record<number, string>>({})
const history = ref<any[]>([])

const statusOptions = [
  { label: '待阅卷', value: 'REVIEWING' },
  { label: '已判分', value: 'GRADED' },
  { label: '已交卷', value: 'SUBMITTED' }
]

async function load() {
  loading.value = true
  try {
    const page = await markingApi.queue(query)
    queue.value = page.list
    total.value = page.total
  } finally {
    loading.value = false
  }
}

async function open(row: any) {
  currentAttempt.value = row
  items.value = await markingApi.list(row.attemptId)
  items.value.forEach((item) => {
    scores[item.questionId] = item.score ?? 0
    remarks[item.questionId] = item.remark ?? ''
  })
  history.value = await markingApi.history(row.attemptId)
  drawerVisible.value = true
}

async function save(item: MarkingItem) {
  const updated = await markingApi.mark(currentAttempt.value.attemptId, item.questionId,
    scores[item.questionId] ?? 0, remarks[item.questionId])
  Object.assign(item, updated)
  ElMessage.success('已保存本题得分')
}

async function finish() {
  await ElMessageBox.confirm('结束阅卷后成绩将按场次规则发布，确认全部主观题已评分？', '结束阅卷', {
    type: 'warning'
  })
  const result = await markingApi.finish(currentAttempt.value.attemptId)
  ElMessage.success(`阅卷完成，总分 ${result.totalScore} / ${result.fullScore}`)
  drawerVisible.value = false
  load()
}

async function correct(item: MarkingItem) {
  const { value } = await ElMessageBox.prompt('请填写成绩更正原因（会留痕）', '成绩更正', {
    inputValue: '考生申诉，复核后调整'
  })
  if (!value) return
  const result = await markingApi.correct(currentAttempt.value.attemptId, item.questionId,
    scores[item.questionId] ?? 0, value)
  ElMessage.success(`已更正，总分 ${result.totalScore}`)
  items.value = await markingApi.list(currentAttempt.value.attemptId)
  history.value = await markingApi.history(currentAttempt.value.attemptId)
}

function joined(values?: string[]) {
  return (values || []).join('；') || '—'
}

onMounted(load)
</script>

<template>
  <div class="page-card">
    <h3 class="page-title">阅卷中心</h3>
    <p class="page-desc">
      含主观题的考试交卷后进入「待阅卷」，客观题已自动判分；全部主观题评分完成后成绩才会对考生可见。
    </p>

    <div class="toolbar">
      <el-select v-model="query.status" style="width: 150px" @change="load">
        <el-option v-for="opt in statusOptions" :key="opt.value" :label="opt.label" :value="opt.value" />
      </el-select>
      <el-button type="primary" @click="load">刷新</el-button>
    </div>

    <el-table v-loading="loading" :data="queue" border stripe>
      <el-table-column prop="attemptId" label="作答ID" width="100" />
      <el-table-column prop="sessionName" label="考试场次" min-width="200" />
      <el-table-column prop="status" label="状态" width="120">
        <template #default="{ row }">
          <el-tag :type="row.status === 'REVIEWING' ? 'warning' : 'success'" effect="plain">
            {{ row.status === 'REVIEWING' ? '待阅卷' : row.status === 'GRADED' ? '已判分' : row.status }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="submittedAt" label="交卷时间" width="180" />
      <el-table-column prop="totalScore" label="当前得分" width="110" />
      <el-table-column label="操作" width="120" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="open(row)">阅卷</el-button>
        </template>
      </el-table-column>
    </el-table>

    <div class="pager">
      <el-pagination v-model:current-page="query.pageNo" v-model:page-size="query.pageSize"
                     :total="total" layout="total, prev, pager, next" @current-change="load" />
    </div>

    <el-drawer v-model="drawerVisible" title="主观题阅卷" size="720px">
      <div v-if="!items.length" class="empty">本次作答没有主观题</div>
      <div v-for="(item, index) in items" :key="item.questionId" class="mark-card">
        <div class="mark-head">
          <span class="idx">第 {{ index + 1 }} 题</span>
          <el-tag :type="item.marked ? 'success' : 'info'" effect="plain" size="small">
            {{ item.marked ? '已评分' : '待评分' }}
          </el-tag>
          <span class="full">满分 {{ item.fullScore }}</span>
        </div>
        <div class="stem">{{ item.stem }}</div>
        <div class="row">
          <span class="label">参考答案</span>
          <span class="value">{{ joined(item.referenceAnswer) }}</span>
        </div>
        <div class="row">
          <span class="label">考生作答</span>
          <span class="value answer">{{ joined(item.givenAnswer) }}</span>
        </div>
        <div class="score-row">
          <el-input-number v-model="scores[item.questionId]" :min="0" :max="item.fullScore" />
          <el-input v-model="remarks[item.questionId]" placeholder="评语（可选）" style="width: 320px" />
          <el-button type="primary" @click="save(item)">保存</el-button>
          <el-button v-if="item.marked" link type="warning" @click="correct(item)">更正分数</el-button>
        </div>
      </div>

      <el-divider>成绩版本记录</el-divider>
      <el-table :data="history" size="small" border>
        <el-table-column prop="revision" label="版本" width="70" />
        <el-table-column prop="operation" label="操作" width="110" />
        <el-table-column prop="totalScore" label="总分" width="90" />
        <el-table-column prop="remark" label="备注" min-width="160" />
        <el-table-column prop="createdAt" label="时间" width="170" />
      </el-table>

      <template #footer>
        <el-button @click="drawerVisible = false">关闭</el-button>
        <el-button type="primary" @click="finish">结束阅卷并发布</el-button>
      </template>
    </el-drawer>
  </div>
</template>

<style scoped>
.mark-card {
  border: 1px solid #eef1f6;
  border-radius: 10px;
  padding: 14px 16px;
  margin-bottom: 12px;
}

.mark-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 8px;
}

.idx {
  font-weight: 600;
  color: #111827;
}

.full {
  color: #6b7280;
  font-size: 13px;
  margin-left: auto;
}

.stem {
  color: #111827;
  margin-bottom: 10px;
  line-height: 1.6;
}

.row {
  display: flex;
  gap: 10px;
  margin-bottom: 6px;
  font-size: 13px;
}

.label {
  color: #6b7280;
  min-width: 64px;
}

.value {
  color: #374151;
  flex: 1;
}

.answer {
  background: #f8fafc;
  border-radius: 6px;
  padding: 4px 8px;
}

.score-row {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 10px;
}

.empty {
  color: #6b7280;
  padding: 20px 0;
}
</style>
