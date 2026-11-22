<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { markingApi, sessionApi } from '@/api'

const loading = ref(false)
const queue = ref<any[]>([])
const total = ref(0)
const sessions = ref<any[]>([])
const query = reactive({ pageNo: 1, pageSize: 10, status: 'REVIEWING', sessionId: undefined as number | undefined })

const markVisible = ref(false)
const currentAttempt = ref<number | null>(null)
const items = ref<any[]>([])
const scores = reactive<Record<number, number | undefined>>({})
const remarks = reactive<Record<number, string>>({})

async function load() {
  loading.value = true
  try {
    const params: Record<string, any> = { pageNo: query.pageNo, pageSize: query.pageSize, status: query.status }
    // 只在选了场次时才带参数，避免把 undefined 发到后端
    if (query.sessionId) {
      params.sessionId = query.sessionId
    }
    const page = await markingApi.queue(params)
    queue.value = page.list
    total.value = page.total
  } finally {
    loading.value = false
  }
}

async function openMark(attemptId: number) {
  currentAttempt.value = attemptId
  items.value = await markingApi.list(attemptId)
  for (const item of items.value) {
    scores[item.questionId] = item.score ?? undefined
    remarks[item.questionId] = item.remark || ''
  }
  markVisible.value = true
}

async function saveScore(item: any) {
  const value = scores[item.questionId]
  if (value === undefined || value === null) {
    return ElMessage.warning('请先输入得分')
  }
  await markingApi.mark(currentAttempt.value!, item.questionId, value, remarks[item.questionId])
  ElMessage.success('已保存该题得分')
  items.value = await markingApi.list(currentAttempt.value!)
}

const detailVisible = ref(false)
const detailResult = ref<any>(null)

async function openDetail(attemptId: number) {
  currentAttempt.value = attemptId
  detailResult.value = await markingApi.detail(attemptId)
  detailVisible.value = true
}

function typeLabel(type: string) {
  const map: Record<string, string> = { SINGLE: '单选', MULTIPLE: '多选', JUDGE: '判断', ESSAY: '主观' }
  return map[type] || type
}

const correctVisible = ref(false)
const correctItems = ref<any[]>([])
const correctQuestionId = ref<number | undefined>()
const correctScore = ref<number | undefined>()
const correctReason = ref('')
const correcting = ref(false)

async function openCorrect(row: any) {
  currentAttempt.value = row.attemptId
  correctItems.value = await markingApi.list(row.attemptId)
  correctQuestionId.value = correctItems.value[0]?.questionId
  correctScore.value = undefined
  correctReason.value = ''
  correctVisible.value = true
}

async function doCorrect() {
  if (correctQuestionId.value === undefined) {
    return ElMessage.warning('请选择题目')
  }
  if (correctScore.value === undefined || correctScore.value === null) {
    return ElMessage.warning('请输入新分数')
  }
  if (!correctReason.value.trim()) {
    return ElMessage.warning('更正原因必填——它会被写进审计日志')
  }
  correcting.value = true
  try {
    const result = await markingApi.correct(currentAttempt.value!, correctQuestionId.value,
      correctScore.value, correctReason.value.trim())
    ElMessage.success(`已更正，当前总分 ${result.totalScore}`)
    correctVisible.value = false
    await load()
  } finally {
    correcting.value = false
  }
}

async function finish() {
  await ElMessageBox.confirm('完成阅卷后主观题分数将汇入总分并生成正式成绩，不可再在此处修改，确认完成？',
    '完成阅卷', { type: 'warning' })
  const result = await markingApi.finish(currentAttempt.value!)
  ElMessage.success(`已完成阅卷，总分 ${result.totalScore}`)
  markVisible.value = false
  await load()
}

onMounted(async () => {
  // 场次列表用于筛选；一次拉 100 条足够（超过时下拉按需可改分页）
  const page = await sessionApi.page({ pageNo: 1, pageSize: 100 })
  sessions.value = page.list
  await load()
})
</script>

<template>
  <div class="page-card">
    <h3 class="page-title">阅卷工作台</h3>
    <p class="page-desc">
      待阅卷答卷按提交先后排队；逐题打分可反复调整，最后「完成阅卷」才把主观题分汇入总分。
    </p>

    <div class="toolbar">
      <el-select v-model="query.status" style="width: 160px" @change="load">
        <el-option label="待阅卷" value="REVIEWING" />
        <el-option label="已阅卷" value="GRADED" />
      </el-select>
      <el-select v-model="query.sessionId" placeholder="全部场次" style="width: 220px" clearable
                 @change="load">
        <el-option v-for="s in sessions" :key="s.id" :label="s.name" :value="s.id" />
      </el-select>
      <el-button type="primary" @click="load">刷新</el-button>
    </div>

    <el-table v-loading="loading" :data="queue" border stripe>
      <el-table-column prop="attemptId" label="答卷ID" width="90" />
      <el-table-column prop="sessionName" label="场次" min-width="150" show-overflow-tooltip />
      <el-table-column prop="status" label="状态" width="120" />
      <el-table-column prop="submittedAt" label="交卷时间" width="190" />
      <el-table-column prop="totalScore" label="当前得分" width="100" />
      <el-table-column label="操作" width="260" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openMark(row.attemptId)">阅卷</el-button>
          <el-button link type="primary" @click="openDetail(row.attemptId)">明细</el-button>
          <el-button link type="warning" @click="openCorrect(row)">更正</el-button>
        </template>
      </el-table-column>
    </el-table>

    <div class="pager">
      <el-pagination v-model:current-page="query.pageNo" v-model:page-size="query.pageSize"
                     :total="total" layout="total, prev, pager, next" @current-change="load" />
    </div>

    <el-drawer v-model="markVisible" :title="`阅卷 · 答卷 #${currentAttempt || ''}`" size="760px">
      <el-table :data="items" border size="small">
        <el-table-column label="题目" min-width="300">
          <template #default="{ row }">
            <div class="stem">{{ row.stem }}</div>
            <div class="muted-line">
              参考答案：{{ (row.referenceAnswer || []).join('、') || '—' }}
            </div>
            <div class="muted-line">
              考生答案：{{ (row.givenAnswer || []).join('、') || '未作答' }}
            </div>
          </template>
        </el-table-column>
        <el-table-column label="满分" width="70">
          <template #default="{ row }">{{ row.fullScore }}</template>
        </el-table-column>
        <el-table-column label="得分" width="130">
          <template #default="{ row }">
            <el-input-number v-model="scores[row.questionId as number]" :min="0"
                             :max="row.fullScore || 100" size="small" style="width: 110px" />
          </template>
        </el-table-column>
        <el-table-column label="评语" width="160">
          <template #default="{ row }">
            <el-input v-model="remarks[row.questionId as number]" size="small" placeholder="选填" />
          </template>
        </el-table-column>
        <el-table-column label="操作" width="90">
          <template #default="{ row }">
            <el-button link type="primary" @click="saveScore(row)">
              {{ row.marked ? '已保存' : '保存' }}
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <template #footer>
        <el-button @click="markVisible = false">关闭</el-button>
        <el-button type="primary" @click="finish">完成阅卷</el-button>
      </template>
    </el-drawer>

    <el-dialog v-model="correctVisible" :title="`成绩更正 · 答卷 #${currentAttempt || ''}`" width="520px">
      <el-form label-width="90px">
        <el-form-item label="题目" required>
          <el-select v-model="correctQuestionId" style="width: 100%">
            <el-option v-for="item in correctItems" :key="item.questionId"
                       :label="`#${item.questionId} ${item.stem}`" :value="item.questionId" />
          </el-select>
        </el-form-item>
        <el-form-item label="当前得分">
          <span style="color: #666;">
            {{ (correctItems.find((i: any) => i.questionId === correctQuestionId) || null)?.score ?? '—' }}
            /
            {{ (correctItems.find((i: any) => i.questionId === correctQuestionId) || null)?.fullScore ?? '—' }}
          </span>
        </el-form-item>
        <el-form-item label="更正为" required>
          <el-input-number v-model="correctScore" :min="0" :max="100" />
        </el-form-item>
        <el-form-item label="更正原因" required>
          <el-input v-model="correctReason" type="textarea" :rows="2"
                    placeholder="填写更正依据，会写入审计日志" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="correctVisible = false">取消</el-button>
        <el-button type="primary" :loading="correcting" @click="doCorrect">提交更正</el-button>
      </template>
    </el-dialog>

    <el-drawer v-model="detailVisible" :title="`答卷明细 · #${currentAttempt || ''}`" size="720px">
      <div v-if="detailResult" class="detail-head">
        <span>总分 <b>{{ detailResult.totalScore }}</b> / {{ detailResult.fullScore }}</span>
        <span>答对 {{ detailResult.correctCount }} / {{ detailResult.questionCount }} 题</span>
        <span>状态 {{ detailResult.status }}</span>
      </div>
      <el-table :data="(detailResult && detailResult.items) || []" border size="small">
        <el-table-column label="题型" width="70">
          <template #default="{ row }">{{ typeLabel(row.type) }}</template>
        </el-table-column>
        <el-table-column label="题目" min-width="340">
          <template #default="{ row }">
            <div class="stem">{{ row.stem }}</div>
            <div class="muted-line">考生答案：{{ (row.yourAnswer || []).join('、') || '未作答' }}</div>
            <div class="muted-line">标准答案：{{ (row.standardAnswer || []).join('、') }}</div>
          </template>
        </el-table-column>
        <el-table-column label="结果" width="80">
          <template #default="{ row }">
            <span :class="row.correct ? 'ok-text' : 'bad-text'">
              {{ row.type === 'ESSAY' ? (row.marked ? '已阅' : '待阅') : (row.correct ? '✓' : '✗') }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="得分" width="80">
          <template #default="{ row }">{{ row.gotScore }} / {{ row.score }}</template>
        </el-table-column>
      </el-table>
      <template #footer>
        <el-button @click="detailVisible = false">关闭</el-button>
      </template>
    </el-drawer>
  </div>
</template>

<style scoped>
.stem {
  margin-bottom: 4px;
}

.muted-line {
  font-size: 12px;
  color: #8a94a6;
}

.detail-head {
  display: flex;
  gap: 24px;
  margin-bottom: 12px;
  font-size: 13px;
}

.ok-text {
  color: #16a34a;
}

.bad-text {
  color: #dc2626;
}
</style>
