<script setup lang="ts">
import { onMounted } from 'vue'
import { useContentStore } from '@/stores/content'

/**
 * 内容运营统一的「项目 / 科目」选择条。
 *
 * <p>四个二级页面共用一份上下文：在这里选一次，切到别的页面还是这个项目、这个科目。
 * 放在页面标题下方、筛选条件上方——它决定的是「看哪个范围」，比筛选条件更靠前。</p>
 */
const props = withDefaults(
  defineProps<{
    /** 允许「全部科目」：题库、使用分析这类跨科目视图需要 */
    subjectOptional?: boolean
  }>(),
  { subjectOptional: false }
)

const emit = defineEmits<{ change: [] }>()
const store = useContentStore()

onMounted(async () => {
  await store.ensureReady()
  emit('change')
})

async function onProjectChange(id: number) {
  await store.selectProject(id)
  emit('change')
}

function onSubjectChange(id: number | null) {
  store.selectSubject(id ?? null)
  emit('change')
}
</script>

<template>
  <div class="ctx-bar">
    <span class="ctx-label">项目</span>
    <el-select :model-value="store.projectId" placeholder="选择项目" filterable
               style="width: 200px" :loading="store.loading" @change="onProjectChange">
      <el-option v-for="p in store.projects" :key="p.id" :label="p.name" :value="p.id!" />
    </el-select>

    <span class="ctx-label">科目</span>
    <el-select :model-value="store.subjectId" placeholder="全部科目" filterable
               :clearable="props.subjectOptional" :disabled="!store.projectId"
               style="width: 200px" @change="onSubjectChange">
      <el-option v-for="s in store.subjects" :key="s.id" :label="s.name" :value="s.id!" />
    </el-select>

    <slot />

    <div class="ctx-spacer" />
    <span v-if="!store.hasProjects" class="ctx-hint">还没有项目，先去「考试项目」新建一个</span>
    <span v-else-if="!store.hasSubjects" class="ctx-hint">该项目下还没有科目，去「考试项目」的科目里添加</span>
    <span v-else class="ctx-path">
      {{ store.projectName }}<template v-if="store.subjectName"> / {{ store.subjectName }}</template>
    </span>
  </div>
</template>

<style scoped>
.ctx-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  padding: 10px 14px;
  margin-bottom: 14px;
  background: #f7f9fc;
  border: 1px solid #e8eef7;
  border-radius: 8px;
}

.ctx-label {
  font-size: 13px;
  color: #64748b;
}

.ctx-spacer {
  flex: 1;
}

.ctx-path {
  font-size: 12px;
  color: #94a3b8;
  padding-right: 2px;
}

.ctx-hint {
  font-size: 12px;
  color: #e6a23c;
}
</style>
