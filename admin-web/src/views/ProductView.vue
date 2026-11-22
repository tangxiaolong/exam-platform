<script setup lang="ts">
/**
 * 商城商品。
 *
 * <p>页面组织按「运营最常做的事」排序：先看有多少在售、多少下架，再按类型/状态筛，
 * 最后才是改价与上下架。上下架做成行内按钮而不是进弹窗——它是一天可能点十几次的动作，
 * 每次都要开弹窗填表等于把高频操作当成低频操作。</p>
 */
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { productApi, type ProductItem } from '@/api'
import { money, PRODUCT_STATUS, PRODUCT_TYPE, toFen, useTradeOverview } from '@/composables/useTrade'

const { overview, loadOverview } = useTradeOverview()

const list = ref<ProductItem[]>([])
const total = ref(0)
const loading = ref(false)
const query = reactive({ pageNo: 1, pageSize: 10, keyword: '', status: '', type: '' })

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

/** 售价高于原价时原价划线没有意义，反而让人以为算错了 */
const priceWarning = computed(
  () => form.originalYuan > 0 && form.priceYuan > form.originalYuan
)

async function load() {
  loading.value = true
  try {
    const params: Record<string, any> = {
      pageNo: query.pageNo,
      pageSize: query.pageSize
    }
    if (query.keyword.trim()) params.keyword = query.keyword.trim()
    if (query.status) params.status = query.status
    if (query.type) params.type = query.type
    const page = await productApi.page(params)
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
  query.type = ''
  query.pageNo = 1
  load()
}

function typeText(type?: string) {
  return (type && PRODUCT_TYPE[type]) || type || '—'
}

/** 折扣只对「有原价且原价更高」的商品计算，否则显示空 */
function discountText(row: ProductItem) {
  const original = row.originalPriceFen || 0
  const price = row.priceFen || 0
  if (original <= 0 || price <= 0 || original <= price) return ''
  const rate = (price * 10) / original
  return `${rate.toFixed(1)} 折`
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
    sortNo: (list.value.length || 0) + 1
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

/** 复制为新商品：同类商品往往只差价格与时长，从零填一遍表单是纯浪费 */
function openCopy(row: ProductItem) {
  editingId.value = null
  Object.assign(form, {
    code: `${row.code || ''}_COPY`,
    name: `${row.name || ''}-副本`,
    type: row.type || 'MEMBER',
    description: row.description || '',
    coverUrl: row.coverUrl || '',
    priceYuan: (row.priceFen || 0) / 100,
    originalYuan: (row.originalPriceFen || 0) / 100,
    validityDays: row.validityDays || 0,
    resourceId: row.resourceId || 0,
    status: 'OFF_SALE',
    sortNo: (row.sortNo || 1) + 1
  })
  editVisible.value = true
  ElMessage.info('已复制为新商品（默认下架），确认价格与编码后再上架')
}

async function submit() {
  if (!form.code.trim() || !form.name.trim()) {
    ElMessage.warning('商品编码与名称不能为空')
    return
  }
  if (form.priceYuan <= 0) {
    ElMessage.warning('售价必须大于 0，否则考生无法下单支付')
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
      priceFen: toFen(form.priceYuan),
      originalPriceFen: toFen(form.originalYuan),
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
    await Promise.all([load(), loadOverview()])
  } finally {
    editing.value = false
  }
}

/** 行内上下架：只回传 status，后端 updateById 会忽略其余空字段 */
async function toggleSale(row: ProductItem) {
  const next = row.status === 'ON_SALE' ? 'OFF_SALE' : 'ON_SALE'
  const label = next === 'ON_SALE' ? '上架' : '下架'
  const effect = next === 'ON_SALE'
    ? '考生端商城立即可见并可购买。'
    : '考生端商城立即消失，已售出的权益不受影响。'
  try {
    await ElMessageBox.confirm(`确认${label}「${row.name}」？${effect}`, `${label}确认`, {
      type: 'warning',
      confirmButtonText: label,
      cancelButtonText: '取消'
    })
  } catch {
    return
  }
  await productApi.update(row.id as number, { status: next })
  ElMessage.success(`已${label}`)
  await Promise.all([load(), loadOverview()])
}

async function removeRow(row: ProductItem) {
  await ElMessageBox.confirm(
    `确认删除商品「${row.name}」？已售出的权益不受影响，但该商品会从商城消失。日常停售请改用「下架」。`,
    '删除商品',
    { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
  )
  await productApi.remove(row.id as number)
  ElMessage.success('已删除')
  await Promise.all([load(), loadOverview()])
}

onMounted(() => {
  load()
  loadOverview()
})
</script>

<template>
  <div class="page-card">
    <h3 class="page-title">商城商品</h3>
    <p class="page-desc">
      下架只是从考生端商城消失，已售出权益不受影响；删除不可恢复，日常停售请用下架。
    </p>

    <div class="stat-strip">
      <div class="stat-item is-ok">
        <div class="stat-value">{{ overview?.productOnSale ?? 0 }}</div>
        <div class="stat-label">在售商品</div>
      </div>
      <div class="stat-item">
        <div class="stat-value">{{ overview?.productOffSale ?? 0 }}</div>
        <div class="stat-label">已下架</div>
      </div>
      <div class="stat-item">
        <div class="stat-value">{{ overview?.couponActive ?? 0 }}</div>
        <div class="stat-label">上架中的优惠券</div>
      </div>
      <div class="stat-item">
        <div class="stat-value">{{ overview?.couponClaimed ?? 0 }}</div>
        <div class="stat-label">券累计领取量</div>
      </div>
    </div>

    <div class="toolbar">
      <el-input v-model="query.keyword" placeholder="编码 / 名称" style="width: 220px" clearable
                @keyup.enter="search" />
      <el-select v-model="query.type" placeholder="全部类型" style="width: 140px" clearable @change="search">
        <el-option v-for="(label, key) in PRODUCT_TYPE" :key="key" :label="label" :value="key" />
      </el-select>
      <el-select v-model="query.status" placeholder="全部状态" style="width: 130px" clearable @change="search">
        <el-option label="在售" value="ON_SALE" />
        <el-option label="已下架" value="OFF_SALE" />
      </el-select>
      <el-button type="primary" @click="search">查询</el-button>
      <el-button @click="reset">重置</el-button>
      <div class="spacer" />
      <el-button type="success" @click="openCreate">新增商品</el-button>
    </div>

    <el-table v-loading="loading" :data="list" border stripe>
      <el-table-column label="商品" min-width="220">
        <template #default="{ row }">
          <div class="name-cell">
            <span class="name">{{ row.name }}</span>
            <span class="code mono">{{ row.code }}</span>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="类型" width="96">
        <template #default="{ row }">
          <el-tag effect="plain" size="small">{{ typeText(row.type) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="售价" width="150" align="right">
        <template #default="{ row }">
          <div class="price">{{ money(row.priceFen) }}</div>
          <div v-if="row.originalPriceFen && row.originalPriceFen > (row.priceFen || 0)" class="price-original">
            <s>{{ money(row.originalPriceFen) }}</s>
            <span class="discount">{{ discountText(row) }}</span>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="有效期" width="100" align="center">
        <template #default="{ row }">
          {{ row.validityDays ? row.validityDays + ' 天' : '长期' }}
        </template>
      </el-table-column>
      <el-table-column label="状态" width="96">
        <template #default="{ row }">
          <el-tag :type="PRODUCT_STATUS[row.status || '']?.type || 'info'" effect="plain">
            {{ PRODUCT_STATUS[row.status || '']?.text || row.status }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="sortNo" label="排序" width="72" align="center" />
      <el-table-column label="操作" width="200" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button link :type="row.status === 'ON_SALE' ? 'warning' : 'success'" @click="toggleSale(row)">
            {{ row.status === 'ON_SALE' ? '下架' : '上架' }}
          </el-button>
          <el-button link type="primary" @click="openCopy(row)">复制</el-button>
          <el-button link type="danger" @click="removeRow(row)">删除</el-button>
        </template>
      </el-table-column>
      <template #empty>
        <div class="empty-wrap">
          <el-empty :description="query.keyword || query.status || query.type
            ? '没有符合条件的商品，换个筛选条件试试'
            : '还没有商品——新增后考生才能在商城看到'" :image-size="72" />
        </div>
      </template>
    </el-table>

    <div class="pager">
      <el-pagination v-model:current-page="query.pageNo" v-model:page-size="query.pageSize"
                     :total="total" :page-sizes="[10, 20, 50]"
                     layout="total, sizes, prev, pager, next" @current-change="load"
                     @size-change="search" />
    </div>

    <el-dialog v-model="editVisible" :title="editingId ? '编辑商品' : '新增商品'" width="640px">
      <el-form label-width="104px">
        <el-divider content-position="left">基本信息</el-divider>
        <el-form-item label="编码" required>
          <!-- 编码是唯一键：编辑时不允许改，避免已引用的商品对不上 -->
          <el-input v-model="form.code" :disabled="!!editingId" placeholder="如 MEMBER_MONTH" />
        </el-form-item>
        <el-form-item label="名称" required>
          <el-input v-model="form.name" placeholder="对考生展示的商品名" />
        </el-form-item>
        <el-form-item label="类型" required>
          <el-select v-model="form.type" style="width: 100%;">
            <el-option v-for="(label, key) in PRODUCT_TYPE" :key="key" :label="label" :value="key" />
          </el-select>
        </el-form-item>

        <el-divider content-position="left">价格与时效</el-divider>
        <el-form-item label="售价（元）" required>
          <el-input-number v-model="form.priceYuan" :min="0" :step="0.01" :precision="2" />
          <span class="hint">考生实付金额，按分入库</span>
        </el-form-item>
        <el-form-item label="原价（元）">
          <el-input-number v-model="form.originalYuan" :min="0" :step="0.01" :precision="2" />
          <span class="hint">用于划线展示，0 表示不展示</span>
        </el-form-item>
        <el-alert v-if="priceWarning" type="warning" :closable="false" show-icon class="form-alert"
                  title="售价高于原价：划线价不会展示，考生看到的是「原价更贵」，请确认是否填反。" />
        <el-form-item label="有效期(天)">
          <el-input-number v-model="form.validityDays" :min="0" />
          <span class="hint">0 表示长期有效</span>
        </el-form-item>
        <el-form-item label="资源 ID">
          <el-input-number v-model="form.resourceId" :min="0" placeholder="绑定题库/课程时可填" />
        </el-form-item>

        <el-divider content-position="left">展示与上架</el-divider>
        <el-form-item label="状态">
          <el-radio-group v-model="form.status">
            <el-radio-button label="ON_SALE">在售</el-radio-button>
            <el-radio-button label="OFF_SALE">下架</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="排序">
          <el-input-number v-model="form.sortNo" :min="1" />
          <span class="hint">数字小的排在商城前面</span>
        </el-form-item>
        <el-form-item label="封面 URL">
          <el-input v-model="form.coverUrl" placeholder="选填，建议 4:3 竖图" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="form.description" type="textarea" :rows="3"
                    placeholder="考生在商品详情页看到的介绍" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" :loading="editing" @click="submit">确定</el-button>
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
  color: #94a3b8;
}

.price {
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}

.price-original {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 6px;
  font-size: 11px;
  color: #b0b7c3;
}

.discount {
  color: #ef4444;
  font-weight: 600;
}

.hint {
  margin-left: 8px;
  font-size: 12px;
  color: #8a94a6;
}

.form-alert {
  margin: 0 0 12px;
}
</style>
