<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { projectApi, questionApi, type OptionItem, type Project, type Question, type Subject } from '@/api'

const loading = ref(false)
const list = ref<Question[]>([])
const total = ref(0)
const projects = ref<Project[]>([])
const subjects = ref<Subject[]>([])
const query = reactive({ pageNo: 1, pageSize: 10, subjectId: undefined as number | undefined, keyword: '', type: '' })

const dialogVisible = ref(false)
const editingId = ref<number | null>(null)
const singleAnswer = ref('')
const essayAnswer = ref('')
const form = reactive({
  subjectId: undefined as number | undefined,
  type: 'SINGLE',
  stem: '',
  options: [] as OptionItem[],
  answer: [] as string[],
  analysis: '',
  difficulty: 'NORMAL',
  status: 'ENABLED'
})

const typeLabel: Record<string, string> = {
  SINGLE: '单选题',
  MULTIPLE: '多选题',
  JUDGE: '判断题',
  ESSAY: '主观题'
}

async function loadProjects() {
  const page = await projectApi.page({ pageNo: 1, pageSize: 50 })
  projects.value = page.list
  if (projects.value.length) {
    subjects.value = await projectApi.subjects(projects.value[0].id!)
    query.subjectId = subjects.value[0]?.id
  }
}

async function load() {
  loading.value = true
  try {
    const page = await questionApi.page(query)
    list.value = page.list
    total.value = page.total
  } finally {
    loading.value = false
  }
}

function resetForm() {
  editingId.value = null
  singleAnswer.value = ''
  essayAnswer.value = ''
  Object.assign(form, {
    subjectId: query.subjectId,
    type: 'SINGLE',
    stem: '',
    options: [
      { key: 'A', text: '' },
      { key: 'B', text: '' },
      { key: 'C', text: '' },
      { key: 'D', text: '' }
    ] as OptionItem[],
    answer: [],
    analysis: '',
    difficulty: 'NORMAL',
    status: 'ENABLED'
  })
}

function openCreate() {
  resetForm()
  dialogVisible.value = true
}

function openEdit(row: Question) {
  editingId.value = row.id
  singleAnswer.value = (row.answer || [])[0] || ''
  essayAnswer.value = row.type === 'ESSAY' ? (row.answer || [])[0] || '' : ''
  Object.assign(form, {
    subjectId: row.subjectId,
    type: row.type,
    stem: row.stem,
    options: row.options || [],
    answer: row.answer || [],
    analysis: row.analysis || '',
    difficulty: row.difficulty || 'NORMAL',
    status: row.status || 'ENABLED'
  })
  dialogVisible.value = true
}

function onTypeChange(type: string) {
  if (type === 'JUDGE') {
    form.options = [
      { key: 'A', text: '正确' },
      { key: 'B', text: '错误' }
    ]
    form.answer = []
  }
  if (type === 'ESSAY') {
    form.options = []
    form.answer = []
    singleAnswer.value = ''
  }
}

function addOption() {
  const key = String.fromCharCode(65 + form.options.length)
  form.options.push({ key, text: '' })
}

function removeOption(index: number) {
  form.options.splice(index, 1)
}

async function save() {
  if (!form.subjectId) return ElMessage.warning('请选择科目')
  if (!form.stem.trim()) return ElMessage.warning('题干不能为空')
  const answer = form.type === 'ESSAY'
    ? (essayAnswer.value.trim() ? [essayAnswer.value.trim()] : [])
    : form.type === 'MULTIPLE' ? form.answer : singleAnswer.value ? [singleAnswer.value] : []
  if (form.type !== 'ESSAY' && !answer.length) return ElMessage.warning('请设置标准答案')
  const payload = { ...form, answer, options: form.type === 'ESSAY' ? [] : form.options.filter((o) => o.text.trim()) }
  if (editingId.value) {
    await questionApi.update(editingId.value, payload)
  } else {
    await questionApi.create(payload)
  }
  ElMessage.success('保存成功，题目版本已更新')
  dialogVisible.value = false
  load()
}

async function remove(row: Question) {
  await ElMessageBox.confirm('删除后历史试卷快照不受影响，确认删除？', '提示', { type: 'warning' })
  await questionApi.remove(row.id)
  ElMessage.success('已删除')
  load()
}

async function toggleStatus(row: Question) {
  const next = row.status === 'ENABLED' ? 'DISABLED' : 'ENABLED'
  await questionApi.changeStatus(row.id, next)
  ElMessage.success('状态已更新')
  load()
}

onMounted(async () => {
  await loadProjects()
  await load()
})
</script>

<template>
  <div class="page-card">
    <h3 class="page-title">题库管理</h3>
    <p class="page-desc">支持单选、多选、判断；修改题目会递增版本，已发布试卷的快照内容保持不变。</p>

    <div class="toolbar">
      <el-select v-model="query.subjectId" placeholder="科目" style="width: 200px" clearable
                 @change="load">
        <el-option v-for="s in subjects" :key="s.id" :label="s.name" :value="s.id!" />
      </el-select>
      <el-select v-model="query.type" placeholder="题型" style="width: 130px" clearable @change="load">
        <el-option label="单选题" value="SINGLE" />
        <el-option label="多选题" value="MULTIPLE" />
        <el-option label="判断题" value="JUDGE" />
        <el-option label="主观题" value="ESSAY" />
      </el-select>
      <el-input v-model="query.keyword" placeholder="题干关键字" style="width: 220px" clearable
                @keyup.enter="load" />
      <el-button type="primary" @click="load">查询</el-button>
      <div class="spacer" />
      <el-button type="primary" @click="openCreate">新增题目</el-button>
    </div>

    <el-table v-loading="loading" :data="list" border stripe>
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column label="题型" width="92">
        <template #default="{ row }">
          <el-tag effect="plain">{{ typeLabel[row.type] || row.type }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="stem" label="题干" min-width="260" show-overflow-tooltip />
      <el-table-column label="答案" width="120">
        <template #default="{ row }">{{ (row.answer || []).join(',') }}</template>
      </el-table-column>
      <el-table-column prop="difficulty" label="难度" width="90" />
      <el-table-column prop="version" label="版本" width="80" />
      <el-table-column label="操作" width="210" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button link type="primary" @click="toggleStatus(row)">
            {{ row.status === 'ENABLED' ? '停用' : '启用' }}
          </el-button>
          <el-button link type="danger" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <div class="pager">
      <el-pagination v-model:current-page="query.pageNo" v-model:page-size="query.pageSize"
                     :total="total" layout="total, prev, pager, next" @current-change="load" />
    </div>

    <el-dialog v-model="dialogVisible" :title="editingId ? '编辑题目' : '新增题目'" width="680px">
      <el-form label-width="90px">
        <el-form-item label="所属科目" required>
          <el-select v-model="form.subjectId" style="width: 240px">
            <el-option v-for="s in subjects" :key="s.id" :label="s.name" :value="s.id!" />
          </el-select>
        </el-form-item>
        <el-form-item label="题型" required>
          <el-radio-group v-model="form.type" @change="onTypeChange">
            <el-radio-button label="SINGLE">单选</el-radio-button>
            <el-radio-button label="MULTIPLE">多选</el-radio-button>
            <el-radio-button label="JUDGE">判断</el-radio-button>
            <el-radio-button label="ESSAY">主观题</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="题干" required>
          <el-input v-model="form.stem" type="textarea" :rows="3" />
        </el-form-item>
        <el-form-item v-if="form.type !== 'ESSAY'" label="选项">
          <div class="options">
            <div v-for="(opt, index) in form.options" :key="index" class="option-row">
              <el-input v-model="opt.key" style="width: 70px" />
              <el-input v-model="opt.text" placeholder="选项内容" />
              <el-button link type="danger" @click="removeOption(index)">删除</el-button>
            </div>
            <el-button link type="primary" @click="addOption">+ 添加选项</el-button>
          </div>
        </el-form-item>
        <el-form-item v-if="form.type === 'ESSAY'" label="参考答案">
          <el-input v-model="essayAnswer" type="textarea" :rows="3"
                    placeholder="选填，仅作为阅卷参考，不参与自动判分" />
        </el-form-item>
        <el-form-item v-else label="标准答案" required>
          <el-checkbox-group v-if="form.type === 'MULTIPLE'" v-model="form.answer">
            <el-checkbox v-for="opt in form.options" :key="opt.key" :label="opt.key">
              {{ opt.key }}
            </el-checkbox>
          </el-checkbox-group>
          <el-radio-group v-else v-model="singleAnswer">
            <el-radio v-for="opt in form.options" :key="opt.key" :label="opt.key">
              {{ opt.key }}
            </el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="解析">
          <el-input v-model="form.analysis" type="textarea" :rows="2" />
        </el-form-item>
        <el-form-item label="难度">
          <el-select v-model="form.difficulty" style="width: 160px">
            <el-option label="简单" value="EASY" />
            <el-option label="中等" value="NORMAL" />
            <el-option label="困难" value="HARD" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.options {
  width: 100%;
}

.option-row {
  display: flex;
  gap: 8px;
  margin-bottom: 8px;
  align-items: center;
}
</style>
