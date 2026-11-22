<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { projectApi, type Project, type Subject } from '@/api'
import { useContentStore } from '@/stores/content'

const router = useRouter()
const content = useContentStore()

const loading = ref(false)
const list = ref<Project[]>([])
const total = ref(0)
const query = reactive({ pageNo: 1, pageSize: 10, keyword: '', status: '' })

const dialogVisible = ref(false)
const editing = ref<Project | null>(null)
const form = reactive<Project>({ code: '', name: '', intro: '', coverUrl: '', status: 'DRAFT', sortNo: 0 })

const drawerVisible = ref(false)
const currentProject = ref<Project | null>(null)
const subjects = ref<Subject[]>([])
const subjectLoading = ref(false)

const subjectDialogVisible = ref(false)
const editingSubjectId = ref<number | null>(null)
const subjectForm = reactive<Subject>({ projectId: 0, code: '', name: '', status: 'ENABLED', sortNo: 0 })

async function load() {
  loading.value = true
  try {
    const page = await projectApi.page(query)
    list.value = page.list
    total.value = page.total
  } finally {
    loading.value = false
  }
}

function resetQuery() {
  Object.assign(query, { pageNo: 1, keyword: '', status: '' })
  load()
}

function openCreate() {
  editing.value = null
  Object.assign(form, { code: '', name: '', intro: '', coverUrl: '', status: 'DRAFT', sortNo: 0 })
  dialogVisible.value = true
}

function openEdit(row: Project) {
  editing.value = row
  Object.assign(form, row)
  dialogVisible.value = true
}

async function save() {
  if (!form.code.trim() || !form.name.trim()) {
    ElMessage.warning('项目编码与名称必填')
    return
  }
  if (editing.value?.id) {
    await projectApi.update(editing.value.id, { ...form })
  } else {
    await projectApi.create({ ...form })
  }
  ElMessage.success('保存成功')
  dialogVisible.value = false
  await load()
  // 新建的项目要立刻出现在各页的项目下拉里，不能等下次刷新
  await content.refreshProjects()
}

async function remove(row: Project) {
  await ElMessageBox.confirm(`确认删除项目「${row.name}」？其下的科目、知识点将无法再维护。`, '删除项目', {
    type: 'warning'
  })
  await projectApi.remove(row.id!)
  ElMessage.success('已删除')
  await load()
  await content.refreshProjects()
}

async function openSubjects(row: Project) {
  currentProject.value = row
  drawerVisible.value = true
  await loadSubjects()
}

async function loadSubjects() {
  if (!currentProject.value?.id) return
  subjectLoading.value = true
  try {
    subjects.value = await projectApi.subjects(currentProject.value.id)
  } finally {
    subjectLoading.value = false
  }
}

function openSubjectCreate() {
  editingSubjectId.value = null
  Object.assign(subjectForm, {
    projectId: currentProject.value?.id ?? 0,
    code: '',
    name: '',
    status: 'ENABLED',
    sortNo: subjects.value.length + 1
  })
  subjectDialogVisible.value = true
}

function openSubjectEdit(row: Subject) {
  editingSubjectId.value = row.id ?? null
  Object.assign(subjectForm, {
    projectId: row.projectId,
    code: row.code,
    name: row.name,
    status: row.status || 'ENABLED',
    sortNo: row.sortNo ?? 0
  })
  subjectDialogVisible.value = true
}

async function saveSubject() {
  if (!subjectForm.code.trim() || !subjectForm.name.trim()) {
    ElMessage.warning('科目编码与名称必填')
    return
  }
  const payload: Subject = { ...subjectForm, projectId: currentProject.value!.id! }
  if (editingSubjectId.value) {
    await projectApi.updateSubject(editingSubjectId.value, payload)
    ElMessage.success('科目已更新')
  } else {
    await projectApi.createSubject(payload)
    ElMessage.success('科目已添加')
  }
  subjectDialogVisible.value = false
  await loadSubjects()
  await content.reloadSubjects()
}

async function toggleSubjectStatus(row: Subject) {
  const next = row.status === 'DISABLED' ? 'ENABLED' : 'DISABLED'
  await projectApi.updateSubject(row.id!, { ...row, status: next })
  ElMessage.success(next === 'ENABLED' ? '已启用' : '已停用')
  await loadSubjects()
}

async function removeSubject(row: Subject) {
  await ElMessageBox.confirm(`确认删除科目「${row.name}」？其下知识点与题库将无法再维护。`, '删除科目', {
    type: 'warning'
  })
  await projectApi.removeSubject(row.id!)
  ElMessage.success('已删除')
  await loadSubjects()
  await content.reloadSubjects()
}

/**
 * 从科目行直接跳到题库 / 知识点 / 课程，并把上下文带过去。
 * 之前要「记住项目名 → 切页面 → 再选一遍项目科目」，多项目时极易选错。
 */
async function jumpTo(target: 'questions' | 'knowledge' | 'courses', subject: Subject) {
  await content.ensureReady()
  await content.selectProject(currentProject.value!.id!)
  content.selectSubject(subject.id!)
  drawerVisible.value = false
  router.push(target === 'questions' ? '/questions' : target === 'knowledge' ? '/knowledge' : '/courses')
}

onMounted(load)
</script>

<template>
  <div class="page-card">
    <h3 class="page-title">考试项目</h3>
    <p class="page-desc">
      项目是业务的顶层容器，其下维护科目、知识点、题库与场次。点任意一行可打开该项目的科目列表。
    </p>

    <div class="toolbar">
      <el-input v-model="query.keyword" placeholder="项目名称 / 编码" style="width: 220px" clearable
                @keyup.enter="load" />
      <el-select v-model="query.status" placeholder="状态" style="width: 140px" clearable @change="load">
        <el-option label="草稿" value="DRAFT" />
        <el-option label="已发布" value="PUBLISHED" />
        <el-option label="已下线" value="OFFLINE" />
      </el-select>
      <el-button type="primary" @click="load">查询</el-button>
      <el-button @click="resetQuery">重置</el-button>
      <div class="spacer" />
      <el-button type="primary" @click="openCreate">新建项目</el-button>
    </div>

    <el-table v-loading="loading" :data="list" border stripe row-key="id"
              @row-click="openSubjects">
      <el-table-column label="项目" min-width="220">
        <template #default="{ row }">
          <div class="proj-cell">
            <el-image v-if="row.coverUrl" :src="row.coverUrl" fit="cover" class="proj-cover" />
            <div v-else class="proj-cover proj-cover--empty">{{ (row.name || '?').slice(0, 1) }}</div>
            <div class="proj-text">
              <div class="link-cell">{{ row.name }}</div>
              <div class="proj-code">{{ row.code }}</div>
            </div>
          </div>
        </template>
      </el-table-column>
      <el-table-column prop="targetAudience" label="适用对象" min-width="150" show-overflow-tooltip>
        <template #default="{ row }">{{ row.targetAudience || '—' }}</template>
      </el-table-column>
      <el-table-column label="状态" width="96">
        <template #default="{ row }">
          <el-tag :type="row.status === 'PUBLISHED' ? 'success' : row.status === 'OFFLINE' ? 'info' : 'warning'"
                  effect="plain" size="small">
            {{ row.status === 'PUBLISHED' ? '已发布' : row.status === 'OFFLINE' ? '已下线' : '草稿' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="168" align="right">
        <template #default="{ row }">
          <el-button link type="primary" @click.stop="openSubjects(row)">科目</el-button>
          <el-button link type="primary" @click.stop="openEdit(row)">编辑</el-button>
          <el-button link type="danger" @click.stop="remove(row)">删除</el-button>
        </template>
      </el-table-column>
      <template #empty>
        <div class="empty-wrap">
          <el-empty description="还没有项目，先建一个承载科目与题库的容器" :image-size="72" />
        </div>
      </template>
    </el-table>

    <div class="pager">
      <el-pagination v-model:current-page="query.pageNo" v-model:page-size="query.pageSize"
                     :total="total" :page-sizes="[10, 20, 50]"
                     layout="total, sizes, prev, pager, next" @current-change="load"
                     @size-change="load" />
    </div>

    <el-dialog v-model="dialogVisible" :title="editing ? '编辑项目' : '新建项目'" width="560px">
      <el-form label-width="90px">
        <el-form-item label="项目编码" required>
          <el-input v-model="form.code" placeholder="如 PMP" />
        </el-form-item>
        <el-form-item label="项目名称" required>
          <el-input v-model="form.name" />
        </el-form-item>
        <el-form-item label="项目简介">
          <el-input v-model="form.intro" type="textarea" :rows="3" />
        </el-form-item>
        <el-form-item label="封面图">
          <el-input v-model="form.coverUrl" placeholder="图片 URL，如 http://localhost:5173/covers/pmp.jpg" clearable />
          <div class="form-hint">留空则小程序端显示项目首字头像</div>
        </el-form-item>
        <el-form-item label="适用对象">
          <el-input v-model="form.targetAudience" />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="form.status" style="width: 160px">
            <el-option label="草稿" value="DRAFT" />
            <el-option label="已发布" value="PUBLISHED" />
            <el-option label="已下线" value="OFFLINE" />
          </el-select>
        </el-form-item>
        <el-form-item label="排序">
          <el-input-number v-model="form.sortNo" :min="0" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="save">保存</el-button>
      </template>
    </el-dialog>

    <el-drawer v-model="drawerVisible" :title="`科目 · ${currentProject?.name || ''}`" size="680px">
      <div class="drawer-head">
        <span class="drawer-sub">科目是题库、知识点、试卷与场次的挂载点，停用后不再出现在前台选择里。</span>
        <el-button type="primary" size="small" @click="openSubjectCreate">新增科目</el-button>
      </div>

      <el-table v-loading="subjectLoading" :data="subjects" border stripe row-key="id">
        <el-table-column prop="code" label="编码" width="130" />
        <el-table-column prop="name" label="名称" min-width="130" />
        <el-table-column label="状态" width="88">
          <template #default="{ row }">
            <el-tag :type="row.status === 'DISABLED' ? 'info' : 'success'" effect="plain" size="small">
              {{ row.status === 'DISABLED' ? '已停用' : '启用中' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="sortNo" label="排序" width="70" />
        <el-table-column label="操作" width="260" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="jumpTo('questions', row)">题库</el-button>
            <el-button link type="primary" @click="jumpTo('knowledge', row)">知识点</el-button>
            <el-button link type="primary" @click="openSubjectEdit(row)">编辑</el-button>
            <el-dropdown trigger="click" @command="(cmd: string) => cmd === 'toggle'
              ? toggleSubjectStatus(row) : removeSubject(row)">
              <el-button link type="primary">更多</el-button>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item command="toggle">
                    {{ row.status === 'DISABLED' ? '启用科目' : '停用科目' }}
                  </el-dropdown-item>
                  <el-dropdown-item command="remove" divided>删除科目</el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
          </template>
        </el-table-column>
        <template #empty>
          <div class="empty-wrap">
            <el-empty description="该项目还没有科目，点右上角新增" :image-size="64" />
          </div>
        </template>
      </el-table>
    </el-drawer>

    <el-dialog v-model="subjectDialogVisible" :title="editingSubjectId ? '编辑科目' : '新增科目'" width="480px">
      <el-form label-width="80px">
        <el-form-item label="编码" required>
          <el-input v-model="subjectForm.code" placeholder="如 PMP-BASE" />
        </el-form-item>
        <el-form-item label="名称" required>
          <el-input v-model="subjectForm.name" />
        </el-form-item>
        <el-form-item label="状态">
          <el-radio-group v-model="subjectForm.status">
            <el-radio-button label="ENABLED">启用</el-radio-button>
            <el-radio-button label="DISABLED">停用</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="排序">
          <el-input-number v-model="subjectForm.sortNo" :min="0" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="subjectDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="saveSubject">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.proj-cell {
  display: flex;
  align-items: center;
  gap: 10px;
}

.proj-cover {
  width: 48px;
  height: 32px;
  border-radius: 5px;
  flex-shrink: 0;
}

.proj-cover--empty {
  display: flex;
  align-items: center;
  justify-content: center;
  background: #eef2f7;
  color: #94a3b8;
  font-size: 14px;
  font-weight: 600;
}

.proj-text {
  min-width: 0;
}

.link-cell {
  color: #1f2937;
  cursor: pointer;
  line-height: 1.4;
}

.link-cell:hover {
  color: #409eff;
}

.proj-code {
  font-size: 12px;
  color: #94a3b8;
  line-height: 1.4;
}

.form-hint {
  font-size: 12px;
  color: #909399;
  margin-top: 4px;
}

.drawer-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 12px;
}

.drawer-sub {
  font-size: 12px;
  color: #8a94a6;
}
</style>
