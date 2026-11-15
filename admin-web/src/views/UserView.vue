<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { userApi, type UserView } from '@/api'

const loading = ref(false)
const list = ref<UserView[]>([])
const total = ref(0)
const query = reactive({ pageNo: 1, pageSize: 10, keyword: '', roleCode: '' })

async function load() {
  loading.value = true
  try {
    const page = await userApi.page(query)
    list.value = page.list
    total.value = page.total
  } finally {
    loading.value = false
  }
}

async function toggleStatus(row: any) {
  const next = row.status === 'DISABLED' ? 'ENABLED' : 'DISABLED'
  const text = next === 'DISABLED' ? '禁用' : '启用'
  await ElMessageBox.confirm(`确认${text}用户「${row.nickname || row.username}」？`, '提示', {
    type: 'warning'
  })
  await userApi.changeStatus(row.id, next)
  ElMessage.success('已更新')
  load()
}

onMounted(load)
</script>

<template>
  <div class="page-card">
    <h3 class="page-title">用户管理</h3>
    <p class="page-desc">管理员与考生共用用户表；角色以 roleCode 表达（ADMIN / STUDENT）。</p>

    <div class="toolbar">
      <el-input v-model="query.keyword" placeholder="账号/昵称" style="width: 220px" clearable
                @keyup.enter="load" />
      <el-select v-model="query.roleCode" placeholder="角色" style="width: 150px" clearable>
        <el-option label="平台管理员" value="ADMIN" />
        <el-option label="考生" value="STUDENT" />
      </el-select>
      <el-button type="primary" @click="load">查询</el-button>
    </div>

    <el-table v-loading="loading" :data="list" border stripe>
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column prop="username" label="账号" min-width="180" />
      <el-table-column prop="nickname" label="昵称" min-width="140" />
      <el-table-column prop="roleCode" label="角色" width="110" />
      <el-table-column prop="tenantId" label="租户" width="90" />
      <el-table-column label="操作" width="140" fixed="right">
        <template #default="{ row }">
          <el-button link type="danger" @click="toggleStatus(row)">禁用/启用</el-button>
        </template>
      </el-table-column>
    </el-table>

    <div class="pager">
      <el-pagination v-model:current-page="query.pageNo" v-model:page-size="query.pageSize"
                     :total="total" layout="total, prev, pager, next" @current-change="load" />
    </div>
  </div>
</template>
