<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { configApi } from '@/api'

const loading = ref(false)
const items = ref<any[]>([])
const editVisible = ref(false)
const saving = ref(false)
const form = reactive({ key: '', value: '', remark: '' })

async function load() {
  loading.value = true
  try {
    items.value = await configApi.list()
  } finally {
    loading.value = false
  }
}

function openEdit(row: any) {
  form.key = row.configKey
  form.value = row.configValue
  form.remark = row.remark || ''
  editVisible.value = true
}

async function save() {
  saving.value = true
  try {
    await configApi.save(form.key, { value: form.value.trim(), remark: form.remark })
    ElMessage.success('已保存，规则立即生效')
    editVisible.value = false
    await load()
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<template>
  <div v-loading="loading">
    <div class="page-card">
      <h3 class="page-title">系统参数</h3>
      <p class="page-desc">
        sys_config 的在线编辑入口：保存即生效（读缓存同步失效），修改动作本身会写入审计日志。
      </p>
      <el-table :data="items" border stripe>
        <el-table-column prop="configKey" label="参数键" min-width="260" show-overflow-tooltip />
        <el-table-column prop="configValue" label="当前值" min-width="140" show-overflow-tooltip />
        <el-table-column label="说明" min-width="220" show-overflow-tooltip>
          <template #default="{ row }">{{ row.remark || '-' }}</template>
        </el-table-column>
        <el-table-column label="更新时间" min-width="160">
          <template #default="{ row }">{{ row.updatedAt || '-' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="90" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <el-dialog v-model="editVisible" title="编辑参数" width="480px">
      <el-form label-width="90px">
        <el-form-item label="参数键">
          <el-input v-model="form.key" disabled />
        </el-form-item>
        <el-form-item label="参数值">
          <el-input v-model="form.value" placeholder="新值" />
        </el-form-item>
        <el-form-item v-if="form.remark" label="说明">
          <el-input v-model="form.remark" disabled />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>
