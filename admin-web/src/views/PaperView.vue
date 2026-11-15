<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { paperApi, projectApi, questionApi, type Paper, type Project, type Question, type Subject } from '@/api'

interface RuleRow {
  type: string
  count: number
  score: number
  difficulty: string
}

const loading = ref(false)
const list = ref<Paper[]>([])
const total = ref(0)
const query = reactive({ pageNo: 1, pageSize: 10, keyword: '' })

const projects = ref<Project[]>([])
const subjects = ref<Subject[]>([])
const questions = ref<Question[]>([])

const dialogVisible = ref(false)
const form = reactive({
  projectId: undefined as number | undefined,
  subjectId: undefined as number | undefined,
  name: '',
  mode: 'FIXED',
  scorePerQuestion: 10,
  selected: [] as number[],
  rules: [] as RuleRow[]
})

const previewVisible = ref(false)
const previewData = ref<any>(null)

const plannedScore = computed(() => {
  if (form.mode === 'RULE') {
    return form.rules.reduce((sum, r) => sum + (r.count || 0) * (r.score || 0), 0)
  }
  return form.selected.length * form.scorePerQuestion
})

async function load() {
  loading.value = true
  try {
    const page = await paperApi.page(query)
    list.value = page.list
    total.value = page.total
  } finally {
    loading.value = false
  }
}

function parseQuestions(paper: Paper) {
  try {
    return JSON.parse(paper.questionJson || '[]') as any[]
  } catch {
    return []
  }
}

async function onProjectChange(projectId: number) {
  subjects.value = await projectApi.subjects(projectId)
  form.subjectId = subjects.value[0]?.id
  await onSubjectChange()
}

async function onSubjectChange() {
  if (!form.subjectId) return
  const page = await questionApi.page({ subjectId: form.subjectId, pageNo: 1, pageSize: 100 })
  questions.value = page.list
  form.selected = []
}

async function openCreate() {
  if (!projects.value.length) return ElMessage.warning('请先创建考试项目')
  Object.assign(form, {
    projectId: projects.value[0].id,
    subjectId: undefined,
    name: '',
    mode: 'FIXED',
    scorePerQuestion: 10,
    selected: [],
    rules: [{ type: 'SINGLE', count: 10, score: 5, difficulty: '' }]
  })
  await onProjectChange(projects.value[0].id!)
  dialogVisible.value = true
}

function addRule() {
  form.rules.push({ type: 'SINGLE', count: 5, score: 5, difficulty: '' })
}

function removeRule(index: number) {
  form.rules.splice(index, 1)
}

async function save() {
  if (!form.name.trim()) return ElMessage.warning('请填写试卷名称')
  if (form.mode === 'RULE') {
    if (!form.rules.length) return ElMessage.warning('请至少配置一条规则')
    if (form.rules.some((r) => !r.count || !r.score)) return ElMessage.warning('规则的题量与分值必须大于 0')
  } else if (!form.selected.length) {
    return ElMessage.warning('请至少选择一道题目')
  }

  const payload: any = {
    projectId: form.projectId,
    subjectId: form.subjectId,
    name: form.name,
    mode: form.mode
  }
  if (form.mode === 'RULE') {
    payload.rules = form.rules.map((r) => ({
      type: r.type,
      count: r.count,
      score: r.score,
      difficulty: r.difficulty || null
    }))
    payload.questions = []
  } else {
    payload.questions = form.selected.map((id) => ({ questionId: id, score: form.scorePerQuestion }))
  }

  await paperApi.create(payload)
  ElMessage.success('试卷已创建')
  dialogVisible.value = false
  load()
}

async function preview(row: Paper) {
  previewData.value = await paperApi.preview(row.id!)
  previewVisible.value = true
}

async function remove(row: Paper) {
  await ElMessageBox.confirm('确认删除该试卷？已生成的试卷快照不受影响。', '提示', { type: 'warning' })
  await paperApi.remove(row.id!)
  ElMessage.success('已删除')
  load()
}

onMounted(async () => {
  const page = await projectApi.page({ pageNo: 1, pageSize: 50 })
  projects.value = page.list
  await load()
})
</script>

<template>
  <div class="page-card">
    <h3 class="page-title">试卷管理</h3>
    <p class="page-desc">
      固定组卷直接指定题目；规则组卷按题型/难度随机抽题。两种情况都在场次发布时生成不可变快照。
    </p>

    <div class="toolbar">
      <el-input v-model="query.keyword" placeholder="试卷名称" style="width: 220px" clearable
                @keyup.enter="load" />
      <el-button type="primary" @click="load">查询</el-button>
      <div class="spacer" />
      <el-button type="primary" @click="openCreate">新建试卷</el-button>
    </div>

    <el-table v-loading="loading" :data="list" border stripe>
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column prop="name" label="试卷名称" min-width="200" />
      <el-table-column label="组卷方式" width="110">
        <template #default="{ row }">
          <el-tag :type="row.mode === 'RULE' ? 'warning' : 'info'" effect="plain">
            {{ row.mode === 'RULE' ? '规则组卷' : '固定组卷' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="totalScore" label="总分" width="90" />
      <el-table-column label="题目数" width="90">
        <template #default="{ row }">
          {{ row.mode === 'RULE' ? '发布时生成' : parseQuestions(row).length }}
        </template>
      </el-table-column>
      <el-table-column prop="version" label="版本" width="80" />
      <el-table-column label="状态" width="110">
        <template #default="{ row }">
          <el-tag :type="row.status === 'PUBLISHED' ? 'success' : 'info'" effect="plain">
            {{ row.status === 'PUBLISHED' ? '已生成快照' : '草稿' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="170" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="preview(row)">试组卷</el-button>
          <el-button link type="danger" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <div class="pager">
      <el-pagination v-model:current-page="query.pageNo" v-model:page-size="query.pageSize"
                     :total="total" layout="total, prev, pager, next" @current-change="load" />
    </div>

    <el-dialog v-model="dialogVisible" title="新建试卷" width="760px">
      <el-form label-width="90px">
        <el-form-item label="所属项目" required>
          <el-select v-model="form.projectId" style="width: 240px" @change="onProjectChange">
            <el-option v-for="p in projects" :key="p.id" :label="p.name" :value="p.id!" />
          </el-select>
        </el-form-item>
        <el-form-item label="所属科目">
          <el-select v-model="form.subjectId" style="width: 240px" @change="onSubjectChange">
            <el-option v-for="s in subjects" :key="s.id" :label="s.name" :value="s.id!" />
          </el-select>
        </el-form-item>
        <el-form-item label="试卷名称" required>
          <el-input v-model="form.name" />
        </el-form-item>
        <el-form-item label="组卷方式">
          <el-radio-group v-model="form.mode">
            <el-radio-button label="FIXED">固定组卷</el-radio-button>
            <el-radio-button label="RULE">规则组卷</el-radio-button>
          </el-radio-group>
        </el-form-item>

        <template v-if="form.mode === 'FIXED'">
          <el-form-item label="每题分值">
            <el-input-number v-model="form.scorePerQuestion" :min="1" :max="100" />
          </el-form-item>
          <el-form-item label="选择题目">
            <div class="picker">
              <el-checkbox-group v-model="form.selected">
                <el-checkbox v-for="q in questions" :key="q.id" :label="q.id" class="q-item">
                  #{{ q.id }} · {{ q.stem }}
                </el-checkbox>
              </el-checkbox-group>
            </div>
          </el-form-item>
        </template>

        <template v-else>
          <el-form-item label="组卷规则">
            <div class="rules">
              <div v-for="(rule, index) in form.rules" :key="index" class="rule-row">
                <el-select v-model="rule.type" style="width: 120px">
                  <el-option label="单选题" value="SINGLE" />
                  <el-option label="多选题" value="MULTIPLE" />
                  <el-option label="判断题" value="JUDGE" />
                </el-select>
                <el-select v-model="rule.difficulty" placeholder="难度不限" style="width: 120px" clearable>
                  <el-option label="简单" value="EASY" />
                  <el-option label="普通" value="NORMAL" />
                  <el-option label="困难" value="HARD" />
                </el-select>
                <el-input-number v-model="rule.count" :min="1" :max="200" placeholder="题量" />
                <span class="unit">题 ×</span>
                <el-input-number v-model="rule.score" :min="1" :max="100" placeholder="分值" />
                <span class="unit">分</span>
                <el-button link type="danger" @click="removeRule(index)">删除</el-button>
              </div>
              <el-button size="small" @click="addRule">添加规则</el-button>
            </div>
          </el-form-item>
        </template>

        <el-form-item label="合计">
          <span class="hint">预计 {{ form.mode === 'RULE' ? form.rules.length + ' 条规则' : form.selected.length + ' 题' }}，
            {{ plannedScore }} 分</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="save">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="previewVisible" title="试组卷结果" width="720px">
      <div v-if="previewData">
        <el-alert :title="`共 ${previewData.questions?.length || 0} 题，合计 ${previewData.totalScore} 分`"
                  type="success" :closable="false" show-icon />
        <el-table :data="previewData.questions" size="small" border class="preview-table">
          <el-table-column prop="questionId" label="题目ID" width="90" />
          <el-table-column prop="type" label="题型" width="110" />
          <el-table-column prop="stem" label="题干" min-width="260" show-overflow-tooltip />
          <el-table-column prop="score" label="分值" width="80" />
        </el-table>
      </div>
    </el-dialog>
  </div>
</template>

<style scoped>
.picker {
  width: 100%;
  max-height: 240px;
  overflow-y: auto;
}

.q-item {
  display: block;
  margin-bottom: 4px;
  white-space: normal;
  height: auto;
}

.rules {
  width: 100%;
}

.rule-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}

.unit {
  color: #6b7280;
  font-size: 13px;
}

.hint {
  font-size: 13px;
  color: #6b7280;
}

.preview-table {
  margin-top: 12px;
}
</style>
