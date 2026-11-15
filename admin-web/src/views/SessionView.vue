<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { paperApi, projectApi, sessionApi, type ExamSession, type Paper, type Project } from '@/api'

const loading = ref(false)
const list = ref<ExamSession[]>([])
const total = ref(0)
const query = reactive({ pageNo: 1, pageSize: 10, keyword: '', status: '' })
const projects = ref<Project[]>([])
const papers = ref<Paper[]>([])

const dialogVisible = ref(false)
const form = reactive<any>({})

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
      <el-input v-model="query.keyword" placeholder="场次名称" style="width: 200px" clearable
                @keyup.enter="load" />
      <el-select v-model="query.status" placeholder="状态" style="width: 130px" clearable>
        <el-option label="草稿" value="DRAFT" />
        <el-option label="已发布" value="PUBLISHED" />
        <el-option label="已关闭" value="CLOSED" />
      </el-select>
      <el-button type="primary" @click="load">查询</el-button>
      <div class="spacer" />
      <el-button type="primary" @click="openCreate">新建场次</el-button>
    </div>

    <el-table v-loading="loading" :data="list" border stripe>
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column prop="name" label="场次名称" min-width="180" />
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
      <el-table-column label="操作" width="250" fixed="right">
        <template #default="{ row }">
          <el-button v-if="row.status === 'DRAFT'" link type="primary" @click="publish(row)">发布</el-button>
          <el-button v-if="row.status === 'PUBLISHED'" link type="danger" @click="close(row)">关闭</el-button>
          <el-button link type="primary" @click="openMonitor(row)">考试监控</el-button>
        </template>
      </el-table-column>
    </el-table>

    <div class="pager">
      <el-pagination v-model:current-page="query.pageNo" v-model:page-size="query.pageSize"
                     :total="total" layout="total, prev, pager, next" @current-change="load" />
    </div>

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
  </div>
</template>

<style scoped>
.hint {
  margin-left: 8px;
  font-size: 12px;
  color: #8a94a6;
}
</style>
