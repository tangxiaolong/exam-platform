<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { questionUsageApi, type QuestionUsageItem } from '@/api'
import ContentContextBar from '@/components/ContentContextBar.vue'
import { useContentStore } from '@/stores/content'

const content = useContentStore()

const loading = ref(false)
const list = ref<QuestionUsageItem[]>([])
const days = ref(90)
const typeFilter = ref('')
const sortBy = ref<'accuracy' | 'appeared' | 'rare'>('accuracy')

/** 样本太少的正确率没有参考价值，单独标出来，别让人照着 1/1 的题去改教材 */
const MIN_SAMPLE = 5

const typeLabel: Record<string, string> = {
  SINGLE: '单选题',
  MULTIPLE: '多选题',
  JUDGE: '判断题',
  ESSAY: '主观题'
}

const filtered = computed(() => {
  let rows = list.value
  if (typeFilter.value) {
    rows = rows.filter((r) => r.type === typeFilter.value)
  }
  const copy = [...rows]
  if (sortBy.value === 'accuracy') {
    copy.sort((a, b) => a.accuracy - b.accuracy || b.appearedCount - a.appearedCount)
  } else if (sortBy.value === 'appeared') {
    copy.sort((a, b) => b.appearedCount - a.appearedCount)
  } else {
    copy.sort((a, b) => a.appearedCount - b.appearedCount)
  }
  return copy
})

const stats = computed(() => {
  const rows = filtered.value
  const appeared = rows.reduce((sum, r) => sum + r.appearedCount, 0)
  const correct = rows.reduce((sum, r) => sum + r.correctCount, 0)
  const weak = rows.filter((r) => r.appearedCount >= MIN_SAMPLE && r.accuracy < 40).length
  return {
    count: rows.length,
    appeared,
    correct,
    accuracy: appeared ? (correct / appeared) * 100 : 0,
    weak
  }
})

async function load() {
  loading.value = true
  try {
    const params: Record<string, any> = { days: days.value, limit: 200 }
    if (content.subjectId) {
      params.subjectId = content.subjectId
    }
    list.value = (await questionUsageApi.analyze(params)) || []
  } finally {
    loading.value = false
  }
}

function onContextChange() {
  load()
}

function accuracyTag(row: QuestionUsageItem) {
  if (row.appearedCount < MIN_SAMPLE) return 'info'
  if (row.accuracy < 40) return 'danger'
  if (row.accuracy < 70) return 'warning'
  return 'success'
}

function exportCsv() {
  if (!filtered.value.length) {
    ElMessage.warning('当前没有可导出的数据')
    return
  }
  const header = ['题目ID', '题型', '题干', '出现次数', '答对次数', '正确率(%)']
  const lines = filtered.value.map((r) => [
    r.questionId,
    typeLabel[r.type] || r.type,
    (r.stem || '').replace(/"/g, '""'),
    r.appearedCount,
    r.correctCount,
    r.accuracy.toFixed(1)
  ])
  const csv = [header, ...lines]
    .map((cols) => cols.map((c) => `"${c}"`).join(','))
    .join('\r\n')
  // 带 BOM，否则 Excel 打开中文是乱码
  const blob = new Blob(['\uFEFF' + csv], { type: 'text/csv;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `题库使用分析_近${days.value}天.csv`
  a.click()
  URL.revokeObjectURL(url)
}
</script>

<template>
  <div class="page-card">
    <h3 class="page-title">题库使用分析</h3>
    <p class="page-desc">
      按最近 {{ days }} 天已判分答卷统计：每道题被抽中多少次、答对多少次、正确率多少。
      正确率最低的题排在最前——出现次数少于 {{ MIN_SAMPLE }} 次的题，结论仅供参考。
    </p>

    <ContentContextBar subject-optional @change="onContextChange" />

    <div class="stat-strip">
      <div class="stat-item">
        <div class="stat-value">{{ stats.count }}</div>
        <div class="stat-label">覆盖题目数</div>
      </div>
      <div class="stat-item">
        <div class="stat-value">{{ stats.appeared }}</div>
        <div class="stat-label">累计作答次数</div>
      </div>
      <div class="stat-item" :class="stats.accuracy < 60 ? 'is-warn' : 'is-ok'">
        <div class="stat-value">{{ stats.accuracy.toFixed(1) }}%</div>
        <div class="stat-label">整体正确率</div>
      </div>
      <div class="stat-item" :class="stats.weak ? 'is-danger' : ''">
        <div class="stat-value">{{ stats.weak }}</div>
        <div class="stat-label">薄弱题（正确率 &lt; 40%）</div>
      </div>
    </div>

    <div class="toolbar">
      <el-select v-model="days" style="width: 140px" @change="load">
        <el-option label="最近 30 天" :value="30" />
        <el-option label="最近 90 天" :value="90" />
        <el-option label="最近 180 天" :value="180" />
        <el-option label="最近 365 天" :value="365" />
      </el-select>
      <el-select v-model="typeFilter" placeholder="全部题型" style="width: 130px" clearable>
        <el-option v-for="(label, key) in typeLabel" :key="key" :label="label" :value="key" />
      </el-select>
      <el-select v-model="sortBy" style="width: 170px">
        <el-option label="正确率从低到高" value="accuracy" />
        <el-option label="作答次数最多" value="appeared" />
        <el-option label="作答次数最少" value="rare" />
      </el-select>
      <el-button type="primary" @click="load">刷新</el-button>
      <div class="spacer" />
      <el-button @click="exportCsv">导出 CSV</el-button>
    </div>

    <el-table v-loading="loading" :data="filtered" border stripe row-key="questionId">
      <el-table-column prop="questionId" label="ID" width="90" />
      <el-table-column label="题型" width="92">
        <template #default="{ row }">
          <el-tag effect="plain" size="small">{{ typeLabel[row.type] || row.type }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="stem" label="题干" min-width="260" show-overflow-tooltip />
      <el-table-column label="出现次数" width="110">
        <template #default="{ row }">
          <span :class="{ 'sample-thin': row.appearedCount < MIN_SAMPLE }">{{ row.appearedCount }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="correctCount" label="答对次数" width="110" />
      <el-table-column label="正确率" width="230">
        <template #default="{ row }">
          <div class="acc-cell">
            <el-progress :percentage="Math.min(100, Math.round(row.accuracy))" :stroke-width="10"
                         :status="row.appearedCount >= MIN_SAMPLE && row.accuracy < 40 ? 'exception' : undefined" />
            <el-tag v-if="row.appearedCount < MIN_SAMPLE" size="small" type="info" effect="plain">样本不足</el-tag>
            <el-tag v-else :type="accuracyTag(row)" size="small" effect="plain">
              {{ row.accuracy < 40 ? '需关注' : row.accuracy < 70 ? '一般' : '掌握好' }}
            </el-tag>
          </div>
        </template>
      </el-table-column>
      <template #empty>
        <div class="empty-wrap">
          <el-empty description="时间窗内暂无已判分的答卷数据——先完成几场考试再来看" :image-size="72" />
        </div>
      </template>
    </el-table>
  </div>
</template>

<style scoped>
.acc-cell {
  display: flex;
  align-items: center;
  gap: 8px;
}

.acc-cell :deep(.el-progress) {
  flex: 1;
}

.sample-thin {
  color: #b0b7c3;
}
</style>
