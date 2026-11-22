const app = getApp()
const { request } = require('../../utils/request')
const { formatDate, fen2yuan } = require('../../utils/format')

const ORDER_STATUS = {
  PENDING_PAYMENT: '待支付',
  PAID: '已支付',
  COMPLETED: '已完成',
  CLOSED: '已关闭',
  PARTIALLY_REFUNDED: '部分退款',
  REFUNDED: '已退款'
}

const REFUND_STATUS = {
  APPLIED: '审核中',
  SUCCESS: '退款成功',
  REJECTED: '已驳回',
  FAILED: '退款失败'
}

Page({
  data: {
    tab: 'orders',
    loading: false,
    paying: false,
    refunding: false,
    orders: [],
    refunds: []
  },

  onShow() {
    app.ready().then(() => this.load())
  },

  load() {
    this.setData({ loading: true })
    Promise.all([
      request({ url: '/orders?pageNo=1&pageSize=20' }),
      request({ url: '/refunds?pageNo=1&pageSize=20', showError: false })
    ])
      .then(([orderPage, refundPage]) => {
        this.setData({
          loading: false,
          orders: (orderPage.list || []).map((o) => ({
            ...o,
            statusText: ORDER_STATUS[o.status] || o.status,
            payText: fen2yuan(o.payAmountFen),
            totalText: fen2yuan(o.totalAmountFen),
            discountText: o.discountAmountFen ? fen2yuan(o.discountAmountFen) : '',
            createdText: formatDate(o.createdAt)
          })),
          refunds: (refundPage.list || []).map((r) => ({
            ...r,
            statusText: REFUND_STATUS[r.status] || r.status,
            amountText: fen2yuan(r.amountFen),
            appliedText: formatDate(r.appliedAt),
            auditText: formatDate(r.auditedAt)
          }))
        })
      })
      .catch(() => this.setData({ loading: false }))
  },

  switchTab(e) {
    this.setData({ tab: e.currentTarget.dataset.tab })
  },

  /** 待支付订单继续支付：与商城购买共用同一条支付链路。 */
  pay(e) {
    const id = e.currentTarget.dataset.id
    if (this.data.paying) return
    this.setData({ paying: true })
    request({ url: `/orders/${id}/payments`, method: 'POST' })
      .then((payment) => {
        if (payment.payParams && payment.payParams.paySign) {
          wx.showToast({ title: '请在微信中完成支付', icon: 'none' })
          return
        }
        if (payment.mockNotifyUrl) {
          return request({ url: payment.mockNotifyUrl, method: 'POST', showError: false })
        }
        return null
      })
      .then(() => {
        this.setData({ paying: false })
        this.load()
      })
      .catch(() => this.setData({ paying: false }))
  },

  /** 申请退款：金额与可退性由服务端按订单实付计算，这里只填理由。 */
  refund(e) {
    const id = e.currentTarget.dataset.id
    if (this.data.refunding) return
    wx.showModal({
      title: '申请退款',
      editable: true,
      placeholderText: '请填写退款原因',
      success: (res) => {
        if (!res.confirm) return
        const reason = (res.content || '').trim()
        if (!reason) {
          wx.showToast({ title: '请填写退款原因', icon: 'none' })
          return
        }
        this.setData({ refunding: true })
        request({ url: `/orders/${id}/refunds`, method: 'POST', data: { reason } })
          .then(() => {
            wx.showToast({ title: '已提交，等待审核', icon: 'success' })
            this.setData({ refunding: false })
            this.load()
          })
          .catch(() => this.setData({ refunding: false }))
      }
    })
  }
})
