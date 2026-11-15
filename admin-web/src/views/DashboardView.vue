<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { dashboardApi, type Overview } from '@/api'

const loading = ref(false)
const data = ref<Overview | null>(null)

const cards = [
  { key: 'userCount', label: '注册用户', icon: 'User', color: '#3b82f6' },
  { key: 'projectCount', label: '考试项目', icon: 'Folder', color: '#8b5cf6' },
  { key: 'questionCount', label: '题库题目', icon: 'Document', color: '#10b981' },
  { key: 'sessionCount', label: '考试场次', icon: 'AlarmClock', color: '#f59e0b' }
] as const

const cards2 = [
  { key: 'registrationCount', label: '报名人次', icon: 'Tickets' },
  { key: 'attemptCount', label: '考试记录', icon: 'EditPen' },
  { key: 'orderCount', label: '订单总数', icon: 'Wallet' },
  { key: 'paidOrderCount', label: '已支付订单', icon: 'CircleCheck' }
] as const

onMounted(async () => {
  loading.value = true
  try {
    data.value = await dashboardApi.overview()
  } finally {
    loading.value = false
  }
})

function money(fen?: number) {
  return '¥' + ((fen || 0) / 100).toFixed(2)
}
</script>

<template>
  <div v-loading="loading">
    <div class="grid">
      <div v-for="card in cards" :key="card.key" class="stat">
        <div class="stat-icon" :style="{ background: card.color + '1a', color: card.color }">
          <el-icon :size="20"><component :is="card.icon" /></el-icon>
        </div>
        <div>
          <div class="stat-value">{{ data?.[card.key] ?? 0 }}</div>
          <div class="stat-label">{{ card.label }}</div>
        </div>
      </div>
    </div>

    <el-row :gutter="16" style="margin-top: 16px">
      <el-col :span="14">
        <div class="page-card">
          <h3 class="page-title">运营指标</h3>
          <p class="page-desc">数据来自后端工作台聚合接口，金额单位为分。</p>
          <el-row :gutter="12">
            <el-col v-for="card in cards2" :key="card.key" :span="6">
              <div class="mini">
                <el-icon :size="16" class="mini-icon"><component :is="card.icon" /></el-icon>
                <div class="mini-value">{{ data?.[card.key] ?? 0 }}</div>
                <div class="mini-label">{{ card.label }}</div>
              </div>
            </el-col>
          </el-row>
          <el-divider />
          <div class="revenue">
            <div>
              <div class="revenue-label">累计成交金额</div>
              <div class="revenue-value">{{ money(data?.paidAmountFen) }}</div>
            </div>
            <el-tag type="success" effect="plain">已支付订单计入</el-tag>
          </div>
        </div>
      </el-col>
      <el-col :span="10">
        <div class="page-card">
          <h3 class="page-title">研发基线自检</h3>
          <p class="page-desc">当前工程已落地的核心能力。</p>
          <ul class="checks">
            <li>统一响应体 / traceId / 全局异常处理</li>
            <li>JWT 鉴权 + 管理端角色校验 + 租户 SQL 级隔离</li>
            <li>项目、科目、知识点、题库、试卷快照</li>
            <li>场次发布、报名、开考、答题保存、交卷、判分、成绩</li>
            <li>商品、订单、支付回调验签、权益幂等发放</li>
            <li>操作审计日志、自动交卷定时任务</li>
          </ul>
        </div>
      </el-col>
    </el-row>
  </div>
</template>

<style scoped>
.grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16px;
}

.stat {
  background: #fff;
  border-radius: 10px;
  padding: 18px;
  display: flex;
  gap: 14px;
  align-items: center;
  box-shadow: 0 1px 3px rgba(15, 23, 42, 0.06);
}

.stat-icon {
  width: 42px;
  height: 42px;
  border-radius: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.stat-value {
  font-size: 22px;
  font-weight: 700;
}

.stat-label {
  font-size: 12px;
  color: #8a94a6;
}

.mini {
  background: #f8fafc;
  border-radius: 8px;
  padding: 12px;
}

.mini-icon {
  color: #64748b;
}

.mini-value {
  font-size: 18px;
  font-weight: 600;
}

.mini-label {
  font-size: 12px;
  color: #8a94a6;
}

.revenue {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.revenue-label {
  font-size: 13px;
  color: #8a94a6;
}

.revenue-value {
  font-size: 26px;
  font-weight: 700;
  color: #16a34a;
}

.checks {
  margin: 0;
  padding-left: 18px;
  font-size: 13px;
  line-height: 2;
  color: #475569;
}
</style>
