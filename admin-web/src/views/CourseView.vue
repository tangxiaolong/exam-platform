<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { courseApi, projectApi, type Course, type CourseLesson, type Project } from '@/api'

const loading = ref(false)
const list = ref<Course[]>([])
const total = ref(0)
const query = reactive({ pageNo: 1, pageSize: 10, keyword: '', status: '' })
const projects = ref<Project[]>([])

const dialogVisible = ref(false)
const editingId = ref<number | null>(null)
const form = reactive<Course>({
  code: '',
  title: '',
  summary: '',
  lecturer: '',
  priceFen: 0,
  accessType: 'FREE',
  status: 'DRAFT',
  sortNo: 0
})

const drawerVisible = ref(false)
const currentCourse = ref<Course | null>(null)
const lessons = ref<CourseLesson[]>([])
const lessonForm = reactive<CourseLesson>({ title: '', content: '', freePreview: 0, sortNo: 0 })

const accessLabel: Record<string, string> = { FREE: '免费', ENTITLED: '需购买' }
const statusLabel: Record<string, string> = { DRAFT: '草稿', PUBLISHED: '已发布', OFFLINE: '已下架' }

async function load() {
  loading.value = true
  try {
    const page = await courseApi.page(query)
    list.value = page.list
    total.value = page.total
  } finally {
    loading.value = false
  }
}

function money(fen?: number) {
  return '¥' + ((fen || 0) / 100).toFixed(2)
}

function openCreate() {
  editingId.value = null
  Object.assign(form, {
    projectId: projects.value[0]?.id,
    subjectId: undefined,
    code: '',
    title: '',
    summary: '',
    lecturer: '',
    priceFen: 0,
    accessType: 'FREE',
    status: 'DRAFT',
    sortNo: 0
  })
  dialogVisible.value = true
}

function openEdit(row: Course) {
  editingId.value = row.id ?? null
  Object.assign(form, row)
  dialogVisible.value = true
}

async function save() {
  if (!form.code.trim() || !form.title.trim()) return ElMessage.warning('课程编码与标题必填')
  if (editingId.value) {
    await courseApi.update(editingId.value, { ...form })
  } else {
    await courseApi.create({ ...form })
  }
  ElMessage.success('保存成功')
  dialogVisible.value = false
  load()
}

async function remove(row: Course) {
  await ElMessageBox.confirm(`确认删除课程「${row.title}」？`, '提示', { type: 'warning' })
  await courseApi.remove(row.id!)
  ElMessage.success('已删除')
  load()
}

async function openLessons(row: Course) {
  currentCourse.value = row
  lessons.value = await courseApi.lessons(row.id!)
  Object.assign(lessonForm, { title: '', content: '', freePreview: 0, sortNo: lessons.value.length })
  drawerVisible.value = true
}

async function addLesson() {
  if (!lessonForm.title.trim()) return ElMessage.warning('课时标题必填')
  await courseApi.createLesson(currentCourse.value!.id!, { ...lessonForm })
  ElMessage.success('课时已添加')
  lessons.value = await courseApi.lessons(currentCourse.value!.id!)
  Object.assign(lessonForm, { title: '', content: '', freePreview: 0, sortNo: lessons.value.length })
}

async function removeLesson(lesson: CourseLesson) {
  await ElMessageBox.confirm(`确认删除课时「${lesson.title}」？`, '提示', { type: 'warning' })
  await courseApi.removeLesson(lesson.id!)
  lessons.value = await courseApi.lessons(currentCourse.value!.id!)
  ElMessage.success('已删除')
}

onMounted(async () => {
  const page = await projectApi.page({ pageNo: 1, pageSize: 50 })
  projects.value = page.list
  await load()
})
</script>

<template>
  <div class="page-card">
    <h3 class="page-title">课程管理</h3>
    <p class="page-desc">
      免费课程登录即可观看；付费课程需购买对应商品（发放 COURSE 权益）后解锁，标记为试看的课时始终可见。
    </p>

    <div class="toolbar">
      <el-input v-model="query.keyword" placeholder="课程标题" style="width: 220px" clearable
                @keyup.enter="load" />
      <el-select v-model="query.status" placeholder="状态" style="width: 140px" clearable @change="load">
        <el-option v-for="(label, key) in statusLabel" :key="key" :label="label" :value="key" />
      </el-select>
      <el-button type="primary" @click="load">查询</el-button>
      <div class="spacer" />
      <el-button type="primary" @click="openCreate">新建课程</el-button>
    </div>

    <el-table v-loading="loading" :data="list" border stripe>
      <el-table-column prop="code" label="编码" width="180" />
      <el-table-column prop="title" label="课程标题" min-width="200" />
      <el-table-column prop="lecturer" label="讲师" width="130" />
      <el-table-column label="观看条件" width="110">
        <template #default="{ row }">
          <el-tag :type="row.accessType === 'FREE' ? 'success' : 'warning'" effect="plain">
            {{ accessLabel[row.accessType] || row.accessType }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="价格" width="100">
        <template #default="{ row }">{{ money(row.priceFen) }}</template>
      </el-table-column>
      <el-table-column prop="lessonCount" label="课时" width="80" />
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="row.status === 'PUBLISHED' ? 'success' : 'info'" effect="plain">
            {{ statusLabel[row.status] || row.status }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="200" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openLessons(row)">课时</el-button>
          <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button link type="danger" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <div class="pager">
      <el-pagination v-model:current-page="query.pageNo" v-model:page-size="query.pageSize"
                     :total="total" layout="total, prev, pager, next" @current-change="load" />
    </div>

    <el-dialog v-model="dialogVisible" :title="editingId ? '编辑课程' : '新建课程'" width="600px">
      <el-form label-width="100px">
        <el-form-item label="课程编码" required>
          <el-input v-model="form.code" placeholder="如 COURSE-PMP-PRO" />
        </el-form-item>
        <el-form-item label="课程标题" required>
          <el-input v-model="form.title" />
        </el-form-item>
        <el-form-item label="所属项目">
          <el-select v-model="form.projectId" style="width: 240px" clearable>
            <el-option v-for="p in projects" :key="p.id" :label="p.name" :value="p.id!" />
          </el-select>
        </el-form-item>
        <el-form-item label="课程简介">
          <el-input v-model="form.summary" type="textarea" :rows="3" />
        </el-form-item>
        <el-form-item label="讲师">
          <el-input v-model="form.lecturer" />
        </el-form-item>
        <el-form-item label="观看条件">
          <el-radio-group v-model="form.accessType">
            <el-radio-button label="FREE">免费</el-radio-button>
            <el-radio-button label="ENTITLED">需购买</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="展示价(分)">
          <el-input-number v-model="form.priceFen" :min="0" :step="100" />
          <span class="hint">实际成交价以商城商品为准</span>
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="form.status" style="width: 160px">
            <el-option v-for="(label, key) in statusLabel" :key="key" :label="label" :value="key" />
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

    <el-drawer v-model="drawerVisible" :title="`课时 · ${currentCourse?.title || ''}`" size="640px">
      <el-table :data="lessons" border size="small">
        <el-table-column prop="sortNo" label="序号" width="70" />
        <el-table-column prop="title" label="标题" min-width="160" />
        <el-table-column label="试看" width="80">
          <template #default="{ row }">
            <el-tag v-if="row.freePreview === 1" type="success" effect="plain" size="small">试看</el-tag>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="90">
          <template #default="{ row }">
            <el-button link type="danger" @click="removeLesson(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-divider>新增课时</el-divider>
      <el-form label-width="90px">
        <el-form-item label="标题">
          <el-input v-model="lessonForm.title" />
        </el-form-item>
        <el-form-item label="内容">
          <el-input v-model="lessonForm.content" type="textarea" :rows="3" />
        </el-form-item>
        <el-form-item label="视频地址">
          <el-input v-model="lessonForm.videoUrl" placeholder="对象存储 URL，可留空" />
        </el-form-item>
        <el-form-item label="可试看">
          <el-switch v-model="lessonForm.freePreview" :active-value="1" :inactive-value="0" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="addLesson">添加课时</el-button>
        </el-form-item>
      </el-form>
    </el-drawer>
  </div>
</template>

<style scoped>
.hint {
  margin-left: 10px;
  font-size: 12px;
  color: #9ca3af;
}

.muted {
  color: #9ca3af;
  font-size: 12px;
}
</style>
