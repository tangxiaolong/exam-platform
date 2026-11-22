<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { roleApi, type RoleItem } from '@/api'

const loading = ref(false)
const list = ref<RoleItem[]>([])

const createVisible = ref(false)
const creating = ref(false)
const form = reactive({ code: '', name: '', description: '' })

const permVisible = ref(false)
const permLoading = ref(false)
const permCode = ref('')
const permissions = ref<string[]>([])

async function load() {
  loading.value = true
  try {
    list.value = await roleApi.list()
  } finally {
    loading.value = false
  }
}

function openCreate() {
  form.code = ''
  form.name = ''
  form.description = ''
  createVisible.value = true
}

async function submitCreate() {
  if (!form.code.trim() || !form.name.trim()) {
    ElMessage.warning('角色编码与名称都不能为空')
    return
  }
  creating.value = true
  try {
    await roleApi.create({
      code: form.code.trim(),
      name: form.name.trim(),
      description: form.description.trim()
    })
    ElMessage.success('角色已创建')
    createVisible.value = false
    await load()
  } finally {
    creating.value = false
  }
}

async function removeRole(row: RoleItem) {
  // 内置角色后端会拒绝，这里先拦一层只是省一次往返；真正的兜底在后端
  if (row.builtIn) {
    ElMessage.warning('内置角色不允许删除')
    return
  }
  await ElMessageBox.confirm(
    `确认删除角色「${row.name}」？持有该角色的用户会失去对应权限。`,
    '提示',
    { type: 'warning' }
  )
  await roleApi.remove(row.code)
  ElMessage.success('已删除')
  await load()
}

async function viewPermissions(row: RoleItem) {
  permCode.value = row.code
  permVisible.value = true
  permLoading.value = true
  permissions.value = []
  try {
    permissions.value = await roleApi.permissions(row.code)
  } finally {
    permLoading.value = false
  }
}

const newPermission = ref('')
const granting = ref(false)

async function submitGrant() {
  const permission = newPermission.value.trim()
  if (!permission) {
    ElMessage.warning('请输入权限码')
    return
  }
  granting.value = true
  try {
    await roleApi.grant(permCode.value, permission)
    ElMessage.success('已授予')
    newPermission.value = ''
    // 重新拉取而不是本地 push：后端可能对权限码做了归一化或去重
    permissions.value = await roleApi.permissions(permCode.value)
  } finally {
    granting.value = false
  }
}

async function removePermission(permission: string) {
  await ElMessageBox.confirm(`确认取消权限「${permission}」？`, '提示', { type: 'warning' })
  await roleApi.revoke(permCode.value, permission)
  ElMessage.success('已取消')
  // 同样重新拉取：撤销可能同时影响其他上下文里的可见权限
  permissions.value = await roleApi.permissions(permCode.value)
}

onMounted(load)
</script>

<template>
  <div>
    <div style="margin-bottom: 16px;">
      <el-button type="primary" @click="openCreate">新建角色</el-button>
      <el-button @click="load">刷新</el-button>
    </div>

    <el-table v-loading="loading" :data="list" border>
      <el-table-column prop="code" label="角色编码" width="200" />
      <el-table-column prop="name" label="名称" width="200" />
      <el-table-column prop="description" label="说明" />
      <el-table-column label="操作" width="180">
        <template #default="{ row }">
          <el-button link type="primary" @click="viewPermissions(row)">权限</el-button>
          <el-button link type="danger" @click="removeRole(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="createVisible" title="新建角色" width="440px">
      <el-form label-width="80px">
        <el-form-item label="编码">
          <el-input v-model="form.code" placeholder="如 AUDITOR，创建后不可修改" />
        </el-form-item>
        <el-form-item label="名称">
          <el-input v-model="form.name" placeholder="如 审计员" />
        </el-form-item>
        <el-form-item label="说明">
          <el-input v-model="form.description" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="submitCreate">确定</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="permVisible" :title="`权限 · ${permCode}`" width="480px">
      <div v-loading="permLoading" style="min-height: 60px;">
        <el-tag v-for="p in permissions" :key="p" closable :disable-transitions="false"
                @close="removePermission(p)"
                style="margin: 0 6px 6px 0;">{{ p }}</el-tag>
        <div v-if="!permLoading && permissions.length === 0" style="color: #999;">该角色暂无权限</div>
      </div>

      <el-input v-model="newPermission" placeholder="输入权限码，如 exam:manage"
                style="margin-top: 16px;" @keyup.enter="submitGrant">
        <template #append>
          <el-button :loading="granting" @click="submitGrant">授予</el-button>
        </template>
      </el-input>

      <div style="margin-top: 8px; color: #999; font-size: 12px;">
        点标签上的 × 取消授权；可选权限码列表尚未开放，需手动输入
      </div>
    </el-dialog>
  </div>
</template>
