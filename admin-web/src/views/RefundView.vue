<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { refundApi, type RefundItem } from '@/api'

const list = ref<RefundItem[]>([])
const total = ref(0)
const loading = ref(false)
const query = ref({ pageNo: 1, pageSize: 10, status: '' })

async function load() {
  loading.value = true
  try {
    const params: Record<string, any> = {
      pageNo: query.value.pageNo,
      pageSize: query.value.pageSize
    }
    if (query.value.status) {
      params.status = query.value.status
    }
    const page = await refundApi.page(params)
    list.value = page.list
    total.value = page.total
  } finally {
    loading.value = false
  }
}

function money(fen?: number) {
  return ((fen || 0) / 100).toFixed(2)
}

async function audit(row: RefundItem, approve: boolean) {
  const label = approve ? '同意退款' : '拒绝退款'
  try {
    const { value } = await ElMessageBox.prompt(
      `确认${label}「${row.refundNo}」？此操作不可撤回。`,
      label,
      {
        inputPlaceholder: '备注（可选，会记入审核记录）',
        inputType: 'textarea'
      }
    )
    if (approve) {
      await refundApi.approve(row.id as number, value)
    } else {
      await refundApi.reject(row.id as number, value)
    }
    ElMessage.success(approve ? '已同意退款' : '已拒绝退款')
    await load()
  } catch {
    // 用户取消弹框：什么都不做
  }
}

onMounted(load)
</script>

<template>
  <div class="page-card">
    <h3 class="page-title">退款审核</h3>
    <p class="page-desc">
      同意退款会调用原路退回并回收未使用的权益；已处理的单子不再显示审批按钮。
    </p>

    <div class="toolbar">
      <el-select v-model="query.status" placeholder="状态" style="width: 180px" clearable @change="load">
        <el-option label="待审核" value="APPLIED" />
        <el-option label="已通过" value="SUCCESS" />
        <el-option label="已拒绝" value="REJECTED" />
      </el-select>
      <el-button type="primary" @click="load">查询</el-button>
    </div>

    <el-table v-loading="loading" :data="list" border stripe>
      <el-table-column prop="refundNo" label="退款单号" min-width="170" />
      <el-table-column prop="orderNo" label="订单号" min-width="170" />
      <el-table-column label="金额（元）" width="110">
        <template #default="{ row }">{{ money(row.amountFen) }}</template>
      </el-table-column>
      <el-table-column prop="reason" label="申请原因" min-width="140" />
      <el-table-column label="状态" width="110">
        <template #default="{ row }">
          <el-tag :type="row.status === 'SUCCESS' ? 'success' : row.status === 'REJECTED' ? 'info' : 'warning'">
            {{ row.status }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="appliedAt" label="申请时间" width="165" />
      <el-table-column label="审核时间" width="165">
        <template #default="{ row }">{{ row.auditedAt || '—' }}</template>
      </el-table-column>
      <el-table-column label="操作" width="150" fixed="right">
        <template #default="{ row }">
          <!-- 用 auditedAt 判断是否已处理：申请时为空，审核后才落值，
               比解读 status 的枚举更稳 -->
          <template v-if="!row.auditedAt">
            <el-button link type="success" @click="audit(row, true)">通过</el-button>
            <el-button link type="danger" @click="audit(row, false)">拒绝</el-button>
          </template>
          <span v-else style="color: #999;">已处理</span>
        </template>
      </el-table-column>
    </el-table>

    <div class="pager">
      <el-pagination v-model:current-page="query.pageNo" v-model:page-size="query.pageSize"
                     :total="total" layout="total, prev, pager, next" @current-change="load" />
    </div>
  </div>
</template>
