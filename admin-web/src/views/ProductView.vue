<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { productApi, type ProductItem } from '@/api'

const list = ref<ProductItem[]>([])
const total = ref(0)
const loading = ref(false)
const query = ref({ pageNo: 1, pageSize: 10, keyword: '', status: '' })

const editVisible = ref(false)
const editing = ref(false)
const editingId = ref<number | null>(null)
const form = reactive({
  code: '',
  name: '',
  type: 'MEMBER',
  description: '',
  coverUrl: '',
  priceYuan: 0,
  originalYuan: 0,
  validityDays: 0,
  resourceId: 0,
  status: 'ON_SALE',
  sortNo: 1
})

async function load() {
  loading.value = true
  try {
    const params: Record<string, any> = {
      pageNo: query.value.pageNo,
      pageSize: query.value.pageSize
    }
    if (query.value.keyword) {
      params.keyword = query.value.keyword
    }
    if (query.value.status) {
      params.status = query.value.status
    }
    const page = await productApi.page(params)
    list.value = page.list
    total.value = page.total
  } finally {
    loading.value = false
  }
}

const TYPE_TEXT: Record<string, string> = {
  MEMBER: '会员', QUESTION_BANK: '题库包', COURSE: '课程', PACKAGE: '套餐'
}

function typeText(type?: string) {
  return (type && TYPE_TEXT[type]) || (type || '')
}

function money(fen?: number) {
  return ((fen || 0) / 100).toFixed(2)
}

function openCreate() {
  editingId.value = null
  Object.assign(form, {
    code: '',
    name: '',
    type: 'MEMBER',
    description: '',
    coverUrl: '',
    priceYuan: 0,
    originalYuan: 0,
    validityDays: 0,
    resourceId: 0,
    status: 'ON_SALE',
    sortNo: list.value.length + 1
  })
  editVisible.value = true
}

function openEdit(row: ProductItem) {
  editingId.value = row.id || null
  Object.assign(form, {
    code: row.code || '',
    name: row.name || '',
    type: row.type || 'MEMBER',
    description: row.description || '',
    coverUrl: row.coverUrl || '',
    priceYuan: (row.priceFen || 0) / 100,
    originalYuan: (row.originalPriceFen || 0) / 100,
    validityDays: row.validityDays || 0,
    resourceId: row.resourceId || 0,
    status: row.status || 'ON_SALE',
    sortNo: row.sortNo || 1
  })
  editVisible.value = true
}

async function submit() {
  if (!form.code.trim() || !form.name.trim()) {
    ElMessage.warning('商品编码与名称不能为空')
    return
  }
  editing.value = true
  try {
    // 金额用元输入、按分提交：避免浮点直接入库，也避免管理员被「12.50 元」和「1250 分」来回折算
    const payload: ProductItem = {
      code: form.code.trim(),
      name: form.name.trim(),
      type: form.type,
      description: form.description,
      coverUrl: form.coverUrl,
      priceFen: Math.round(form.priceYuan * 100),
      originalPriceFen: Math.round(form.originalYuan * 100),
      validityDays: form.validityDays,
      resourceId: form.resourceId || 0,
      status: form.status,
      sortNo: form.sortNo
    }
    if (editingId.value) {
      await productApi.update(editingId.value, payload)
      ElMessage.success('已保存')
    } else {
      await productApi.create(payload)
      ElMessage.success('已新增')
    }
    editVisible.value = false
    await load()
  } finally {
    editing.value = false
  }
}

async function removeRow(row: ProductItem) {
  await ElMessageBox.confirm(
    `确认删除商品「${row.name}」？已售出的权益不受影响，但该商品会从商城消失。日常停售请改用「下架」。`,
    '删除商品',
    { type: 'warning' }
  )
  await productApi.remove(row.id as number)
  ElMessage.success('已删除')
  await load()
}

onMounted(load)
</script>

<template>
  <div class="page-card">
    <h3 class="page-title">商城商品</h3>
    <p class="page-desc">下架只是从考生端商城消失，已售出权益不受影响；删除不可恢复。</p>

    <div class="toolbar">
      <el-input v-model="query.keyword" placeholder="编码/名称" style="width: 200px" clearable
                @keyup.enter="load" />
      <el-select v-model="query.status" placeholder="状态" style="width: 150px" clearable @change="load">
        <el-option label="在售" value="ON_SALE" />
        <el-option label="下架" value="OFF_SALE" />
      </el-select>
      <el-button type="primary" @click="load">查询</el-button>
      <el-button type="success" @click="openCreate">新增商品</el-button>
    </div>

    <el-table v-loading="loading" :data="list" border stripe>
      <el-table-column prop="code" label="编码" width="200" />
      <el-table-column prop="name" label="名称" min-width="160" />
      <el-table-column label="类型" width="130">
        <template #default="{ row }">{{ typeText(row.type) }}</template>
      </el-table-column>
      <el-table-column label="价格（元）" width="110">
        <template #default="{ row }">{{ money(row.priceFen) }}</template>
      </el-table-column>
      <el-table-column label="原价（元）" width="110">
        <template #default="{ row }">{{ money(row.originalPriceFen) }}</template>
      </el-table-column>
      <el-table-column prop="validityDays" label="有效期(天)" width="100" />
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="row.status === 'ON_SALE' ? 'success' : 'info'">
            {{ row.status === 'ON_SALE' ? '在售' : '下架' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="sortNo" label="排序" width="80" />
      <el-table-column label="操作" width="140" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button link type="danger" @click="removeRow(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <div class="pager">
      <el-pagination v-model:current-page="query.pageNo" v-model:page-size="query.pageSize"
                     :total="total" layout="total, prev, pager, next" @current-change="load" />
    </div>

    <el-dialog v-model="editVisible" :title="editingId ? '编辑商品' : '新增商品'" width="560px">
      <el-form label-width="100px">
        <el-form-item label="编码">
          <!-- 编码是唯一键：编辑时不允许改，避免已引用的商品对不上 -->
          <el-input v-model="form.code" :disabled="!!editingId" placeholder="如 MEMBER_MONTH" />
        </el-form-item>
        <el-form-item label="名称">
          <el-input v-model="form.name" />
        </el-form-item>
        <el-form-item label="类型">
          <el-select v-model="form.type" style="width: 100%;">
            <el-option label="会员" value="MEMBER" />
            <el-option label="题库" value="QUESTION_BANK" />
            <el-option label="课程" value="COURSE" />
            <el-option label="套餐" value="PACKAGE" />
          </el-select>
        </el-form-item>
        <el-form-item label="售价（元）">
          <el-input-number v-model="form.priceYuan" :min="0" :step="0.01" :precision="2" />
        </el-form-item>
        <el-form-item label="原价（元）">
          <el-input-number v-model="form.originalYuan" :min="0" :step="0.01" :precision="2" />
        </el-form-item>
        <el-form-item label="有效期(天)">
          <el-input-number v-model="form.validityDays" :min="0" />
        </el-form-item>
        <el-form-item label="资源 ID">
          <el-input-number v-model="form.resourceId" :min="0" placeholder="绑定题库/课程时可填" />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="form.status" style="width: 100%;">
            <el-option label="在售" value="ON_SALE" />
            <el-option label="下架" value="OFF_SALE" />
          </el-select>
        </el-form-item>
        <el-form-item label="排序">
          <el-input-number v-model="form.sortNo" :min="1" />
        </el-form-item>
        <el-form-item label="封面 URL">
          <el-input v-model="form.coverUrl" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="form.description" type="textarea" :rows="3" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" :loading="editing" @click="submit">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>
