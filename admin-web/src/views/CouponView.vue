<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { couponAdminApi, type CouponItem } from '@/api'

const loading = ref(false)
const list = ref<CouponItem[]>([])
const total = ref(0)
const query = reactive({ pageNo: 1, pageSize: 10, keyword: '', status: '' })

const createVisible = ref(false)
const saving = ref(false)
const form = reactive({
  code: '',
  name: '',
  type: 'AMOUNT',
  thresholdYuan: 0,
  valueYuan: 0,
  discountPercent: 85,
  maxDiscountYuan: undefined as number | undefined,
  totalQuantity: 0,
  perUserLimit: 1,
  scopeType: 'ALL',
  scopeId: undefined as number | undefined,
  validRange: [] as string[]
})

function fen(yuan?: number) {
  return Math.round((yuan || 0) * 100)
}

function yuanOf(fenValue?: number | null) {
  return ((fenValue || 0) / 100).toFixed(2)
}

function couponText(row: CouponItem) {
  if (row.type === 'DISCOUNT') {
    const cap = row.maxDiscountFen ? `，封顶${yuanOf(row.maxDiscountFen)}元` : ''
    return `${(row.discountPercent / 10)}折${cap}`
  }
  const threshold = row.thresholdFen ? `满${yuanOf(row.thresholdFen)}元` : '无门槛'
  return `${threshold}减${yuanOf(row.valueFen)}元`
}

async function load() {
  loading.value = true
  try {
    const params: Record<string, any> = { pageNo: query.pageNo, pageSize: query.pageSize }
    if (query.keyword) {
      params.keyword = query.keyword
    }
    if (query.status) {
      params.status = query.status
    }
    const page = await couponAdminApi.page(params)
    list.value = page.list
    total.value = page.total
  } finally {
    loading.value = false
  }
}

function resetForm() {
  form.code = ''
  form.name = ''
  form.type = 'AMOUNT'
  form.thresholdYuan = 0
  form.valueYuan = 0
  form.discountPercent = 85
  form.maxDiscountYuan = undefined
  form.totalQuantity = 0
  form.perUserLimit = 1
  form.scopeType = 'ALL'
  form.scopeId = undefined
  form.validRange = []
}

async function save() {
  if (!form.code.trim() || !form.name.trim()) {
    return ElMessage.warning('编码与名称必填')
  }
  if (!form.validRange || form.validRange.length !== 2) {
    return ElMessage.warning('请选择有效期')
  }
  saving.value = true
  try {
    await couponAdminApi.create({
      code: form.code.trim(),
      name: form.name.trim(),
      type: form.type,
      thresholdFen: fen(form.thresholdYuan),
      valueFen: fen(form.valueYuan),
      discountPercent: form.type === 'DISCOUNT' ? form.discountPercent : 0,
      maxDiscountFen: form.type === 'DISCOUNT' && form.maxDiscountYuan ? fen(form.maxDiscountYuan) : null,
      totalQuantity: form.totalQuantity,
      perUserLimit: form.perUserLimit,
      scopeType: form.scopeType,
      scopeId: form.scopeType === 'PRODUCT' ? form.scopeId : null,
      validFrom: form.validRange[0],
      validTo: form.validRange[1]
    })
    ElMessage.success('券已创建并上架，考生可凭编码领取')
    createVisible.value = false
    await load()
  } finally {
    saving.value = false
  }
}

async function toggle(row: CouponItem) {
  const next = row.status === 'ACTIVE' ? 'STOPPED' : 'ACTIVE'
  const label = next === 'STOPPED' ? '停发' : '启用'
  const effect = next === 'STOPPED' ? '考生侧将无法继续领取' : '考生侧恢复可领'
  await ElMessageBox.confirm(`确认${label}券「${row.name}」？${effect}。`, `${label}确认`, { type: 'warning' })
  await couponAdminApi.updateStatus(row.id, next)
  ElMessage.success(`已${label}`)
  await load()
}

const grantsVisible = ref(false)
const grantsLoading = ref(false)
const grants = ref<any[]>([])
const grantsTotal = ref(0)
const grantsQuery = reactive({ couponId: 0, pageNo: 1, pageSize: 10 })

const GRANT_STATUS: Record<string, string> = {
  UNUSED: '未使用',
  LOCKED: '下单锁定中',
  USED: '已核销',
  EXPIRED: '已过期'
}

async function loadGrants() {
  grantsLoading.value = true
  try {
    const page = await couponAdminApi.grants(grantsQuery.couponId, {
      pageNo: grantsQuery.pageNo,
      pageSize: grantsQuery.pageSize
    })
    grants.value = page.list
    grantsTotal.value = page.total
  } finally {
    grantsLoading.value = false
  }
}

function openGrants(row: CouponItem) {
  grantsQuery.couponId = row.id
  grantsQuery.pageNo = 1
  grantsVisible.value = true
  loadGrants()
}

onMounted(load)
</script>

<template>
  <div class="page-card">
    <h3 class="page-title">优惠券</h3>
    <p class="page-desc">创建后立即上架；停发后考生侧不可再领。金额按元填写，后端按分存储。</p>

    <div class="toolbar">
      <el-input v-model="query.keyword" placeholder="编码 / 名称" style="width: 200px" clearable
                @keyup.enter="load" />
      <el-select v-model="query.status" placeholder="全部状态" style="width: 140px" clearable
                 @change="load">
        <el-option label="上架中" value="ACTIVE" />
        <el-option label="已停发" value="STOPPED" />
      </el-select>
      <el-button type="primary" @click="load">查询</el-button>
      <el-button type="success" @click="resetForm(); createVisible = true">新建券</el-button>
    </div>

    <el-table v-loading="loading" :data="list" border stripe>
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column prop="code" label="编码" width="140" show-overflow-tooltip />
      <el-table-column prop="name" label="名称" min-width="150" show-overflow-tooltip />
      <el-table-column label="优惠内容" min-width="170">
        <template #default="{ row }">{{ couponText(row) }}</template>
      </el-table-column>
      <el-table-column label="已领/总量" width="110">
        <template #default="{ row }">{{ row.claimedQuantity }} / {{ row.totalQuantity || '不限' }}</template>
      </el-table-column>
      <el-table-column label="有效期" min-width="210">
        <template #default="{ row }">{{ row.validFrom }} ~ {{ row.validTo }}</template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="row.status === 'ACTIVE' ? 'success' : 'info'" effect="plain">
            {{ row.status === 'ACTIVE' ? '上架中' : '已停发' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="150" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openGrants(row)">领取记录</el-button>
          <el-button link :type="row.status === 'ACTIVE' ? 'danger' : 'primary'" @click="toggle(row)">
            {{ row.status === 'ACTIVE' ? '停发' : '启用' }}
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <div class="pager">
      <el-pagination v-model:current-page="query.pageNo" v-model:page-size="query.pageSize"
                     :total="total" layout="total, prev, pager, next" @current-change="load" />
    </div>

    <el-dialog v-model="grantsVisible" title="领取记录" width="680px">
      <el-table v-loading="grantsLoading" :data="grants" border stripe>
        <el-table-column prop="userId" label="用户ID" width="80" />
        <el-table-column prop="username" label="用户名" min-width="120" />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">{{ GRANT_STATUS[row.status] || row.status }}</template>
        </el-table-column>
        <el-table-column label="抵扣" width="100">
          <template #default="{ row }">{{ row.discountFen ? (row.discountFen / 100).toFixed(2) + ' 元' : '-' }}</template>
        </el-table-column>
        <el-table-column label="领取时间" min-width="160">
          <template #default="{ row }">{{ row.grantedAt || '-' }}</template>
        </el-table-column>
        <el-table-column label="核销时间" min-width="160">
          <template #default="{ row }">{{ row.usedAt || '-' }}</template>
        </el-table-column>
      </el-table>
      <div class="pager">
        <el-pagination v-model:current-page="grantsQuery.pageNo" :page-size="grantsQuery.pageSize"
                       :total="grantsTotal" layout="total, prev, pager, next" @current-change="loadGrants" />
      </div>
    </el-dialog>

    <el-dialog v-model="createVisible" title="新建优惠券" width="560px">
      <el-form label-width="96px">
        <el-form-item label="券编码" required>
          <el-input v-model="form.code" placeholder="考生领券时使用，租户内唯一" />
        </el-form-item>
        <el-form-item label="名称" required>
          <el-input v-model="form.name" placeholder="对考生展示，如「满50减5」" />
        </el-form-item>
        <el-form-item label="类型" required>
          <el-radio-group v-model="form.type">
            <el-radio label="AMOUNT">满减券</el-radio>
            <el-radio label="DISCOUNT">折扣券</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="使用门槛">
          <el-input-number v-model="form.thresholdYuan" :min="0" :precision="2" style="width: 160px" />
          <span class="unit">元（0 表示无门槛）</span>
        </el-form-item>
        <el-form-item v-if="form.type === 'AMOUNT'" label="抵扣金额" required>
          <el-input-number v-model="form.valueYuan" :min="0.01" :precision="2" style="width: 160px" />
          <span class="unit">元</span>
        </el-form-item>
        <el-form-item v-else label="折扣力度" required>
          <el-input-number v-model="form.discountPercent" :min="1" :max="99" style="width: 160px" />
          <span class="unit">% 优惠，85 表示 85 折</span>
        </el-form-item>
        <el-form-item v-if="form.type === 'DISCOUNT'" label="封顶金额">
          <el-input-number v-model="form.maxDiscountYuan" :min="0" :precision="2" style="width: 160px" />
          <span class="unit">元，留空不封顶</span>
        </el-form-item>
        <el-form-item label="发行总量">
          <el-input-number v-model="form.totalQuantity" :min="0" style="width: 160px" />
          <span class="unit">张（0 表示不限量）</span>
        </el-form-item>
        <el-form-item label="每人限领">
          <el-input-number v-model="form.perUserLimit" :min="1" style="width: 160px" />
          <span class="unit">张</span>
        </el-form-item>
        <el-form-item label="适用范围">
          <el-select v-model="form.scopeType" style="width: 160px">
            <el-option label="全部商品" value="ALL" />
            <el-option label="指定商品" value="PRODUCT" />
          </el-select>
          <el-input-number v-if="form.scopeType === 'PRODUCT'" v-model="form.scopeId" :min="1"
                           placeholder="商品ID" style="width: 140px; margin-left: 8px" />
        </el-form-item>
        <el-form-item label="有效期" required>
          <el-date-picker v-model="form.validRange" type="datetimerange" range-separator="至"
                          start-placeholder="开始" end-placeholder="结束"
                          value-format="YYYY-MM-DDTHH:mm:ss" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">创建并上架</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.unit {
  margin-left: 8px;
  font-size: 12px;
  color: #8a94a6;
}
</style>
