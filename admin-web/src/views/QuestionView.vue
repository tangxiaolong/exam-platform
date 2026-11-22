<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { questionApi, type ImportReport, type OptionItem, type Question } from '@/api'
import ContentContextBar from '@/components/ContentContextBar.vue'
import { useContentStore } from '@/stores/content'

const content = useContentStore()

const loading = ref(false)
const list = ref<Question[]>([])
const total = ref(0)
const query = reactive({
  pageNo: 1,
  pageSize: 10,
  keyword: '',
  type: '',
  status: '',
  source: ''
})

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
  source: '',
  status: 'ENABLED'
})

const typeLabel: Record<string, string> = {
  SINGLE: '单选题',
  MULTIPLE: '多选题',
  JUDGE: '判断题',
  ESSAY: '主观题'
}

const difficultyLabel: Record<string, string> = { EASY: '简单', NORMAL: '中等', HARD: '困难' }

const tableRef = ref<any>(null)
const selected = ref<any[]>([])
const selectedIds = computed(() => selected.value.map((r: any) => r.id as number))

function onSelectionChange(rows: any[]) {
  selected.value = rows
}

function clearSelection() {
  selected.value = []
  tableRef.value?.clearSelection()
}

const moveVisible = ref(false)
const moving = ref(false)
const moveForm = reactive({ subjectId: undefined as number | undefined })

function openMove() {
  moveForm.subjectId = undefined
  moveVisible.value = true
}

async function confirmMove() {
  if (!moveForm.subjectId) {
    return ElMessage.warning('请选择目标科目')
  }
  moving.value = true
  try {
    const count = await questionApi.batchSubject(selectedIds.value, moveForm.subjectId)
    ElMessage.success(`已移动 ${count} 道题目`)
    moveVisible.value = false
    clearSelection()
    await load()
  } finally {
    moving.value = false
  }
}

async function batchStatus(status: string) {
  const label = status === 'ENABLED' ? '启用' : '停用'
  await ElMessageBox.confirm(`确认${label}选中的 ${selected.value.length} 道题目？`, `${label}确认`, {
    type: 'warning'
  })
  await questionApi.batchStatus(selectedIds.value, status)
  ElMessage.success(`已${label} ${selected.value.length} 道题目`)
  clearSelection()
  await load()
}

async function load() {
  loading.value = true
  try {
    const page = await questionApi.page({
      pageNo: query.pageNo,
      pageSize: query.pageSize,
      subjectId: content.subjectId ?? undefined,
      type: query.type || undefined,
      status: query.status || undefined,
      keyword: query.keyword.trim() || undefined,
      source: query.source.trim() || undefined
    })
    list.value = page.list
    total.value = page.total
  } finally {
    loading.value = false
  }
}

function resetQuery() {
  Object.assign(query, { pageNo: 1, keyword: '', type: '', status: '', source: '' })
  load()
}

/** 切换项目/科目：范围变了页码必须归位，否则会停在一个不存在的第 5 页上显示空表 */
function onContextChange() {
  query.pageNo = 1
  clearSelection()
  load()
}

function resetForm() {
  editingId.value = null
  singleAnswer.value = ''
  essayAnswer.value = ''
  Object.assign(form, {
    subjectId: content.subjectId ?? undefined,
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
    source: '',
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
    source: row.source || '',
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
    singleAnswer.value = ''
  }
  if (type === 'ESSAY') {
    form.options = []
    form.answer = []
    singleAnswer.value = ''
  }
  if (type === 'MULTIPLE') {
    // 从单选切过来时 singleAnswer 还留着上一次的选择，不清掉会同时存在两份答案
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
  await ElMessageBox.confirm('删除后历史试卷快照不受影响，确认删除？', '删除题目', { type: 'warning' })
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

const importVisible = ref(false)
const importing = ref(false)
const importFile = ref<File | null>(null)
const report = ref<ImportReport | null>(null)

function openImport() {
  importFile.value = null
  report.value = null
  importVisible.value = true
}

function onFileChange(file: { raw: File }) {
  importFile.value = file.raw
}

async function doImport() {
  if (!importFile.value) return ElMessage.warning('请先选择 CSV 文件')
  importing.value = true
  try {
    report.value = await questionApi.importCsv(importFile.value)
    // 后端是「整批校验、全过才落库」：committed=false 表示有行失败、整批没写入
    if (report.value.committed) {
      ElMessage.success(`导入成功 ${report.value.successCount} 条`)
      await load()
    } else {
      ElMessage.warning(`有 ${report.value.failureCount} 行未通过校验，整批未落库`)
    }
  } finally {
    importing.value = false
  }
}

async function downloadTemplate() {
  const blob = await questionApi.templateBlob()
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = 'question-import-template.csv'
  a.click()
  URL.revokeObjectURL(url)
}

function onMoreCommand(cmd: string) {
  if (cmd === 'import') openImport()
  if (cmd === 'template') downloadTemplate()
}
</script>

<template>
  <div class="page-card">
    <h3 class="page-title">题库管理</h3>
    <p class="page-desc">
      支持单选、多选、判断与主观题；修改题目会递增版本，已发布试卷的快照内容保持不变。
    </p>

    <ContentContextBar subject-optional @change="onContextChange" />

    <div class="toolbar">
      <el-input v-model="query.keyword" placeholder="题干关键字" style="width: 220px" clearable
                @keyup.enter="load" />
      <el-select v-model="query.type" placeholder="题型" style="width: 130px" clearable @change="load">
        <el-option v-for="(label, key) in typeLabel" :key="key" :label="label" :value="key" />
      </el-select>
      <el-select v-model="query.status" placeholder="状态" style="width: 130px" clearable @change="load">
        <el-option label="启用中" value="ENABLED" />
        <el-option label="已停用" value="DISABLED" />
      </el-select>
      <el-input v-model="query.source" placeholder="来源（如：2024年真题）" style="width: 190px" clearable
                @keyup.enter="load" />
      <el-button type="primary" @click="load">查询</el-button>
      <el-button @click="resetQuery">重置</el-button>
      <div class="spacer" />
      <el-dropdown trigger="click" @command="onMoreCommand">
        <el-button>
          导入<el-icon class="el-icon--right"><ArrowDown /></el-icon>
        </el-button>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item command="import">批量导入 CSV</el-dropdown-item>
            <el-dropdown-item command="template">下载导入模板</el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
      <el-button type="primary" @click="openCreate">新增题目</el-button>
    </div>

    <!-- 批量操作只在真的勾了题之后出现：常驻三个灰按钮会一直占着一行宽度 -->
    <div v-if="selected.length" class="sel-bar">
      <span class="sel-count">已选 <b>{{ selected.length }}</b> 道题</span>
      <el-button size="small" type="success" plain @click="batchStatus('ENABLED')">批量启用</el-button>
      <el-button size="small" type="warning" plain @click="batchStatus('DISABLED')">批量停用</el-button>
      <el-button size="small" type="info" plain @click="openMove">移动科目</el-button>
      <div class="spacer" />
      <el-button size="small" link @click="clearSelection">取消选择</el-button>
    </div>

    <el-table ref="tableRef" v-loading="loading" :data="list" border stripe row-key="id"
              @selection-change="onSelectionChange">
      <el-table-column type="selection" width="44" />
      <el-table-column type="expand" width="34">
        <template #default="{ row }">
          <div class="expand-body">
            <div v-if="row.options?.length" class="expand-block">
              <div class="expand-label">选项</div>
              <div v-for="opt in row.options" :key="opt.key" class="expand-option"
                   :class="{ correct: (row.answer || []).includes(opt.key) }">
                <span class="opt-key">{{ opt.key }}</span>
                <span>{{ opt.text }}</span>
              </div>
            </div>
            <div class="expand-block">
              <div class="expand-label">标准答案</div>
              <div class="expand-text">
                <template v-if="(row.answer || []).length">{{ (row.answer || []).join('、') }}</template>
                <span v-else class="muted">主观题，按参考答案人工阅卷</span>
              </div>
            </div>
            <div class="expand-block">
              <div class="expand-label">解析</div>
              <div class="expand-text">
                {{ row.analysis || '—' }}
              </div>
            </div>
          </div>
        </template>
      </el-table-column>
      <el-table-column prop="id" label="ID" width="76" />
      <el-table-column label="题型" width="92">
        <template #default="{ row }">
          <el-tag effect="plain" size="small">{{ typeLabel[row.type] || row.type }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column v-if="!content.subjectId" label="科目" width="130" show-overflow-tooltip>
        <template #default="{ row }">
          {{ content.subjectMap.get(row.subjectId) || '—' }}
        </template>
      </el-table-column>
      <el-table-column prop="stem" label="题干" min-width="240" show-overflow-tooltip />
      <el-table-column label="答案" width="110">
        <template #default="{ row }">
          {{ (row.answer || []).join(',') || '—' }}
        </template>
      </el-table-column>
      <el-table-column label="难度" width="86">
        <template #default="{ row }">{{ difficultyLabel[row.difficulty || 'NORMAL'] }}</template>
      </el-table-column>
      <el-table-column prop="source" label="来源" width="130" show-overflow-tooltip />
      <el-table-column label="状态" width="92">
        <template #default="{ row }">
          <el-tag :type="row.status === 'DISABLED' ? 'info' : 'success'" effect="plain" size="small">
            {{ row.status === 'DISABLED' ? '已停用' : '启用中' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="version" label="版本" width="70" />
      <el-table-column label="操作" width="190" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button link type="primary" @click="toggleStatus(row)">
            {{ row.status === 'ENABLED' ? '停用' : '启用' }}
          </el-button>
          <el-button link type="danger" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
      <template #empty>
        <div class="empty-wrap">
          <el-empty :description="content.subjectId
            ? '该科目下还没有题目，可以手动新增或批量导入'
            : '当前项目下还没有题目'" :image-size="72" />
        </div>
      </template>
    </el-table>

    <div class="pager">
      <el-pagination v-model:current-page="query.pageNo" v-model:page-size="query.pageSize"
                     :total="total" :page-sizes="[10, 20, 50]"
                     layout="total, sizes, prev, pager, next" @current-change="load"
                     @size-change="load" />
    </div>

    <el-dialog v-model="dialogVisible" :title="editingId ? '编辑题目' : '新增题目'" width="680px">
      <el-form label-width="90px">
        <el-form-item label="所属科目" required>
          <el-select v-model="form.subjectId" style="width: 240px" filterable>
            <el-option v-for="s in content.subjects" :key="s.id" :label="s.name" :value="s.id!" />
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
        <el-form-item label="来源">
          <el-input v-model="form.source" placeholder="如：2024 年真题 / 教材同步 / 自编" style="width: 320px" />
        </el-form-item>
        <el-form-item label="状态">
          <el-radio-group v-model="form.status">
            <el-radio-button label="ENABLED">启用</el-radio-button>
            <el-radio-button label="DISABLED">停用</el-radio-button>
          </el-radio-group>
          <span class="form-hint">停用后组卷与练习都不会抽到这道题</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="save">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="moveVisible" title="移动科目" width="440px">
      <el-form label-width="90px">
        <el-form-item label="目标科目" required>
          <el-select v-model="moveForm.subjectId" placeholder="选择目标科目" style="width: 100%" filterable>
            <el-option v-for="s in content.subjects" :key="s.id" :label="s.name" :value="s.id!" />
          </el-select>
        </el-form-item>
      </el-form>
      <p class="page-desc" style="margin: 0 0 0 90px;">
        已发布场次的卷面快照不受影响，移动只作用于之后的组卷与练习。
      </p>
      <template #footer>
        <el-button @click="moveVisible = false">取消</el-button>
        <el-button type="primary" :loading="moving" @click="confirmMove">移动</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="importVisible" title="批量导入题目" width="760px">
      <el-alert type="info" show-icon :closable="false"
                title="整批校验通过才会落库；任一行失败则整批不写入，按行号修正后重新导入。" />
      <div class="import-picker">
        <el-upload :auto-upload="false" :show-file-list="false" accept=".csv" :on-change="onFileChange">
          <el-button>选择 CSV 文件</el-button>
        </el-upload>
        <el-button link type="primary" @click="downloadTemplate">下载模板</el-button>
        <span v-if="importFile" class="file-name">{{ importFile.name }}</span>
      </div>
      <template v-if="report">
        <p class="report-summary">
          共 {{ report.totalRows }} 行：成功 {{ report.successCount }}，失败 {{ report.failureCount }}，
          {{ report.committed ? '已落库' : '整批未落库' }}
        </p>
        <el-table :data="report.rows" border stripe max-height="320" size="small">
          <el-table-column prop="lineNo" label="行号" width="80" />
          <el-table-column label="结果" width="90">
            <template #default="{ row }">
              <el-tag :type="row.status === 'SUCCESS' ? 'success' : 'danger'" size="small">
                {{ row.status === 'SUCCESS' ? '成功' : '失败' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="stem" label="题干" min-width="220" show-overflow-tooltip />
          <el-table-column label="错误" min-width="200">
            <template #default="{ row }">
              <span v-if="row.errorMessage">{{ row.errorCode }}：{{ row.errorMessage }}</span>
              <span v-else class="muted">—</span>
            </template>
          </el-table-column>
        </el-table>
      </template>
      <template #footer>
        <el-button @click="importVisible = false">关闭</el-button>
        <el-button type="primary" :loading="importing" @click="doImport">开始导入</el-button>
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

.expand-body {
  display: flex;
  gap: 32px;
  flex-wrap: wrap;
  padding: 6px 16px 10px 48px;
  background: #fafcff;
}

.expand-block {
  min-width: 180px;
}

.expand-label {
  font-size: 12px;
  color: #8a94a6;
  margin-bottom: 6px;
}

.expand-option {
  display: flex;
  gap: 8px;
  align-items: baseline;
  font-size: 13px;
  color: #4b5563;
  padding: 2px 0;
}

.expand-option.correct {
  color: #16a34a;
  font-weight: 600;
}

.opt-key {
  font-variant-numeric: tabular-nums;
  min-width: 14px;
}

.expand-text {
  font-size: 13px;
  color: #4b5563;
  line-height: 1.6;
  max-width: 520px;
  white-space: pre-wrap;
}

.muted {
  color: #9ca3af;
  font-size: 12px;
}

.form-hint {
  margin-left: 10px;
  font-size: 12px;
  color: #909399;
}

.import-picker {
  display: flex;
  gap: 12px;
  align-items: center;
  margin: 16px 0;
}

.file-name {
  color: #666;
  font-size: 13px;
}

.report-summary {
  margin: 8px 0;
}
</style>
