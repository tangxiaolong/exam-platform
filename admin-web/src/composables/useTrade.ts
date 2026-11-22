import { computed, ref } from 'vue'
import { tradeApi, type TradeOverview } from '@/api'

type TagType = 'primary' | 'success' | 'info' | 'warning' | 'danger'

/**
 * 交易域统一的状态字典。
 *
 * <p>之前每个页面各写一份：订单页把 `REFUNDED` 显示成原始枚举、退款页把 `SUCCESS`
 * 直接吐给运营看、券页在「已核销」和「已使用」之间来回摇摆。同一套枚举在四个页面
 * 有四种叫法，客服照着页面念出来的词在审计日志里根本搜不到。集中在这里，改一处全站生效。</p>
 */
export const ORDER_STATUS: Record<string, { text: string; type: TagType }> = {
  CREATED: { text: '已创建', type: 'info' },
  PENDING_PAYMENT: { text: '待支付', type: 'warning' },
  PAID: { text: '已支付', type: 'success' },
  COMPLETED: { text: '已完成', type: 'success' },
  PARTIALLY_REFUNDED: { text: '部分退款', type: 'warning' },
  REFUNDED: { text: '已退款', type: 'info' },
  CLOSED: { text: '已关闭', type: 'info' }
}

export const REFUND_STATUS: Record<string, { text: string; type: TagType }> = {
  APPLIED: { text: '待审核', type: 'warning' },
  SUCCESS: { text: '已退款', type: 'success' },
  REJECTED: { text: '已驳回', type: 'info' },
  FAILED: { text: '退款失败', type: 'danger' }
}

export const COUPON_STATUS: Record<string, { text: string; type: TagType }> = {
  ACTIVE: { text: '上架中', type: 'success' },
  STOPPED: { text: '已停发', type: 'info' }
}

export const GRANT_STATUS: Record<string, { text: string; type: TagType }> = {
  UNUSED: { text: '未使用', type: 'success' },
  LOCKED: { text: '下单锁定', type: 'warning' },
  USED: { text: '已核销', type: 'info' },
  EXPIRED: { text: '已过期', type: 'danger' }
}

export const PRODUCT_STATUS: Record<string, { text: string; type: TagType }> = {
  ON_SALE: { text: '在售', type: 'success' },
  OFF_SALE: { text: '已下架', type: 'info' }
}

export const PRODUCT_TYPE: Record<string, string> = {
  MEMBER: '会员',
  QUESTION_BANK: '题库包',
  COURSE: '课程',
  PACKAGE: '套餐'
}

/** 分 → 元，保留两位小数；空值按 0 处理，避免表格里出现 `¥NaN` */
export function yuan(fen?: number | null): string {
  return ((fen || 0) / 100).toFixed(2)
}

/** 分 → 带符号的元，用于金额列 */
export function money(fen?: number | null): string {
  return '¥' + yuan(fen)
}

/** 元（输入框里的浮点）→ 分，四舍五入到整数，避免浮点误差入库 */
export function toFen(value?: number | null): number {
  return Math.round((value || 0) * 100)
}

/**
 * 概览指标。
 *
 * <p>失败不阻断页面：指标条只是辅助信息，拿不到就不显示，列表本身照常可用。</p>
 */
export function useTradeOverview() {
  const overview = ref<TradeOverview | null>(null)
  const loading = ref(false)

  async function loadOverview() {
    loading.value = true
    try {
      overview.value = await tradeApi.overview()
    } catch {
      overview.value = null
    } finally {
      loading.value = false
    }
  }

  const pendingRefund = computed(() => overview.value?.refundPending ?? 0)

  return { overview, loading, loadOverview, pendingRefund }
}
