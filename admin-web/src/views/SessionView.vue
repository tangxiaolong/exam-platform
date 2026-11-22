<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { exportApi, paperApi, projectApi, sessionApi, type ExamSession, type LeaderboardEntry, type Paper, type Project, type QuestionAccuracyItem, type SessionAnalytics } from '@/api'

const loading = ref(false)
const list = ref<ExamSession[]>([])
const total = ref(0)
const query = reactive({ pageNo: 1, pageSize: 10, keyword: '', status: '' })
const projects = ref<Project[]>([])
const papers = ref<Paper[]>([])

const dialogVisible = ref(false)
const form = reactive<any>({})

const compareVisible = ref(false)
const compareLoading = ref(false)
const compareRows = ref<any[]>([])
const compareSelected = ref<ExamSession[]>([])

function onCompareSelection(rows: ExamSession[]) {
  compareSelected.value = rows
}

async function openCompare() {
  if (compareSelected.value.length < 2) {
    return ElMessage.warning('请至少勾选 2 个场次')
  }
  if (compareSelected.value.length > 10) {
    return ElMessage.warning('一次最多对比 10 个场次')
  }
  compareVisible.value = true
  compareLoading.value = true
  try {
    compareRows.value = await sessionApi.compare(
      compareSelected.value.map((row: any) => row.id)
    )
  } finally {
    compareLoading.value = false
  }
}

async function exportCompare() {
  if (compareSelected.value.length < 2) {
    return ElMessage.warning('请至少勾选 2 个场次')
  }
  const blob = await sessionApi.compareExport(compareSelected.value.map((row: any) => row.id))
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = 'session-compare.csv'
  a.click()
  URL.revokeObjectURL(url)
}

const monitorVisible = ref(false)
const attempts = ref<any[]>([])
const currentSession = ref<ExamSession | null>(null)

async function load() {
  loading.value = true
  try {
    const page = await sessionApi.page(query)
    list.value = page.list
    total.value = page.total
  } finally {
    loading.value = false
  }
}

const cloneVisible = ref(false)
const cloneSaving = ref(false)
const cloneForm = reactive<any>({})

function openClone(row: ExamSession) {
  Object.assign(cloneForm, {
    sourceId: row.id,
    name: `${row.name}-副本`,
    registerStartAt: '',
    registerEndAt: '',
    startAt: '',
    endAt: '',
    resultPublishAt: ''
  })
  cloneVisible.value = true
}

async function submitClone() {
  if (!cloneForm.name) return ElMessage.warning('请填写场次名称')
  if (!cloneForm.startAt || !cloneForm.endAt) return ElMessage.warning('请选择考试起止时间')
  cloneSaving.value = true
  try {
    const payload: any = {
      name: cloneForm.name,
      startAt: cloneForm.startAt,
      endAt: cloneForm.endAt
    }
    if (cloneForm.registerStartAt && cloneForm.registerEndAt) {
      payload.registerStartAt = cloneForm.registerStartAt
      payload.registerEndAt = cloneForm.registerEndAt
    }
    if (cloneForm.resultPublishAt) {
      payload.resultPublishAt = cloneForm.resultPublishAt
    }
    await sessionApi.clone(cloneForm.sourceId, payload)
    ElMessage.success('已复制为新草稿，发布后生成试卷快照')
    cloneVisible.value = false
    load()
  } finally {
    cloneSaving.value = false
  }
}

function openCreate() {
  if (!projects.value.length || !papers.value.length) {
    ElMessage.warning('请先准备考试项目与试卷')
    return
  }
  const now = new Date()
  const startAt = new Date(now.getTime() + 10 * 60 * 1000)
  const endAt = new Date(now.getTime() + 130 * 60 * 1000)
  Object.assign(form, {
    projectId: projects.value[0].id,
    paperId: papers.value[0].id,
    name: '',
    registerStartAt: toLocal(new Date(now.getTime() - 60 * 60 * 1000)),
    registerEndAt: toLocal(endAt),
    startAt: toLocal(startAt),
    endAt: toLocal(endAt),
    durationMinutes: 60,
    allowEnterBeforeMinutes: 20,
    lateLimitMinutes: 0,
    quota: 100,
    noticeVersion: 'v1',
    noticeContent: '1. 开考后请保持网络畅通；2. 到时系统自动交卷。'
  })
  dialogVisible.value = true
}

function toLocal(date: Date) {
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
}

async function save() {
  if (!form.name) return ElMessage.warning('请填写场次名称')
  await sessionApi.create({ ...form })
  ElMessage.success('场次已创建（草稿），发布后生成试卷快照')
  dialogVisible.value = false
  load()
}

async function publish(row: ExamSession) {
  await ElMessageBox.confirm('发布后将生成试卷快照且考试规则不可再修改，确认发布？', '提示', {
    type: 'warning'
  })
  await sessionApi.publish(row.id!)
  ElMessage.success('已发布')
  load()
}

async function close(row: ExamSession) {
  await ElMessageBox.confirm('确认关闭该场次？', '提示', { type: 'warning' })
  await sessionApi.close(row.id!)
  ElMessage.success('已关闭')
  load()
}

const regVisible = ref(false)
const regs = ref<any[]>([])
const regSession = ref<ExamSession | null>(null)

async function openRegistrations(row: ExamSession) {
  regSession.value = row
  regs.value = (await sessionApi.registrations(row.id!)) || []
  regVisible.value = true
}

function regStatusLabel(status: string) {
  switch (status) {
    case 'REGISTERED':
      return '已报名'
    case 'CANCELLED':
      return '已取消'
    case 'CHECKED_IN':
      return '已入场'
    default:
      return status || '—'
  }
}

const analyticsVisible = ref(false)
const analytics = ref<SessionAnalytics | null>(null)
const analyticsSession = ref<ExamSession | null>(null)
const accuracyList = ref<QuestionAccuracyItem[]>([])
const leaderboardList = ref<LeaderboardEntry[]>([])

async function openAnalytics(row: ExamSession) {
  analyticsSession.value = row
  // 三个接口互不依赖，并行拉取；空数据照常展示
  const [summary, accuracy, board] = await Promise.all([
    sessionApi.analytics(row.id!),
    sessionApi.questionAccuracy(row.id!),
    sessionApi.leaderboard(row.id!, 10)
  ])
  analytics.value = summary
  accuracyList.value = accuracy || []
  leaderboardList.value = board || []
  analyticsVisible.value = true
}

const exporting = ref(false)

async function exportReport() {
  if (!analyticsSession.value) {
    return
  }
  exporting.value = true
  try {
    const task = await exportApi.submit({ type: 'SESSION_ANALYTICS', sessionId: analyticsSession.value.id! })
    ElMessage.success(`导出任务已提交（${task.taskNo}），完成后自动下载`)
    // 导出是异步任务：轮询约 15 秒，超时就引导去报表导出页
    for (let i = 0; i < 15; i++) {
      await new Promise((resolve) => setTimeout(resolve, 800))
      const detail = await exportApi.detail(task.id!)
      if (detail.status === 'SUCCESS') {
        const blob = await exportApi.download(task.id!)
        const url = URL.createObjectURL(blob)
        const a = document.createElement('a')
        a.href = url
        a.download = detail.fileName || 'session-analytics.csv'
        a.click()
        URL.revokeObjectURL(url)
        return
      }
      if (detail.status === 'FAILED') {
        ElMessage.error(detail.errorMsg || '导出失败')
        return
      }
    }
    ElMessage.warning('导出仍在处理中，可稍后到「报表导出」页下载')
  } finally {
    exporting.value = false
  }
}

function bandPercent(count: number) {
  if (!analytics.value || !analytics.value.participantCount) {
    return 0
  }
  return Math.round((count * 100) / analytics.value.participantCount)
}

async function openMonitor(row: ExamSession) {
  currentSession.value = row
  attempts.value = await sessionApi.attempts(row.id!)
  monitorVisible.value = true
}

async function forceSubmit(attempt: any) {
  await ElMessageBox.confirm(`确认对考试记录 #${attempt.attemptId} 强制收卷？`, '提示', {
    type: 'warning'
  })
  const result = await sessionApi.forceSubmit(attempt.attemptId)
  ElMessage.success(`已收卷，得分 ${result.totalScore}`)
  attempts.value = await sessionApi.attempts(currentSession.value!.id!)
}

onMounted(async () => {
  const [p, pa] = await Promise.all([
    projectApi.page({ pageNo: 1, pageSize: 50 }),
    paperApi.page({ pageNo: 1, pageSize: 50 })
  ])
  projects.value = p.list
  papers.value = pa.list
  await load()
})
</script>

<template>
  <div class="page-card">
    <h3 class="page-title">考试场次</h3>
    <p class="page-desc">发布场次会生成不可变试卷快照；已发布场次的考试规则不允许修改。</p>

    <div class="toolbar">
      <el-input v-model="query.keyword" placeholder="场次名称" style="width: 220px" clearable
                @keyup.enter="load" />
      <el-select v-model="query.status" placeholder="状态" style="width: 130px" clearable>
        <el-option label="草稿" value="DRAFT" />
        <el-option label="已发布" value="PUBLISHED" />
        <el-option label="已关闭" value="CLOSED" />
      </el-select>
      <el-button type="primary" @click="load">查询</el-button>
      <div class="spacer" />
      <el-button type="primary" @click="openCreate">新建场次</el-button>
      <el-button type="info" plain :disabled="compareSelected.length < 2" @click="openCompare">
        成绩对比{{ compareSelected.length ? '(' + compareSelected.length + ')' : '' }}
      </el-button>
    </div>

    <el-table v-loading="loading" :data="list" border stripe @selection-change="onCompareSelection">
      <el-table-column type="selection" width="44" />
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column prop="name" label="场次名称" min-width="200" />
      <el-table-column prop="startAt" label="开始时间" width="180" />
      <el-table-column prop="endAt" label="结束时间" width="180" />
      <el-table-column prop="durationMinutes" label="时长(分)" width="100" />
      <el-table-column prop="quota" label="名额" width="80" />
      <el-table-column label="快照" width="90">
        <template #default="{ row }">
          <el-tag v-if="row.snapshotId" type="success" effect="plain">#{{ row.snapshotId }}</el-tag>
          <span v-else>-</span>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="row.status === 'PUBLISHED' ? 'success' : row.status === 'CLOSED' ? 'danger' : 'info'"
                  effect="plain">
            {{ row.status === 'PUBLISHED' ? '已发布' : row.status === 'CLOSED' ? '已关闭' : '草稿' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="410" fixed="right">
        <template #default="{ row }">
          <el-button v-if="row.status === 'DRAFT'" link type="primary" @click="publish(row)">发布</el-button>
          <el-button v-if="row.status === 'PUBLISHED'" link type="danger" @click="close(row)">关闭</el-button>
          <el-button link type="primary" @click="openRegistrations(row)">报名名单</el-button>
          <el-button link type="primary" @click="openAnalytics(row)">成绩分析</el-button>
          <el-button link type="primary" @click="openMonitor(row)">考试监控</el-button>
      <el-button link type="primary" @click="openClone(row)">复制</el-button>
        </template>
      </el-table-column>
    </el-table>

    <div class="pager">
      <el-pagination v-model:current-page="query.pageNo" v-model:page-size="query.pageSize"
                     :total="total" layout="total, prev, pager, next" @current-change="load" />
    </div>

    <el-drawer v-model="compareVisible" title="场次成绩对比" size="640px">
      <el-table v-loading="compareLoading" :data="compareRows" border stripe>
        <el-table-column prop="session_name" label="场次" min-width="150" show-overflow-tooltip />
        <el-table-column prop="result_count" label="答卷数" width="90" />
        <el-table-column prop="avg_score" label="均分" width="90" />
        <el-table-column prop="max_score" label="最高" width="90" />
        <el-table-column prop="min_score" label="最低" width="90" />
        <el-table-column prop="full_score" label="满分" width="90" />
      </el-table>
      <div style="margin-top: 12px;">
        <el-button type="primary" plain :disabled="compareSelected.length < 2" @click="exportCompare">
          导出 CSV
        </el-button>
      </div>
      <p class="page-desc" style="padding: 12px 4px 0;">
        仅统计已出分的答卷（exam_result），与单场次成绩分析同口径；无答卷场次答卷数为 0。
      </p>
    </el-drawer>

    <el-dialog v-model="cloneVisible" title="复制场次" width="520px">
      <el-form label-width="100px">
        <el-form-item label="场次名称" required>
          <el-input v-model="cloneForm.name" />
        </el-form-item>
        <el-form-item label="报名时间">
          <el-date-picker v-model="cloneForm.registerStartAt" type="datetime" placeholder="报名开始（可空）"
                          value-format="YYYY-MM-DDTHH:mm:ss" style="width: 48%" />
          <el-date-picker v-model="cloneForm.registerEndAt" type="datetime" placeholder="报名截止（可空）"
                          value-format="YYYY-MM-DDTHH:mm:ss" style="width: 48%; margin-left: 4%" />
        </el-form-item>
        <el-form-item label="考试时间" required>
          <el-date-picker v-model="cloneForm.startAt" type="datetime" placeholder="开始"
                          value-format="YYYY-MM-DDTHH:mm:ss" style="width: 48%" />
          <el-date-picker v-model="cloneForm.endAt" type="datetime" placeholder="结束"
                          value-format="YYYY-MM-DDTHH:mm:ss" style="width: 48%; margin-left: 4%" />
        </el-form-item>
        <el-form-item label="发布时间">
          <el-date-picker v-model="cloneForm.resultPublishAt" type="datetime" placeholder="留空则交卷即可见"
                          value-format="YYYY-MM-DDTHH:mm:ss" style="width: 100%" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="cloneVisible = false">取消</el-button>
        <el-button type="primary" :loading="cloneSaving" @click="submitClone">复制并保存草稿</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="dialogVisible" title="新建考试场次" width="720px">
      <el-form label-width="120px">
        <el-form-item label="所属项目">
          <el-select v-model="form.projectId" style="width: 260px">
            <el-option v-for="p in projects" :key="p.id" :label="p.name" :value="p.id!" />
          </el-select>
        </el-form-item>
        <el-form-item label="试卷">
          <el-select v-model="form.paperId" style="width: 260px">
            <el-option v-for="p in papers" :key="p.id" :label="p.name" :value="p.id!" />
          </el-select>
        </el-form-item>
        <el-form-item label="场次名称" required>
          <el-input v-model="form.name" />
        </el-form-item>
        <el-form-item label="报名开始">
          <el-date-picker v-model="form.registerStartAt" type="datetime"
                          value-format="YYYY-MM-DDTHH:mm:ss" style="width: 260px" />
        </el-form-item>
        <el-form-item label="报名截止">
          <el-date-picker v-model="form.registerEndAt" type="datetime"
                          value-format="YYYY-MM-DDTHH:mm:ss" style="width: 260px" />
        </el-form-item>
        <el-form-item label="考试开始" required>
          <el-date-picker v-model="form.startAt" type="datetime"
                          value-format="YYYY-MM-DDTHH:mm:ss" style="width: 260px" />
        </el-form-item>
        <el-form-item label="考试结束" required>
          <el-date-picker v-model="form.endAt" type="datetime"
                          value-format="YYYY-MM-DDTHH:mm:ss" style="width: 260px" />
        </el-form-item>
        <el-form-item label="答题时长(分钟)">
          <el-input-number v-model="form.durationMinutes" :min="1" :max="600" />
        </el-form-item>
        <el-form-item label="提前入场(分钟)">
          <el-input-number v-model="form.allowEnterBeforeMinutes" :min="0" :max="1440" />
        </el-form-item>
        <el-form-item label="名额">
          <el-input-number v-model="form.quota" :min="0" />
          <span class="hint">0 表示不限</span>
        </el-form-item>
        <el-form-item label="考试须知">
          <el-input v-model="form.noticeContent" type="textarea" :rows="3" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="save">保存</el-button>
      </template>
    </el-dialog>

    <el-drawer v-model="monitorVisible" :title="`考试监控 · ${currentSession?.name || ''}`" size="620px">
      <el-table :data="attempts" border size="small">
        <el-table-column prop="attemptId" label="记录" width="80" />
        <el-table-column prop="status" label="状态" width="120" />
        <el-table-column prop="deadlineAt" label="截止时间" width="180" />
        <el-table-column prop="totalScore" label="得分" width="80" />
        <el-table-column label="操作" width="100">
          <template #default="{ row }">
            <el-button v-if="row.status === 'IN_PROGRESS'" link type="danger"
                       @click="forceSubmit(row)">强制收卷</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-drawer>

    <el-drawer v-model="regVisible" :title="`报名名单 · ${regSession?.name || ''}`" size="560px">
      <el-table :data="regs" border size="small">
        <el-table-column prop="registrationId" label="报名ID" width="90" />
        <el-table-column prop="ticketNo" label="准考证号" min-width="140" />
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag effect="plain">{{ regStatusLabel(row.status) }}</el-tag>
          </template>
        </el-table-column>
      </el-table>
      <div v-if="!regs.length" style="color: #999; font-size: 12px; margin-top: 8px;">
        暂无报名记录。
      </div>
    </el-drawer>

    <el-dialog v-model="analyticsVisible" :title="`成绩分析 · ${analyticsSession?.name || ''}`" width="680px">
      <template v-if="analytics">
        <el-descriptions :column="3" border>
          <el-descriptions-item label="参考人数">{{ analytics.participantCount }}</el-descriptions-item>
          <el-descriptions-item label="满分">{{ analytics.fullScore }}</el-descriptions-item>
          <el-descriptions-item label="平均分">{{ analytics.avgScore }}</el-descriptions-item>
          <el-descriptions-item label="最高分">{{ analytics.maxScore }}</el-descriptions-item>
          <el-descriptions-item label="最低分">{{ analytics.minScore }}</el-descriptions-item>
          <el-descriptions-item :label="`及格率（≥${analytics.passPercent}%）`">
            {{ analytics.passRate }}%
          </el-descriptions-item>
        </el-descriptions>

        <h4 style="margin: 16px 0 8px;">分数段分布</h4>
        <el-table :data="analytics.distribution" border size="small">
          <el-table-column prop="label" label="分数段" width="120" />
          <el-table-column prop="count" label="人数" width="90" />
          <el-table-column label="占比">
            <template #default="{ row }">
              <el-progress :percentage="bandPercent(row.count)" />
            </template>
          </el-table-column>
        </el-table>
        <div style="color: #999; font-size: 12px; margin-top: 8px;">
          只统计已判分或已发布成绩的答卷；阅卷中的答卷客观题分不完整，混入会拉低均值。
        </div>

        <h4 style="margin: 16px 0 8px;">逐题正确率（最低的在前）</h4>
        <el-table :data="accuracyList" border size="small" max-height="280">
          <el-table-column prop="questionId" label="题目" width="80" />
          <el-table-column prop="stem" label="题干" min-width="220" show-overflow-tooltip />
          <el-table-column prop="answered" label="作答" width="70" />
          <el-table-column prop="correct" label="答对" width="70" />
          <el-table-column label="正确率" width="160">
            <template #default="{ row }">
              <el-progress :percentage="Math.min(100, Math.round(row.accuracy))" />
            </template>
          </el-table-column>
        </el-table>
        <div v-if="!accuracyList.length" style="color: #999; font-size: 12px; margin-top: 4px;">
          暂无逐题数据：成绩明细为空，或答卷还在阅卷中。
        </div>

        <h4 style="margin: 16px 0 8px;">成绩排行（前 10）</h4>
        <el-table :data="leaderboardList" border size="small">
          <el-table-column prop="rank" label="名次" width="70" />
          <el-table-column prop="userName" label="考生" min-width="160" show-overflow-tooltip />
          <el-table-column prop="totalScore" label="总分" width="100" />
        </el-table>
      </template>
      <template #footer>
        <el-button type="primary" :loading="exporting" @click="exportReport">导出报表</el-button>
        <el-button @click="analyticsVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.hint {
  margin-left: 8px;
  font-size: 12px;
  color: #8a94a6;
}
</style>
