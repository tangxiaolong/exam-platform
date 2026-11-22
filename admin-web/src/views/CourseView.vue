<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { courseApi, projectApi, type Course, type CourseLesson } from '@/api'
import { useContentStore } from '@/stores/content'

const content = useContentStore()

const loading = ref(false)
const list = ref<Course[]>([])
const total = ref(0)
const query = reactive({ pageNo: 1, pageSize: 10, keyword: '', status: '' })

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

const accessLabel: Record<string, string> = { FREE: '免费', ENTITLED: '需购买' }
const statusLabel: Record<string, string> = { DRAFT: '草稿', PUBLISHED: '已发布', OFFLINE: '已下架' }

/** 表单里按「元」填，落到后端仍是「分」；直接让人填分是绝大多数价格填错的原因 */
const priceYuan = computed({
  get: () => Number(((form.priceFen || 0) / 100).toFixed(2)),
  set: (value: number) => {
    form.priceFen = Math.round((value || 0) * 100)
  }
})

const formSubjects = ref<{ id?: number; name: string }[]>([])

const drawerVisible = ref(false)
const currentCourse = ref<Course | null>(null)
const lessons = ref<CourseLesson[]>([])
const lessonLoading = ref(false)

const lessonDialogVisible = ref(false)
const editingLessonId = ref<number | null>(null)
const lessonForm = reactive<CourseLesson>({
  title: '',
  content: '',
  videoUrl: '',
  durationSeconds: 0,
  freePreview: 0,
  sortNo: 0,
  status: 'ENABLED'
})

function money(fen?: number) {
  return '¥' + ((fen || 0) / 100).toFixed(2)
}

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

function resetQuery() {
  Object.assign(query, { pageNo: 1, keyword: '', status: '' })
  load()
}

async function loadSubjectsOfProject(projectId?: number) {
  if (!projectId) {
    formSubjects.value = []
    return
  }
  if (projectId === content.projectId && content.subjects.length) {
    formSubjects.value = content.subjects
    return
  }
  // 课程可能挂到别的项目下，科目下拉必须按当前选的项目现拉，不能复用上下文里的那一份
  formSubjects.value = (await projectApi.subjects(projectId)) || []
}

async function onFormProjectChange(id?: number) {
  form.subjectId = undefined
  await loadSubjectsOfProject(id)
}

function openCreate() {
  editingId.value = null
  Object.assign(form, {
    projectId: content.projectId ?? undefined,
    subjectId: content.subjectId ?? undefined,
    code: '',
    title: '',
    summary: '',
    lecturer: '',
    priceFen: 0,
    accessType: 'FREE',
    status: 'DRAFT',
    sortNo: 0
  })
  loadSubjectsOfProject(form.projectId)
  dialogVisible.value = true
}

function openEdit(row: Course) {
  editingId.value = row.id ?? null
  Object.assign(form, row)
  loadSubjectsOfProject(row.projectId)
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
  await ElMessageBox.confirm(`确认删除课程「${row.title}」？已购学员的权益不会自动回收。`, '删除课程', {
    type: 'warning'
  })
  await courseApi.remove(row.id!)
  ElMessage.success('已删除')
  load()
}

async function loadLessons() {
  if (!currentCourse.value?.id) return
  lessonLoading.value = true
  try {
    lessons.value = await courseApi.lessons(currentCourse.value.id)
  } finally {
    lessonLoading.value = false
  }
}

async function openLessons(row: Course) {
  currentCourse.value = row
  drawerVisible.value = true
  await loadLessons()
}

function openLessonCreate() {
  editingLessonId.value = null
  Object.assign(lessonForm, {
    title: '',
    content: '',
    videoUrl: '',
    durationSeconds: 0,
    freePreview: 0,
    sortNo: lessons.value.length + 1,
    status: 'ENABLED'
  })
  lessonDialogVisible.value = true
}

function openLessonEdit(lesson: CourseLesson) {
  editingLessonId.value = lesson.id ?? null
  Object.assign(lessonForm, {
    title: lesson.title,
    content: lesson.content || '',
    videoUrl: lesson.videoUrl || '',
    durationSeconds: lesson.durationSeconds || 0,
    freePreview: lesson.freePreview ?? 0,
    sortNo: lesson.sortNo ?? 0,
    status: lesson.status || 'ENABLED'
  })
  lessonDialogVisible.value = true
}

async function saveLesson() {
  if (!lessonForm.title.trim()) return ElMessage.warning('课时标题必填')
  if (editingLessonId.value) {
    await courseApi.updateLesson(editingLessonId.value, { ...lessonForm })
    ElMessage.success('课时已更新')
  } else {
    await courseApi.createLesson(currentCourse.value!.id!, { ...lessonForm })
    ElMessage.success('课时已添加')
  }
  lessonDialogVisible.value = false
  await loadLessons()
  load()
}

async function removeLesson(lesson: CourseLesson) {
  await ElMessageBox.confirm(`确认删除课时「${lesson.title}」？`, '删除课时', { type: 'warning' })
  await courseApi.removeLesson(lesson.id!)
  await loadLessons()
  load()
  ElMessage.success('已删除')
}

function durationText(seconds?: number) {
  if (!seconds) return '—'
  const m = Math.floor(seconds / 60)
  const s = seconds % 60
  return `${m}:${String(s).padStart(2, '0')}`
}

onMounted(async () => {
  await content.ensureReady()
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
      <el-input v-model="query.keyword" placeholder="课程标题 / 编码" style="width: 220px" clearable
                @keyup.enter="load" />
      <el-select v-model="query.status" placeholder="状态" style="width: 140px" clearable @change="load">
        <el-option v-for="(label, key) in statusLabel" :key="key" :label="label" :value="key" />
      </el-select>
      <el-button type="primary" @click="load">查询</el-button>
      <el-button @click="resetQuery">重置</el-button>
      <div class="spacer" />
      <el-button type="primary" @click="openCreate">新建课程</el-button>
    </div>

    <el-table v-loading="loading" :data="list" border stripe row-key="id">
      <el-table-column prop="code" label="编码" width="170" />
      <el-table-column label="课程标题" min-width="200">
        <template #default="{ row }">
          <el-button link type="primary" @click="openLessons(row)">{{ row.title }}</el-button>
        </template>
      </el-table-column>
      <el-table-column label="所属项目" width="150" show-overflow-tooltip>
        <template #default="{ row }">
          {{ row.projectId ? content.projectMap.get(row.projectId) || '—' : '—' }}
        </template>
      </el-table-column>
      <el-table-column prop="lecturer" label="讲师" width="120" />
      <el-table-column label="观看条件" width="104">
        <template #default="{ row }">
          <el-tag :type="row.accessType === 'FREE' ? 'success' : 'warning'" effect="plain" size="small">
            {{ accessLabel[row.accessType || 'FREE'] }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="价格" width="96">
        <template #default="{ row }">{{ money(row.priceFen) }}</template>
      </el-table-column>
      <el-table-column prop="lessonCount" label="课时" width="76" />
      <el-table-column label="状态" width="96">
        <template #default="{ row }">
          <el-tag :type="row.status === 'PUBLISHED' ? 'success' : row.status === 'OFFLINE' ? 'info' : 'warning'"
                  effect="plain" size="small">
            {{ statusLabel[row.status || 'DRAFT'] }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="180" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openLessons(row)">课时</el-button>
          <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button link type="danger" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
      <template #empty>
        <div class="empty-wrap">
          <el-empty description="还没有课程，新建一门试试" :image-size="72" />
        </div>
      </template>
    </el-table>

    <div class="pager">
      <el-pagination v-model:current-page="query.pageNo" v-model:page-size="query.pageSize"
                     :total="total" :page-sizes="[10, 20, 50]"
                     layout="total, sizes, prev, pager, next" @current-change="load"
                     @size-change="load" />
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
          <el-select v-model="form.projectId" style="width: 240px" clearable
                     @change="onFormProjectChange">
            <el-option v-for="p in content.projects" :key="p.id" :label="p.name" :value="p.id!" />
          </el-select>
        </el-form-item>
        <el-form-item label="所属科目">
          <el-select v-model="form.subjectId" style="width: 240px" clearable
                     :disabled="!form.projectId" placeholder="可不选">
            <el-option v-for="s in formSubjects" :key="s.id" :label="s.name" :value="s.id!" />
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
        <el-form-item label="展示价">
          <el-input-number v-model="priceYuan" :min="0" :step="10" :precision="2" style="width: 160px" />
          <span class="form-hint">单位：元。实际成交价以商城商品为准</span>
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
      <div class="drawer-head">
        <span class="drawer-sub">
          共 {{ lessons.length }} 个课时，标记为试看的课时无需购买即可观看。
        </span>
        <el-button type="primary" size="small" @click="openLessonCreate">新增课时</el-button>
      </div>

      <el-table v-loading="lessonLoading" :data="lessons" border size="small" row-key="id">
        <el-table-column prop="sortNo" label="序号" width="70" />
        <el-table-column prop="title" label="标题" min-width="150" show-overflow-tooltip />
        <el-table-column label="时长" width="80">
          <template #default="{ row }">{{ durationText(row.durationSeconds) }}</template>
        </el-table-column>
        <el-table-column label="试看" width="76">
          <template #default="{ row }">
            <el-tag v-if="row.freePreview === 1" type="success" effect="plain" size="small">试看</el-tag>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="130">
          <template #default="{ row }">
            <el-button link type="primary" @click="openLessonEdit(row)">编辑</el-button>
            <el-button link type="danger" @click="removeLesson(row)">删除</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <div class="empty-wrap">
            <el-empty description="还没有课时，点右上角新增" :image-size="60" />
          </div>
        </template>
      </el-table>
    </el-drawer>

    <el-dialog v-model="lessonDialogVisible" :title="editingLessonId ? '编辑课时' : '新增课时'" width="520px">
      <el-form label-width="90px">
        <el-form-item label="标题" required>
          <el-input v-model="lessonForm.title" />
        </el-form-item>
        <el-form-item label="内容">
          <el-input v-model="lessonForm.content" type="textarea" :rows="3" />
        </el-form-item>
        <el-form-item label="视频地址">
          <el-input v-model="lessonForm.videoUrl" placeholder="对象存储 URL，可留空" />
        </el-form-item>
        <el-form-item label="时长(秒)">
          <el-input-number v-model="lessonForm.durationSeconds" :min="0" :step="60" />
        </el-form-item>
        <el-form-item label="可试看">
          <el-switch v-model="lessonForm.freePreview" :active-value="1" :inactive-value="0" />
        </el-form-item>
        <el-form-item label="排序">
          <el-input-number v-model="lessonForm.sortNo" :min="0" />
          <span class="form-hint">按此升序排课时</span>
        </el-form-item>
        <el-form-item label="状态">
          <el-radio-group v-model="lessonForm.status">
            <el-radio-button label="ENABLED">启用</el-radio-button>
            <el-radio-button label="DISABLED">停用</el-radio-button>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="lessonDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="saveLesson">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.form-hint {
  margin-left: 10px;
  font-size: 12px;
  color: #9ca3af;
}

.muted {
  color: #9ca3af;
  font-size: 12px;
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
