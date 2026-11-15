<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { projectApi, type Project, type Subject } from '@/api'

const loading = ref(false)
const list = ref<Project[]>([])
const total = ref(0)
const query = reactive({ pageNo: 1, pageSize: 10, keyword: '', status: '' })

const dialogVisible = ref(false)
const editing = ref<Project | null>(null)
const form = reactive<Project>({ code: '', name: '', intro: '', status: 'DRAFT', sortNo: 0 })

const drawerVisible = ref(false)
const currentProject = ref<Project | null>(null)
const subjects = ref<Subject[]>([])
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

function openCreate() {
  editing.value = null
  Object.assign(form, { code: '', name: '', intro: '', status: 'DRAFT', sortNo: 0 })
  dialogVisible.value = true
}

function openEdit(row: Project) {
  editing.value = row
  Object.assign(form, row)
  dialogVisible.value = true
}

async function save() {
  if (!form.code || !form.name) {
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
  load()
}

async function remove(row: Project) {
  await ElMessageBox.confirm(`确认删除项目「${row.name}」？`, '提示', { type: 'warning' })
  await projectApi.remove(row.id!)
  ElMessage.success('已删除')
  load()
}

async function openSubjects(row: Project) {
  currentProject.value = row
  drawerVisible.value = true
  subjects.value = await projectApi.subjects(row.id!)
}

async function addSubject() {
  if (!subjectForm.code || !subjectForm.name) {
    ElMessage.warning('科目编码与名称必填')
    return
  }
  await projectApi.createSubject({ ...subjectForm, projectId: currentProject.value!.id! })
  ElMessage.success('科目已添加')
  Object.assign(subjectForm, { code: '', name: '', status: 'ENABLED', sortNo: 0 })
  subjects.value = await projectApi.subjects(currentProject.value!.id!)
}

onMounted(load)
</script>

<template>
  <div class="page-card">
    <h3 class="page-title">考试项目</h3>
    <p class="page-desc">项目是业务的顶层容器，其下维护科目、知识点、题库与场次。</p>

    <div class="toolbar">
      <el-input v-model="query.keyword" placeholder="项目名称/编码" style="width: 220px" clearable
                @keyup.enter="load" />
      <el-select v-model="query.status" placeholder="状态" style="width: 140px" clearable>
        <el-option label="草稿" value="DRAFT" />
        <el-option label="已发布" value="PUBLISHED" />
        <el-option label="已下线" value="OFFLINE" />
      </el-select>
      <el-button type="primary" @click="load">查询</el-button>
      <div class="spacer" />
      <el-button type="primary" @click="openCreate">新建项目</el-button>
    </div>

    <el-table v-loading="loading" :data="list" border stripe>
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column prop="code" label="编码" width="140" />
      <el-table-column prop="name" label="名称" min-width="180" />
      <el-table-column prop="targetAudience" label="适用对象" min-width="180" show-overflow-tooltip />
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="row.status === 'PUBLISHED' ? 'success' : 'info'" effect="plain">
            {{ row.status === 'PUBLISHED' ? '已发布' : row.status === 'OFFLINE' ? '已下线' : '草稿' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="sortNo" label="排序" width="80" />
      <el-table-column label="操作" width="240" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openSubjects(row)">科目</el-button>
          <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button link type="danger" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <div class="pager">
      <el-pagination v-model:current-page="query.pageNo" v-model:page-size="query.pageSize"
                     :total="total" layout="total, prev, pager, next" @current-change="load" />
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

    <el-drawer v-model="drawerVisible" :title="`科目 · ${currentProject?.name || ''}`" size="520px">
      <el-table :data="subjects" border size="small">
        <el-table-column prop="code" label="编码" width="120" />
        <el-table-column prop="name" label="名称" min-width="140" />
        <el-table-column prop="status" label="状态" width="90" />
      </el-table>
      <el-divider>新增科目</el-divider>
      <el-form label-width="80px">
        <el-form-item label="编码">
          <el-input v-model="subjectForm.code" placeholder="如 PMP-BASE" />
        </el-form-item>
        <el-form-item label="名称">
          <el-input v-model="subjectForm.name" />
        </el-form-item>
        <el-form-item label="排序">
          <el-input-number v-model="subjectForm.sortNo" :min="0" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="addSubject">添加</el-button>
        </el-form-item>
      </el-form>
    </el-drawer>
  </div>
</template>
