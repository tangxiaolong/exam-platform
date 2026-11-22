<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { auditLogApi, type AuditLogView } from '@/api'

const loading = ref(false)
const list = ref<AuditLogView[]>([])
const total = ref(0)
const actions = ref<string[]>([])

const query = reactive({ pageNo: 1, pageSize: 10 })
const form = reactive({
  action: '',
  username: '',
  range: [] as string[]
})

async function load() {
  loading.value = true
  try {
    const params: any = { pageNo: query.pageNo, pageSize: query.pageSize }
    if (form.action) params.action = form.action
    if (form.username) params.username = form.username.trim()
    if (form.range && form.range.length === 2) {
      params.from = form.range[0]
      params.to = form.range[1]
    }
    const page = await auditLogApi.page(params)
    list.value = page.list
    total.value = page.total
  } finally {
    loading.value = false
  }
}

function search() {
  query.pageNo = 1
  load()
}

function reset() {
  form.action = ''
  form.username = ''
  form.range = []
  search()
}

/** 导出 CSV：筛选条件与列表页一致（后端同一套过滤逻辑，含日期开区间处理）。 */
async function exportCsv() {
  const params: any = {}
  if (form.action) params.action = form.action
  if (form.username) params.username = form.username.trim()
  if (form.range && form.range.length === 2) {
    params.from = form.range[0]
    params.to = form.range[1]
  }
  const blob = await auditLogApi.export(params)
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = `audit-logs-${Date.now()}.csv`
  link.click()
  URL.revokeObjectURL(url)
}

onMounted(async () => {
  // 动作列表取不到不影响主流程：下拉为空时运营仍可按账号与时间查
  try {
    actions.value = await auditLogApi.actions()
  } catch {
    actions.value = []
  }
  await load()
})
</script>

<template>
  <div class="page-card">
    <h3 class="page-title">审计日志</h3>
    <p class="page-desc">
      管理端高风险操作（发布/关闭场次、强制收卷、改分、退款审核、对账处置等）的落地记录。
      只读且不可删除——审计的价值在于「事后还能还原」，能改的记录等于没有记录。
    </p>

    <div class="toolbar">
      <el-select v-model="form.action" placeholder="全部动作" style="width: 220px" clearable>
        <el-option v-for="a in actions" :key="a" :label="a" :value="a" />
      </el-select>
      <el-input v-model="form.username" placeholder="操作人账号（支持模糊）" style="width: 200px"
                clearable @keyup.enter="search" />
      <el-date-picker v-model="form.range" type="daterange" value-format="YYYY-MM-DD"
                      start-placeholder="开始日期" end-placeholder="结束日期" style="width: 260px" />
      <el-button type="primary" @click="search">查询</el-button>
      <el-button @click="reset">重置</el-button>
      <el-button type="success" @click="exportCsv">导出 CSV</el-button>
    </div>

    <el-table v-loading="loading" :data="list" border stripe>
      <el-table-column prop="createdAt" label="时间" width="180" />
      <el-table-column prop="username" label="操作人" width="130" />
      <el-table-column prop="action" label="动作" width="180" show-overflow-tooltip />
      <el-table-column prop="target" label="对象" min-width="180" show-overflow-tooltip />
      <el-table-column prop="detail" label="详情" min-width="240" show-overflow-tooltip />
      <el-table-column prop="ip" label="来源 IP" width="140" />
    </el-table>

    <div class="pager">
      <el-pagination v-model:current-page="query.pageNo" v-model:page-size="query.pageSize"
                     :total="total" layout="total, prev, pager, next" @current-change="load" />
    </div>
  </div>
</template>
