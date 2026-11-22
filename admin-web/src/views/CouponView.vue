<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { couponAdminApi, type CouponGrantAdminRow, type CouponItem } from '@/api'
import { COUPON_STATUS, GRANT_STATUS, toFen, useTradeOverview } from '@/composables/useTrade'

/**
 * 页面组织按「运营最常做的事」排序：先看有多少在架、领了多少，再按状态筛，
 * 最后才是建券。停发/启用做成行内按钮——它是一天可能点十几次的动作，
 * 每次都要开弹窗填表等于把高频操作当成低频操作。
 */
const { overview, loadOverview } = useTradeOverview()

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

/** 整数金额去掉尾部 .00：「满 100 减 20」比「满 100.00 元减 20.00 元」短一半，不易折行 */
function amountText(fen?: number | null) {
  const value = (fen || 0) / 100
  return Number.isInteger(value) ? String(value) : value.toFixed(2)
}

/** 券面文案：列表里直接给出「满 100 减 20」「8.5 折封顶 20」比两列裸数字好读得多 */
function couponText(row: CouponItem) {
  if (row.type === 'DISCOUNT') {
    const cap = row.maxDiscountFen ? `，封顶 ${amountText(row.maxDiscountFen)} 元` : ''
    return `${(row.discountPercent / 10).toFixed(1)} 折${cap}`
  }
  const threshold = row.thresholdFen ? `满 ${amountText(row.thresholdFen)} 元` : '无门槛'
  return `${threshold}减 ${amountText(row.valueFen)} 元`
}

function scopeText(row: CouponItem) {
  if (row.scopeType === 'PRODUCT') {
    return row.scopeId ? `限商品 #${row.scopeId}` : '限指定商品'
  }
  return '全部商品'
}

/** 领取进度：限量券看百分比，不限量券只显示已领数量 */
function claimPercent(row: CouponItem) {
  if (!row.totalQuantity) return 0
  return Math.min(100, Math.round(((row.claimedQuantity || 0) * 100) / row.totalQuantity))
}

/** 券是否已过期（有效期结束早于现在）：列表里要能一眼分辨「停发」与「自然过期」 */
function isExpired(row: CouponItem) {
  return !!row.validTo && new Date(row.validTo).getTime() < Date.now()
}

async function load() {
  loading.value = true
  try {
    const params: Record<string, any> = { pageNo: query.pageNo, pageSize: query.pageSize }
    if (query.keyword.trim()) params.keyword = query.keyword.trim()
    if (query.status) params.status = query.status
    const page = await couponAdminApi.page(params)
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
  query.keyword = ''
  query.status = ''
  query.pageNo = 1
  load()
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

function openCreate() {
  resetForm()
  createVisible.value = true
}

/** 复制券：发同类活动时通常只改面额与有效期，从零填一遍表单是纯浪费 */
function openCopy(row: CouponItem) {
  resetForm()
  Object.assign(form, {
    code: `${row.code}_COPY`,
    name: `${row.name}-副本`,
    type: row.type,
    thresholdYuan: (row.thresholdFen || 0) / 100,
    valueYuan: (row.valueFen || 0) / 100,
    discountPercent: row.discountPercent || 85,
    maxDiscountYuan: row.maxDiscountFen ? row.maxDiscountFen / 100 : undefined,
    totalQuantity: row.totalQuantity || 0,
    perUserLimit: row.perUserLimit || 1,
    scopeType: row.scopeType || 'ALL',
    scopeId: row.scopeId || undefined,
    validRange: []
  })
  createVisible.value = true
  ElMessage.info('已复制券面配置，请修改编码并重新选择有效期')
}

/** 提交前的本地校验：把后端会拒的明显错误提前挡掉，别让运营等一次往返 */
const formError = computed(() => {
  if (!form.code.trim()) return '券编码必填'
  if (!form.name.trim()) return '券名称必填'
  if (!form.validRange || form.validRange.length !== 2) return '请选择有效期'
  if (form.type === 'AMOUNT' && form.valueYuan <= 0) return '满减券的抵扣金额必须大于 0'
  if (form.type === 'DISCOUNT' && (form.discountPercent <= 0 || form.discountPercent >= 100)) {
    return '折扣力度需在 1~99 之间（85 表示 85 折）'
  }
  if (form.scopeType === 'PRODUCT' && !form.scopeId) return '指定商品的券必须填写商品 ID'
  if (form.thresholdYuan > 0 && form.type === 'AMOUNT' && form.thresholdYuan <= form.valueYuan) {
    return '抵扣金额不应大于等于使用门槛，否则等于无门槛券'
  }
  return ''
})

async function save() {
  if (formError.value) {
    return ElMessage.warning(formError.value)
  }
  saving.value = true
  try {
    await couponAdminApi.create({
      code: form.code.trim(),
      name: form.name.trim(),
      type: form.type,
      thresholdFen: toFen(form.thresholdYuan),
      valueFen: form.type === 'AMOUNT' ? toFen(form.valueYuan) : 0,
      discountPercent: form.type === 'DISCOUNT' ? form.discountPercent : 0,
      maxDiscountFen: form.type === 'DISCOUNT' && form.maxDiscountYuan ? toFen(form.maxDiscountYuan) : null,
      totalQuantity: form.totalQuantity,
      perUserLimit: form.perUserLimit,
      scopeType: form.scopeType,
      scopeId: form.scopeType === 'PRODUCT' ? form.scopeId : null,
      validFrom: form.validRange[0],
      validTo: form.validRange[1]
    })
    ElMessage.success('券已创建并上架，考生可凭编码领取')
    createVisible.value = false
    await Promise.all([load(), loadOverview()])
  } finally {
    saving.value = false
  }
}

async function toggle(row: CouponItem) {
  const next = row.status === 'ACTIVE' ? 'STOPPED' : 'ACTIVE'
  const label = next === 'STOPPED' ? '停发' : '启用'
  const effect = next === 'STOPPED'
    ? '考生侧将无法继续领取，已领取的券仍可在有效期内使用。'
    : '考生侧恢复可领。'
  try {
    await ElMessageBox.confirm(`确认${label}券「${row.name}」？${effect}`, `${label}确认`, {
      type: 'warning',
      confirmButtonText: label,
      cancelButtonText: '取消'
    })
  } catch {
    return
  }
  await couponAdminApi.updateStatus(row.id, next)
  ElMessage.success(`已${label}`)
  await Promise.all([load(), loadOverview()])
}

async function copyCode(code: string) {
  try {
    await navigator.clipboard.writeText(code)
    ElMessage.success('券编码已复制，可直接发给考生')
  } catch {
    ElMessage.warning('浏览器不允许自动复制，请手动选中')
  }
}

// ---------------------------------------------------------------- 领取记录

const grantsVisible = ref(false)
const grantsLoading = ref(false)
const grants = ref<CouponGrantAdminRow[]>([])
const grantsTotal = ref(0)
const grantsQuery = reactive({ couponId: 0, pageNo: 1, pageSize: 10 })
const grantsCoupon = ref<CouponItem | null>(null)

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
  grantsCoupon.value = row
  grantsQuery.couponId = row.id
  grantsQuery.pageNo = 1
  grantsVisible.value = true
  loadGrants()
}

onMounted(() => {
  load()
  loadOverview()
})
</script>

<template>
  <div class="page-card">
    <h3 class="page-title">优惠券</h3>
    <p class="page-desc">
      创建后立即上架；停发后考生侧不可再领，已领的券仍在有效期内可用。金额按元填写，后端按分存储。
    </p>

    <div class="stat-strip">
      <div class="stat-item is-ok">
        <div class="stat-value">{{ overview?.couponActive ?? 0 }}</div>
        <div class="stat-label">上架中的券</div>
      </div>
      <div class="stat-item">
        <div class="stat-value">{{ overview?.couponStopped ?? 0 }}</div>
        <div class="stat-label">已停发</div>
      </div>
      <div class="stat-item">
        <div class="stat-value">{{ overview?.couponClaimed ?? 0 }}</div>
        <div class="stat-label">累计领取量</div>
      </div>
      <div class="stat-item">
        <div class="stat-value">{{ overview?.productOnSale ?? 0 }}</div>
        <div class="stat-label">可用的在售商品</div>
      </div>
    </div>

    <div class="toolbar">
      <el-input v-model="query.keyword" placeholder="编码 / 名称" style="width: 220px" clearable
                @keyup.enter="search" />
      <el-select v-model="query.status" placeholder="全部状态" style="width: 140px" clearable @change="search">
        <el-option label="上架中" value="ACTIVE" />
        <el-option label="已停发" value="STOPPED" />
      </el-select>
      <el-button type="primary" @click="search">查询</el-button>
      <el-button @click="reset">重置</el-button>
      <div class="spacer" />
      <el-button type="success" @click="openCreate">新建券</el-button>
    </div>

    <el-table v-loading="loading" :data="list" border stripe>
      <el-table-column label="券" min-width="190">
        <template #default="{ row }">
          <div class="name-cell">
            <span class="name">{{ row.name }}</span>
            <span class="code mono" @click="copyCode(row.code)">{{ row.code }}</span>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="优惠内容" min-width="150">
        <template #default="{ row }">{{ couponText(row) }}</template>
      </el-table-column>
      <el-table-column label="适用范围" width="118">
        <template #default="{ row }">
          <el-tag :type="row.scopeType === 'ALL' ? 'info' : 'warning'" effect="plain" size="small">
            {{ scopeText(row) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="领取进度" width="160">
        <template #default="{ row }">
          <div v-if="row.totalQuantity" class="claim">
            <el-progress :percentage="claimPercent(row)" :stroke-width="8"
                         :status="claimPercent(row) >= 100 ? 'success' : undefined" />
            <span class="claim-text">{{ row.claimedQuantity }} / {{ row.totalQuantity }}</span>
          </div>
          <div v-else class="claim">
            <span class="claim-text">已领 {{ row.claimedQuantity }} 张</span>
            <span class="claim-unlimited">不限量</span>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="有效期" min-width="185">
        <template #default="{ row }">
          <div class="validity" :class="{ expired: isExpired(row) }">
            {{ row.validFrom?.replace('T', ' ').slice(0, 16) }}
            <span class="tilde">~</span>
            {{ row.validTo?.replace('T', ' ').slice(0, 16) }}
          </div>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="92">
        <template #default="{ row }">
          <el-tag :type="COUPON_STATUS[row.status]?.type || 'info'" effect="plain">
            {{ COUPON_STATUS[row.status]?.text || row.status }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="196" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openGrants(row)">领取记录</el-button>
          <el-button link type="primary" @click="openCopy(row)">复制</el-button>
          <el-button link :type="row.status === 'ACTIVE' ? 'danger' : 'success'" @click="toggle(row)">
            {{ row.status === 'ACTIVE' ? '停发' : '启用' }}
          </el-button>
        </template>
      </el-table-column>
      <template #empty>
        <div class="empty-wrap">
          <el-empty :description="query.keyword || query.status
            ? '没有符合条件的券，换个筛选条件试试'
            : '还没有优惠券——新建后考生可凭编码领取'" :image-size="72" />
        </div>
      </template>
    </el-table>

    <div class="pager">
      <el-pagination v-model:current-page="query.pageNo" v-model:page-size="query.pageSize"
                     :total="total" :page-sizes="[10, 20, 50]"
                     layout="total, sizes, prev, pager, next" @current-change="load"
                     @size-change="search" />
    </div>

    <el-dialog v-model="grantsVisible" :title="`领取记录 · ${grantsCoupon?.name || ''}`" width="720px">
      <p class="page-desc" style="margin: 0 0 12px;">
        {{ grantsCoupon ? couponText(grantsCoupon) : '' }}，共领取 {{ grantsTotal }} 张。
        锁定中的券正在某笔未支付订单里占用，订单关闭后会自动退回未使用。
      </p>
      <el-table v-loading="grantsLoading" :data="grants" border stripe>
        <el-table-column label="用户" min-width="130">
          <template #default="{ row }">
            <span>{{ row.username }}</span>
            <span class="muted"> #{{ row.userId }}</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag :type="GRANT_STATUS[row.status]?.type || 'info'" effect="plain" size="small">
              {{ GRANT_STATUS[row.status]?.text || row.status }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="实际抵扣" width="100" align="right">
          <template #default="{ row }">
            {{ row.discountFen ? amountText(row.discountFen) + ' 元' : '—' }}
          </template>
        </el-table-column>
        <el-table-column label="领取时间" min-width="150">
          <template #default="{ row }">{{ row.grantedAt?.replace('T', ' ').slice(0, 16) || '—' }}</template>
        </el-table-column>
        <el-table-column label="核销时间" min-width="150">
          <template #default="{ row }">{{ row.usedAt?.replace('T', ' ').slice(0, 16) || '—' }}</template>
        </el-table-column>
      </el-table>
      <div class="pager">
        <el-pagination v-model:current-page="grantsQuery.pageNo" :page-size="grantsQuery.pageSize"
                       :total="grantsTotal" layout="total, prev, pager, next" @current-change="loadGrants" />
      </div>
    </el-dialog>

    <el-dialog v-model="createVisible" title="新建优惠券" width="620px">
      <el-form label-width="104px">
        <el-divider content-position="left">券面</el-divider>
        <el-form-item label="券编码" required>
          <el-input v-model="form.code" placeholder="考生领券时使用，租户内唯一" />
        </el-form-item>
        <el-form-item label="名称" required>
          <el-input v-model="form.name" placeholder="对考生展示，如「满50减5」" />
        </el-form-item>
        <el-form-item label="类型" required>
          <el-radio-group v-model="form.type">
            <el-radio-button label="AMOUNT">满减券</el-radio-button>
            <el-radio-button label="DISCOUNT">折扣券</el-radio-button>
          </el-radio-group>
        </el-form-item>

        <el-divider content-position="left">优惠规则</el-divider>
        <el-form-item label="使用门槛">
          <el-input-number v-model="form.thresholdYuan" :min="0" :precision="2" style="width: 160px" />
          <span class="unit">元，0 表示无门槛</span>
        </el-form-item>
        <el-form-item v-if="form.type === 'AMOUNT'" label="抵扣金额" required>
          <el-input-number v-model="form.valueYuan" :min="0.01" :precision="2" style="width: 160px" />
          <span class="unit">元</span>
        </el-form-item>
        <el-form-item v-else label="折扣力度" required>
          <el-input-number v-model="form.discountPercent" :min="1" :max="99" style="width: 160px" />
          <span class="unit">%，85 表示 85 折</span>
        </el-form-item>
        <el-form-item v-if="form.type === 'DISCOUNT'" label="封顶金额">
          <el-input-number v-model="form.maxDiscountYuan" :min="0" :precision="2" style="width: 160px" />
          <span class="unit">元，留空不封顶</span>
        </el-form-item>
        <el-alert v-if="form.type === 'DISCOUNT' && !form.maxDiscountYuan" type="warning" show-icon
                  :closable="false" class="form-alert"
                  title="折扣券未设封顶：订单金额越大抵扣越多，大额订单容易被薅，建议配置封顶金额。" />
        <el-form-item label="适用范围">
          <el-select v-model="form.scopeType" style="width: 160px">
            <el-option label="全部商品" value="ALL" />
            <el-option label="指定商品" value="PRODUCT" />
          </el-select>
          <el-input-number v-if="form.scopeType === 'PRODUCT'" v-model="form.scopeId" :min="1"
                           placeholder="商品ID" style="width: 150px; margin-left: 8px" />
        </el-form-item>

        <el-divider content-position="left">发行与有效期</el-divider>
        <el-form-item label="发行总量">
          <el-input-number v-model="form.totalQuantity" :min="0" style="width: 160px" />
          <span class="unit">张，0 表示不限量</span>
        </el-form-item>
        <el-form-item label="每人限领">
          <el-input-number v-model="form.perUserLimit" :min="1" style="width: 160px" />
          <span class="unit">张</span>
        </el-form-item>
        <el-form-item label="有效期" required>
          <el-date-picker v-model="form.validRange" type="datetimerange" range-separator="至"
                          start-placeholder="开始" end-placeholder="结束"
                          value-format="YYYY-MM-DDTHH:mm:ss" style="width: 100%;" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" :disabled="!!formError" @click="save">
          创建并上架
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.name-cell {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.name {
  font-weight: 500;
}

.mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 11px;
  color: #2563eb;
  cursor: pointer;
}

.mono:hover {
  text-decoration: underline;
}

.muted {
  color: #9ca3af;
  font-size: 12px;
}

.claim {
  display: flex;
  flex-direction: column;
  gap: 3px;
}

.claim-text {
  font-size: 12px;
  color: #64748b;
  font-variant-numeric: tabular-nums;
}

.claim-unlimited {
  font-size: 11px;
  color: #16a34a;
}

.validity {
  font-size: 12px;
  color: #4b5563;
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}

.validity.expired {
  color: #b0b7c3;
}

.tilde {
  color: #cbd5e1;
  margin: 0 2px;
}

.unit {
  margin-left: 8px;
  font-size: 12px;
  color: #8a94a6;
}

.form-alert {
  margin: 0 0 14px;
}
</style>
