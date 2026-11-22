<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { knowledgeApi, type KnowledgePoint } from '@/api'
import ContentContextBar from '@/components/ContentContextBar.vue'
import { useContentStore } from '@/stores/content'

interface PointNode extends KnowledgePoint {
  questionCount: number
  children?: PointNode[]
}

const router = useRouter()
const content = useContentStore()

const loading = ref(false)
const points = ref<PointNode[]>([])
const keyword = ref('')
const noQuestionOnly = ref(false)

const editVisible = ref(false)
const editing = ref(false)
const editingId = ref<number | null>(null)
const form = reactive<KnowledgePoint>({ subjectId: 0, parentId: null, name: '', sortNo: 1 })

const coveredCount = computed(() => points.value.filter((p) => p.questionCount > 0).length)
const uncoveredCount = computed(() => points.value.filter((p) => p.questionCount === 0).length)

const visiblePoints = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  return points.value.filter((p) => {
    if (noQuestionOnly.value && p.questionCount > 0) return false
    if (kw && !p.name.toLowerCase().includes(kw)) return false
    return true
  })
})

/**
 * 平铺列表 → 树。
 *
 * <p>命中搜索时父节点可能被过滤掉，此时把子节点当根节点展示，而不是让它凭空消失——
 * 搜到的东西一定要能看见，层级只是辅助信息。</p>
 */
const tree = computed<PointNode[]>(() => {
  const nodes = new Map<number, PointNode>()
  visiblePoints.value.forEach((p) => nodes.set(p.id as number, { ...p, children: [] }))
  const roots: PointNode[] = []
  nodes.forEach((node) => {
    const parent = node.parentId ? nodes.get(node.parentId) : undefined
    if (parent) parent.children!.push(node)
    else roots.push(node)
  })
  const sort = (list: PointNode[]) => {
    list.sort((a, b) => (a.sortNo ?? 0) - (b.sortNo ?? 0) || (a.id ?? 0) - (b.id ?? 0))
    list.forEach((item) => item.children?.length && sort(item.children))
  }
  sort(roots)
  return roots
})

const parentOptions = computed(() => {
  // 不能挂到自己或自己的子孙下，否则成环；后端也会拒，但先在前端挡掉更省事
  if (!editingId.value) return points.value
  const banned = new Set<number>([editingId.value])
  let changed = true
  while (changed) {
    changed = false
    points.value.forEach((p) => {
      if (p.parentId && banned.has(p.parentId) && !banned.has(p.id as number)) {
        banned.add(p.id as number)
        changed = true
      }
    })
  }
  return points.value.filter((p) => !banned.has(p.id as number))
})

async function loadPoints() {
  if (!content.subjectId) {
    points.value = []
    return
  }
  loading.value = true
  try {
    const [list, coverage] = await Promise.all([
      knowledgeApi.list(content.subjectId),
      knowledgeApi.coverage(content.subjectId).catch(() => [] as any[])
    ])
    const countMap: Record<number, number> = {}
    ;(coverage || []).forEach((row: any) => {
      countMap[row.knowledge_point_id] = row.question_count
    })
    points.value = (list || []).map((p: any) => ({ ...p, questionCount: countMap[p.id] ?? 0 }))
  } finally {
    loading.value = false
  }
}

function onContextChange() {
  keyword.value = ''
  loadPoints()
}

function openCreate() {
  if (!content.subjectId) {
    ElMessage.warning('请先选择科目')
    return
  }
  editingId.value = null
  form.subjectId = content.subjectId
  form.parentId = null
  form.name = ''
  form.sortNo = points.value.length + 1
  editVisible.value = true
}

function openCreateChild(row: PointNode) {
  editingId.value = null
  form.subjectId = row.subjectId
  form.parentId = row.id ?? null
  form.name = ''
  form.sortNo = (points.value.filter((p) => p.parentId === row.id).length || 0) + 1
  editVisible.value = true
}

function openEdit(row: PointNode) {
  editingId.value = row.id || null
  form.subjectId = row.subjectId
  form.parentId = row.parentId ?? null
  form.name = row.name
  form.sortNo = row.sortNo ?? 1
  editVisible.value = true
}

async function submit() {
  if (!form.name.trim()) {
    ElMessage.warning('名称不能为空')
    return
  }
  editing.value = true
  try {
    const payload: KnowledgePoint = {
      subjectId: form.subjectId,
      parentId: form.parentId ?? null,
      name: form.name.trim(),
      sortNo: form.sortNo || 1
    }
    if (editingId.value) {
      await knowledgeApi.update(editingId.value, payload)
      ElMessage.success('已更新')
    } else {
      await knowledgeApi.create(payload)
      ElMessage.success('已创建')
    }
    editVisible.value = false
    await loadPoints()
  } finally {
    editing.value = false
  }
}

async function removeRow(row: PointNode) {
  await ElMessageBox.confirm(
    `确认删除知识点「${row.name}」？其下还有 ${row.children?.length || 0} 个子知识点，会一并失去归属。`,
    '删除知识点',
    { type: 'warning' }
  )
  await knowledgeApi.remove(row.id as number)
  ElMessage.success('已删除')
  await loadPoints()
}

function jumpToQuestions() {
  // 题库页没有按知识点筛选的参数，只能把项目/科目带过去
  router.push('/questions')
}
</script>

<template>
  <div class="page-card">
    <h3 class="page-title">知识点</h3>
    <p class="page-desc">
      知识点按科目维护，支持两级以上的父子层级；组卷时可按知识点抽题，所以「无题知识点」会直接导致抽不到题。
    </p>

    <ContentContextBar @change="onContextChange" />

    <div class="stat-strip">
      <div class="stat-item">
        <div class="stat-value">{{ points.length }}</div>
        <div class="stat-label">知识点总数</div>
      </div>
      <div class="stat-item is-ok">
        <div class="stat-value">{{ coveredCount }}</div>
        <div class="stat-label">已挂题目</div>
      </div>
      <div class="stat-item" :class="uncoveredCount ? 'is-danger' : ''">
        <div class="stat-value">{{ uncoveredCount }}</div>
        <div class="stat-label">无题知识点</div>
      </div>
    </div>

    <div class="toolbar">
      <el-input v-model="keyword" placeholder="知识点名称" style="width: 220px" clearable />
      <el-checkbox v-model="noQuestionOnly" label="仅看无题知识点" />
      <el-button type="primary" @click="loadPoints">刷新</el-button>
      <div class="spacer" />
      <el-button @click="jumpToQuestions">去题库</el-button>
      <el-button type="primary" :disabled="!content.subjectId" @click="openCreate">新增知识点</el-button>
    </div>

    <el-table v-loading="loading" :data="tree" border row-key="id" default-expand-all
              :tree-props="{ children: 'children' }">
      <el-table-column prop="name" label="名称" min-width="260">
        <template #default="{ row }">
          <span>{{ row.name }}</span>
          <el-tag v-if="row.children?.length" size="small" effect="plain" class="child-tag">
            {{ row.children.length }} 个子项
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="题库量" width="120">
        <template #default="{ row }">
          <el-tag v-if="row.questionCount > 0" type="success" effect="plain" size="small">
            {{ row.questionCount }} 道
          </el-tag>
          <el-tag v-else type="danger" effect="plain" size="small">无题</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="sortNo" label="排序" width="90" />
      <el-table-column label="操作" width="200" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openCreateChild(row)">加子项</el-button>
          <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button link type="danger" @click="removeRow(row)">删除</el-button>
        </template>
      </el-table-column>
      <template #empty>
        <div class="empty-wrap">
          <el-empty :description="content.subjectId
            ? '该科目下还没有知识点，先建一级章节再往下拆'
            : '请先在上方选择项目与科目'" :image-size="72" />
        </div>
      </template>
    </el-table>

    <p v-if="uncoveredCount > 0" class="warn-tip">
      有 {{ uncoveredCount }} 个知识点还没有挂题：按知识点抽题的规则抽到这里时会是 0 道，先补题或调整规则。
    </p>

    <el-dialog v-model="editVisible" :title="editingId ? '编辑知识点' : '新增知识点'" width="460px">
      <el-form label-width="80px">
        <el-form-item label="名称" required>
          <el-input v-model="form.name" placeholder="如：项目立项流程" />
        </el-form-item>
        <el-form-item label="上级">
          <el-select v-model="form.parentId" placeholder="不选则为一级节点" clearable filterable
                     style="width: 100%;">
            <el-option v-for="p in parentOptions" :key="p.id" :label="p.name" :value="p.id!" />
          </el-select>
        </el-form-item>
        <el-form-item label="排序">
          <el-input-number v-model="form.sortNo" :min="1" />
          <span class="form-hint">同级按此升序排列</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" :loading="editing" @click="submit">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.child-tag {
  margin-left: 8px;
}

.warn-tip {
  margin: 12px 0 0;
  font-size: 12px;
  color: #e6a23c;
}

.form-hint {
  margin-left: 10px;
  font-size: 12px;
  color: #909399;
}
</style>
