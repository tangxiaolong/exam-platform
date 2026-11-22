<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { noticeApi, type NoticeItem } from '@/api'

const loading = ref(false)
const all = ref<NoticeItem[]>([])
const keyword = ref('')
const statusTab = ref('')
const pageNo = ref(1)
const pageSize = ref(10)

const editVisible = ref(false)
const saving = ref(false)
const editingId = ref<number | null>(null)
const form = reactive({
  title: '',
  content: '',
  pinned: false,
  type: 'NOTICE',
  publishAt: '' as string | null,
  expireAt: '' as string | null
})

const previewVisible = ref(false)
const previewRow = ref<NoticeItem | null>(null)

const statusTabs = computed(() => [
  { key: '', label: '全部', count: all.value.length },
  { key: 'DRAFT', label: '草稿', count: all.value.filter((n) => (n.status || 'DRAFT') === 'DRAFT').length },
  { key: 'PUBLISHED', label: '已发布', count: all.value.filter((n) => n.status === 'PUBLISHED').length },
  { key: 'ARCHIVED', label: '已归档', count: all.value.filter((n) => n.status === 'ARCHIVED').length }
])

const filtered = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  return all.value.filter((n) => {
    if (statusTab.value && (n.status || 'DRAFT') !== statusTab.value) return false
    if (kw && !(n.title || '').toLowerCase().includes(kw) && !(n.content || '').toLowerCase().includes(kw)) {
      return false
    }
    return true
  })
})

/** 后端管理端列表按创建时间倒序返回；置顶的还要提到最前——考生端就是这么排的 */
const sortedFiltered = computed(() =>
  [...filtered.value].sort((a, b) => {
    const pinnedDiff = (b.pinned || 0) - (a.pinned || 0)
    if (pinnedDiff !== 0) return pinnedDiff
    return (b.createdAt || '').localeCompare(a.createdAt || '')
  })
)

// 先排序再切页：只在页内排序会让第 2 页的置顶公告排不到第 1 页去
const paged = computed(() => {
  const start = (pageNo.value - 1) * pageSize.value
  return sortedFiltered.value.slice(start, start + pageSize.value)
})

const reachLimit = computed(() => all.value.length >= 200)

async function load() {
  loading.value = true
  try {
    // 公告量级很小，一次取够后在本地做状态分组/搜索/分页，比每次切 tab 都打一次接口快得多
    all.value = (await noticeApi.page({ pageNo: 1, pageSize: 200 })) || []
    if ((pageNo.value - 1) * pageSize.value >= filtered.value.length) {
      pageNo.value = 1
    }
  } finally {
    loading.value = false
  }
}

function switchTab(key: string) {
  statusTab.value = key
  pageNo.value = 1
}

function openCreate() {
  editingId.value = null
  Object.assign(form, { title: '', content: '', pinned: false, type: 'NOTICE', publishAt: '', expireAt: '' })
  editVisible.value = true
}

function openEdit(row: NoticeItem) {
  editingId.value = row.id || null
  Object.assign(form, {
    title: row.title || '',
    content: row.content || '',
    pinned: row.pinned === 1,
    type: row.type || 'NOTICE',
    // 日期选择器要的是字符串；null 会让控件回显成空
    publishAt: row.publishAt || '',
    expireAt: row.expireAt || ''
  })
  editVisible.value = true
}

function openPreview(row: NoticeItem) {
  previewRow.value = row
  previewVisible.value = true
}

async function submit() {
  if (!form.title.trim() || !form.content.trim()) {
    ElMessage.warning('标题与正文不能为空')
    return
  }
  if (form.publishAt && form.expireAt && form.expireAt <= form.publishAt) {
    ElMessage.warning('过期时间要晚于发布时间')
    return
  }
  saving.value = true
  try {
    const payload = {
      title: form.title.trim(),
      content: form.content,
      pinned: form.pinned,
      type: form.type,
      publishAt: form.publishAt || null,
      expireAt: form.expireAt || null
    }
    if (editingId.value) {
      await noticeApi.update(editingId.value, payload)
      ElMessage.success('已保存')
    } else {
      await noticeApi.create(payload)
      ElMessage.success('已创建草稿，需发布后考生才可见')
    }
    editVisible.value = false
    await load()
  } finally {
    saving.value = false
  }
}

async function publish(row: NoticeItem) {
  const reissue = row.status === 'ARCHIVED'
  await ElMessageBox.confirm(
    reissue
      ? `确认重新发布公告「${row.title}」？归档的公告会重新出现在考生端。`
      : `确认发布公告「${row.title}」？发布后考生端立即可见。`,
    reissue ? '重新发布' : '发布',
    { type: 'info' }
  )
  await noticeApi.publish(row.id as number)
  ElMessage.success(reissue ? '已重新发布' : '已发布')
  await load()
}

async function archive(row: NoticeItem) {
  await ElMessageBox.confirm(`确认归档公告「${row.title}」？归档后考生端不再展示，记录仍可查。`, '归档', {
    type: 'warning'
  })
  await noticeApi.archive(row.id as number)
  ElMessage.success('已归档')
  await load()
}

async function removeRow(row: NoticeItem) {
  await ElMessageBox.confirm(`确认删除公告「${row.title}」？此操作不可恢复。`, '删除', { type: 'warning' })
  await noticeApi.remove(row.id as number)
  ElMessage.success('已删除')
  await load()
}

function statusText(status?: string) {
  if (status === 'PUBLISHED') return '已发布'
  if (status === 'ARCHIVED') return '已归档'
  return '草稿'
}

function statusType(status?: string) {
  if (status === 'PUBLISHED') return 'success'
  if (status === 'ARCHIVED') return 'info'
  return 'warning'
}

onMounted(load)
</script>

<template>
  <div class="page-card">
    <h3 class="page-title">公告管理</h3>
    <p class="page-desc">
      新建默认是草稿，发布后考生端才可见；归档只是从考生端撤下，记录仍可查，也可以重新发布。
    </p>

    <div class="tabs">
      <span v-for="tab in statusTabs" :key="tab.key" class="tab"
            :class="{ active: statusTab === tab.key }" @click="switchTab(tab.key)">
        {{ tab.label }}<em>{{ tab.count }}</em>
      </span>
    </div>

    <div class="toolbar">
      <el-input v-model="keyword" placeholder="标题 / 正文关键字" style="width: 240px" clearable
                @input="pageNo = 1" />
      <el-button @click="load">刷新</el-button>
      <div class="spacer" />
      <el-button type="primary" @click="openCreate">新建公告</el-button>
    </div>

    <el-table v-loading="loading" :data="paged" border stripe row-key="id">
      <el-table-column label="标题" min-width="240">
        <template #default="{ row }">
          <el-tag v-if="row.pinned === 1" type="danger" size="small" class="pin-tag">置顶</el-tag>
          <el-button link type="primary" class="title-link" @click="openPreview(row)">
            {{ row.title }}
          </el-button>
        </template>
      </el-table-column>
      <el-table-column label="类型" width="90">
        <template #default="{ row }">
          <el-tag :type="row.type === 'ACTIVITY' ? 'warning' : 'info'" size="small" effect="plain">
            {{ row.type === 'ACTIVITY' ? '活动' : '公告' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="statusType(row.status)" effect="plain" size="small">{{ statusText(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="发布时间" width="165">
        <template #default="{ row }">{{ row.publishAt || '—' }}</template>
      </el-table-column>
      <el-table-column label="过期时间" width="165">
        <template #default="{ row }">{{ row.expireAt || '—' }}</template>
      </el-table-column>
      <el-table-column label="创建时间" width="165">
        <template #default="{ row }">{{ row.createdAt || '—' }}</template>
      </el-table-column>
      <el-table-column label="操作" width="230" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openPreview(row)">预览</el-button>
          <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button v-if="row.status === 'DRAFT'" link type="success" @click="publish(row)">发布</el-button>
          <el-button v-else-if="row.status === 'ARCHIVED'" link type="success" @click="publish(row)">
            重新发布
          </el-button>
          <el-button v-else link type="warning" @click="archive(row)">归档</el-button>
          <el-button link type="danger" @click="removeRow(row)">删除</el-button>
        </template>
      </el-table-column>
      <template #empty>
        <div class="empty-wrap">
          <el-empty :description="keyword ? '没有匹配的公告' : '还没有公告，新建一条试试'" :image-size="72" />
        </div>
      </template>
    </el-table>

    <div class="pager">
      <el-pagination v-model:current-page="pageNo" v-model:page-size="pageSize"
                     :total="sortedFiltered.length" :page-sizes="[10, 20, 50]"
                     layout="total, sizes, prev, pager, next" />
    </div>

    <p v-if="reachLimit" class="limit-tip">
      已加载最近 200 条公告，更早的记录未包含在本次结果里。
    </p>

    <el-dialog v-model="editVisible" :title="editingId ? '编辑公告' : '新建公告'" width="640px">
      <el-form label-width="90px">
        <el-form-item label="标题" required>
          <el-input v-model="form.title" placeholder="考生端列表显示的就是这一行" />
        </el-form-item>
        <el-form-item label="正文" required>
          <el-input v-model="form.content" type="textarea" :rows="8" />
        </el-form-item>
        <el-form-item label="类型">
          <el-select v-model="form.type" style="width: 200px;">
            <el-option label="公告" value="NOTICE" />
            <el-option label="活动" value="ACTIVITY" />
          </el-select>
          <span class="form-hint">活动会展示在小程序首页「活动」区块</span>
        </el-form-item>
        <el-form-item label="置顶">
          <el-switch v-model="form.pinned" />
          <span class="form-hint">置顶的公告排在列表最前</span>
        </el-form-item>
        <el-form-item label="发布时间">
          <div style="width: 100%;">
            <el-date-picker v-model="form.publishAt" type="datetime" value-format="YYYY-MM-DD HH:mm:ss"
                            placeholder="留空表示发布时立即生效" style="width: 100%;" />
            <div class="form-hint block">填未来时间则到点自动发布，不需要再手动点「发布」。</div>
          </div>
        </el-form-item>
        <el-form-item label="过期时间">
          <el-date-picker v-model="form.expireAt" type="datetime" value-format="YYYY-MM-DD HH:mm:ss"
                          placeholder="留空表示长期有效" style="width: 100%;" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submit">保存</el-button>
      </template>
    </el-dialog>

    <el-drawer v-model="previewVisible" title="考生端预览" size="460px">
      <div v-if="previewRow" class="preview">
        <div class="preview-head">
          <el-tag v-if="previewRow.pinned === 1" type="danger" size="small">置顶</el-tag>
          <el-tag :type="previewRow.type === 'ACTIVITY' ? 'warning' : 'info'" size="small" effect="plain">
            {{ previewRow.type === 'ACTIVITY' ? '活动' : '公告' }}
          </el-tag>
          <el-tag :type="statusType(previewRow.status)" size="small" effect="plain">
            {{ statusText(previewRow.status) }}
          </el-tag>
        </div>
        <h4 class="preview-title">{{ previewRow.title }}</h4>
        <div class="preview-meta">
          {{ previewRow.publishAt || previewRow.createdAt || '—' }}
        </div>
        <div class="preview-body">{{ previewRow.content }}</div>
        <el-alert v-if="previewRow.status !== 'PUBLISHED'" type="warning" show-icon :closable="false"
                  class="preview-alert"
                  title="当前状态考生端看不到这条公告，发布后才会出现在列表里。" />
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
.tabs {
  display: flex;
  gap: 4px;
  margin-bottom: 14px;
  border-bottom: 1px solid #eef2f7;
}

.tab {
  padding: 8px 14px;
  font-size: 14px;
  color: #64748b;
  cursor: pointer;
  border-bottom: 2px solid transparent;
  margin-bottom: -1px;
}

.tab em {
  font-style: normal;
  margin-left: 6px;
  font-size: 12px;
  color: #94a3b8;
  font-variant-numeric: tabular-nums;
}

.tab:hover {
  color: #409eff;
}

.tab.active {
  color: #409eff;
  border-bottom-color: #409eff;
  font-weight: 600;
}

.tab.active em {
  color: #409eff;
}

.pin-tag {
  margin-right: 6px;
}

.title-link {
  font-weight: 500;
}

.form-hint {
  margin-left: 8px;
  font-size: 12px;
  color: #909399;
}

.form-hint.block {
  display: block;
  margin: 4px 0 0;
}

.limit-tip {
  margin: 10px 0 0;
  font-size: 12px;
  color: #94a3b8;
}

.preview-head {
  display: flex;
  gap: 8px;
  margin-bottom: 12px;
}

.preview-title {
  margin: 0 0 6px;
  font-size: 18px;
  line-height: 1.4;
}

.preview-meta {
  font-size: 12px;
  color: #94a3b8;
  margin-bottom: 16px;
}

.preview-body {
  font-size: 14px;
  line-height: 1.8;
  color: #374151;
  white-space: pre-wrap;
}

.preview-alert {
  margin-top: 20px;
}
</style>
