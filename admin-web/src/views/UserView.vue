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

const rolesVisible = ref(false)
const rolesLoading = ref(false)
const currentUser = ref<any>(null)
const userRoles = ref<string[]>([])
const newRoleCode = ref('')

async function openRoles(row: any) {
  currentUser.value = row
  rolesVisible.value = true
  rolesLoading.value = true
  newRoleCode.value = ''
  try {
    userRoles.value = await userApi.roles(row.id)
  } finally {
    rolesLoading.value = false
  }
}

async function addRole() {
  const code = newRoleCode.value.trim()
  if (!code) {
    ElMessage.warning('请选择角色')
    return
  }
  await userApi.grantRole(currentUser.value.id, code)
  ElMessage.success('已绑定')
  newRoleCode.value = ''
  userRoles.value = await userApi.roles(currentUser.value.id)
}

async function removeRole(code: string) {
  await ElMessageBox.confirm(`确认移除角色「${code}」？`, '提示', { type: 'warning' })
  // 撤销自己的角色会被后端拦下（AUTH_SELF_REVOKE），提示由 request 拦截器统一弹出
  await userApi.revokeRole(currentUser.value.id, code)
  ElMessage.success('已移除')
  userRoles.value = await userApi.roles(currentUser.value.id)
}

async function revokeSessions(row: any) {
  await ElMessageBox.confirm(
    `确认让「${row.nickname || row.username}」的登录会话立即失效？对方需要重新登录。`,
    '提示',
    { type: 'warning' }
  )
  await userApi.revokeSessions(row.id)
  ElMessage.success('已强制下线')
}

const pwdVisible = ref(false)
const pwdSaving = ref(false)
const pwdUser = ref<any>(null)
const pwdValue = ref('')

function openResetPassword(row: any) {
  pwdUser.value = row
  pwdValue.value = ''
  pwdVisible.value = true
}

async function submitResetPassword() {
  const pwd = pwdValue.value.trim()
  if (pwd.length < 8) {
    ElMessage.warning('新密码至少 8 位')
    return
  }
  pwdSaving.value = true
  try {
    await userApi.resetPassword(pwdUser.value.id, pwd)
    // 后端同时作废了该用户的会话，所以这里要提示「重新登录」而不是「密码已改」
    ElMessage.success('已重置，该用户需用新密码重新登录')
    pwdVisible.value = false
  } finally {
    pwdSaving.value = false
  }
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
      <el-table-column label="操作" width="360" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openRoles(row)">角色</el-button>
          <el-button link type="primary" @click="openResetPassword(row)">重置密码</el-button>
          <el-button link type="warning" @click="revokeSessions(row)">强制下线</el-button>
          <el-button link type="danger" @click="toggleStatus(row)">禁用/启用</el-button>
        </template>
      </el-table-column>
    </el-table>

    <div class="pager">
      <el-pagination v-model:current-page="query.pageNo" v-model:page-size="query.pageSize"
                     :total="total" layout="total, prev, pager, next" @current-change="load" />
    </div>

    <el-dialog v-model="rolesVisible" :title="`角色 · ${currentUser?.username || ''}`" width="480px">
      <div v-loading="rolesLoading" style="min-height: 50px;">
        <el-tag v-for="r in userRoles" :key="r" closable :disable-transitions="false"
                @close="removeRole(r)"
                style="margin: 0 8px 8px 0;">{{ r }}</el-tag>
        <div v-if="!rolesLoading && userRoles.length === 0" style="color: #999;">该用户尚无角色</div>
      </div>

      <div style="display: flex; gap: 8px; margin-top: 16px;">
        <el-select v-model="newRoleCode" placeholder="选择角色" style="flex: 1;" clearable>
          <el-option label="平台管理员 (ADMIN)" value="ADMIN" />
          <el-option label="考生 (STUDENT)" value="STUDENT" />
        </el-select>
        <el-button type="primary" @click="addRole">绑定</el-button>
      </div>

      <div style="margin-top: 8px; color: #999; font-size: 12px;">
        点标签上的 × 解除绑定。撤销自己的角色会被后端拒绝——失去后台入口后连补救账号都没有。
      </div>
    </el-dialog>

    <el-dialog v-model="pwdVisible" :title="`重置密码 · ${pwdUser?.username || ''}`" width="440px">
      <el-form label-width="80px">
        <el-form-item label="新密码">
          <el-input v-model="pwdValue" type="password" placeholder="至少 8 位" show-password />
        </el-form-item>
      </el-form>
      <div style="color: #999; font-size: 12px;">
        保存后该用户的所有登录会话会立即失效，必须用新密码重新登录。
      </div>
      <template #footer>
        <el-button @click="pwdVisible = false">取消</el-button>
        <el-button type="primary" :loading="pwdSaving" @click="submitResetPassword">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>
