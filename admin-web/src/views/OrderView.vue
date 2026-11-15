<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { tradeApi, type Order, type Product, type Refund } from '@/api'

const tab = ref('orders')
const loading = ref(false)
const orders = ref<Order[]>([])
const orderTotal = ref(0)
const orderQuery = reactive({ pageNo: 1, pageSize: 10, status: '', keyword: '' })

const refunds = ref<Refund[]>([])
const refundTotal = ref(0)
const refundQuery = reactive({ pageNo: 1, pageSize: 10, status: '' })
const refundStatusLabel: Record<string, string> = {
  APPLIED: '待审核',
  SUCCESS: '已退款',
  REJECTED: '已驳回',
  FAILED: '退款失败'
}

const products = ref<Product[]>([])
const productTotal = ref(0)
const productQuery = reactive({ pageNo: 1, pageSize: 10, keyword: '' })
const productVisible = ref(false)
const productForm = reactive<Product>({
  code: '',
  name: '',
  type: 'QUESTION_BANK',
  priceFen: 0,
  originalPriceFen: 0,
  validityDays: 30,
  status: 'ON_SALE'
})

const statusLabel: Record<string, string> = {
  CREATED: '已创建',
  PENDING_PAYMENT: '待支付',
  PAID: '已支付',
  COMPLETED: '已完成',
  CLOSED: '已关闭'
}

async function loadOrders() {
  loading.value = true
  try {
    const page = await tradeApi.orders(orderQuery)
    orders.value = page.list
    orderTotal.value = page.total
  } finally {
    loading.value = false
  }
}

async function loadProducts() {
  const page = await tradeApi.products(productQuery)
  products.value = page.list
  productTotal.value = page.total
}

async function loadRefunds() {
  const page = await tradeApi.refunds(refundQuery)
  refunds.value = page.list
  refundTotal.value = page.total
}

async function approveRefund(row: Refund) {
  await ElMessageBox.confirm(
    `确认退款 ${money(row.amountFen)}？将同时作废该订单未使用的权益。`,
    '审核通过',
    { type: 'warning' }
  )
  await tradeApi.approveRefund(row.id, '运营审核通过')
  ElMessage.success('已退款并回收权益')
  await Promise.all([loadRefunds(), loadOrders()])
}

async function rejectRefund(row: Refund) {
  const { value } = await ElMessageBox.prompt('请填写驳回原因', '驳回退款', {
    inputValue: '不符合退款规则'
  })
  await tradeApi.rejectRefund(row.id, value || '不符合退款规则')
  ElMessage.success('已驳回')
  loadRefunds()
}

function money(fen?: number) {
  return '¥' + ((fen || 0) / 100).toFixed(2)
}

function openCreateProduct() {
  Object.assign(productForm, {
    code: '',
    name: '',
    type: 'QUESTION_BANK',
    priceFen: 0,
    originalPriceFen: 0,
    validityDays: 30,
    status: 'ON_SALE',
    description: ''
  })
  productVisible.value = true
}

async function saveProduct() {
  if (!productForm.code || !productForm.name) return ElMessage.warning('编码与名称必填')
  await tradeApi.createProduct({ ...productForm })
  ElMessage.success('商品已创建')
  productVisible.value = false
  loadProducts()
}

onMounted(async () => {
  await Promise.all([loadOrders(), loadProducts(), loadRefunds()])
})
</script>

<template>
  <div class="page-card">
    <h3 class="page-title">交易管理</h3>
    <p class="page-desc">金额以「分」存储；支付成功只以服务端回调（验签 + 金额核对）为准。</p>

    <el-tabs v-model="tab">
      <el-tab-pane label="订单" name="orders">
        <div class="toolbar">
          <el-input v-model="orderQuery.keyword" placeholder="订单号/商品" style="width: 220px" clearable
                    @keyup.enter="loadOrders" />
          <el-select v-model="orderQuery.status" placeholder="状态" style="width: 150px" clearable>
            <el-option v-for="(label, key) in statusLabel" :key="key" :label="label" :value="key" />
          </el-select>
          <el-button type="primary" @click="loadOrders">查询</el-button>
        </div>
        <el-table v-loading="loading" :data="orders" border stripe>
          <el-table-column prop="orderNo" label="订单号" width="230" />
          <el-table-column prop="productName" label="商品" min-width="180" />
          <el-table-column label="金额" width="110">
            <template #default="{ row }">{{ money(row.payAmountFen) }}</template>
          </el-table-column>
          <el-table-column prop="userId" label="用户" width="90" />
          <el-table-column label="状态" width="110">
            <template #default="{ row }">
              <el-tag :type="row.status === 'COMPLETED' || row.status === 'PAID' ? 'success' : 'info'"
                      effect="plain">
                {{ statusLabel[row.status] || row.status }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="createdAt" label="创建时间" width="180" />
        </el-table>
        <div class="pager">
          <el-pagination v-model:current-page="orderQuery.pageNo" v-model:page-size="orderQuery.pageSize"
                         :total="orderTotal" layout="total, prev, pager, next" @current-change="loadOrders" />
        </div>
      </el-tab-pane>

      <el-tab-pane label="商品" name="products">
        <div class="toolbar">
          <el-input v-model="productQuery.keyword" placeholder="商品名称" style="width: 220px" clearable
                    @keyup.enter="loadProducts" />
          <el-button type="primary" @click="loadProducts">查询</el-button>
          <div class="spacer" />
          <el-button type="primary" @click="openCreateProduct">新建商品</el-button>
        </div>
        <el-table :data="products" border stripe>
          <el-table-column prop="code" label="编码" width="120" />
          <el-table-column prop="name" label="名称" min-width="180" />
          <el-table-column prop="type" label="类型" width="140" />
          <el-table-column label="价格" width="110">
            <template #default="{ row }">{{ money(row.priceFen) }}</template>
          </el-table-column>
          <el-table-column prop="validityDays" label="有效期(天)" width="110" />
          <el-table-column prop="status" label="状态" width="100" />
        </el-table>
        <div class="pager">
          <el-pagination v-model:current-page="productQuery.pageNo" v-model:page-size="productQuery.pageSize"
                         :total="productTotal" layout="total, prev, pager, next" @current-change="loadProducts" />
        </div>
      </el-tab-pane>

      <el-tab-pane label="退款" name="refunds">
        <div class="toolbar">
          <el-select v-model="refundQuery.status" placeholder="状态" style="width: 150px" clearable>
            <el-option v-for="(label, key) in refundStatusLabel" :key="key" :label="label" :value="key" />
          </el-select>
          <el-button type="primary" @click="loadRefunds">查询</el-button>
        </div>
        <el-table :data="refunds" border stripe>
          <el-table-column prop="refundNo" label="退款单号" width="230" />
          <el-table-column prop="orderNo" label="订单号" width="230" />
          <el-table-column label="退款金额" width="110">
            <template #default="{ row }">{{ money(row.amountFen) }}</template>
          </el-table-column>
          <el-table-column prop="reason" label="申请原因" min-width="140" show-overflow-tooltip />
          <el-table-column label="状态" width="110">
            <template #default="{ row }">
              <el-tag :type="row.status === 'SUCCESS' ? 'success' : row.status === 'REJECTED' ? 'info' : 'warning'"
                      effect="plain">
                {{ refundStatusLabel[row.status] || row.status }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="auditRemark" label="审核意见" min-width="140" show-overflow-tooltip />
          <el-table-column prop="appliedAt" label="申请时间" width="180" />
          <el-table-column label="操作" width="150" fixed="right">
            <template #default="{ row }">
              <template v-if="row.status === 'APPLIED'">
                <el-button link type="primary" @click="approveRefund(row)">通过</el-button>
                <el-button link type="danger" @click="rejectRefund(row)">驳回</el-button>
              </template>
              <span v-else>—</span>
            </template>
          </el-table-column>
        </el-table>
        <div class="pager">
          <el-pagination v-model:current-page="refundQuery.pageNo" v-model:page-size="refundQuery.pageSize"
                         :total="refundTotal" layout="total, prev, pager, next" @current-change="loadRefunds" />
        </div>
      </el-tab-pane>
    </el-tabs>

    <el-dialog v-model="productVisible" title="新建商品" width="560px">
      <el-form label-width="110px">
        <el-form-item label="编码" required>
          <el-input v-model="productForm.code" placeholder="如 QB-PMP" />
        </el-form-item>
        <el-form-item label="名称" required>
          <el-input v-model="productForm.name" />
        </el-form-item>
        <el-form-item label="类型">
          <el-select v-model="productForm.type" style="width: 200px">
            <el-option label="题库包" value="QUESTION_BANK" />
            <el-option label="会员" value="MEMBER" />
            <el-option label="课程" value="COURSE" />
          </el-select>
        </el-form-item>
        <el-form-item label="价格(分)">
          <el-input-number v-model="productForm.priceFen" :min="0" />
        </el-form-item>
        <el-form-item label="原价(分)">
          <el-input-number v-model="productForm.originalPriceFen" :min="0" />
        </el-form-item>
        <el-form-item label="有效期(天)">
          <el-input-number v-model="productForm.validityDays" :min="0" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="productForm.description" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="productVisible = false">取消</el-button>
        <el-button type="primary" @click="saveProduct">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>
