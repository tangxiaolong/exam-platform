const app = getApp()
const config = require('../../config')
const { request } = require('../../utils/request')
const { fen2yuan } = require('../../utils/format')

function yuanText(fen) {
  const value = (fen || 0) / 100
  return Number.isInteger(value) ? String(value) : value.toFixed(2)
}

function couponText(c) {
  if (c.type === 'DISCOUNT') {
    const percent = (c.discountPercent || 100) / 10
    const cap = c.maxDiscountFen ? '，封顶' + yuanText(c.maxDiscountFen) + '元' : ''
    return percent + '折' + cap
  }
  const threshold = c.thresholdFen ? '满' + yuanText(c.thresholdFen) + '元' : '无门槛'
  return threshold + '减' + yuanText(c.valueFen) + '元'
}

Page({
  data: {
    products: [],
    loading: true,
    buying: false
  },

  onShow() {
    this.load()
  },

  load() {
    app
      .ready()
      .then(() => request({ url: '/products?pageNo=1&pageSize=20' }))
      .then((data) => {
        const products = (data.list || []).map((p) => ({
          ...p,
          priceText: fen2yuan(p.priceFen),
      typeText: { MEMBER: '会员', QUESTION_BANK: '题库包', COURSE: '课程', PACKAGE: '套餐' }[p.type] || p.type,
          originText: fen2yuan(p.originalPriceFen)
        }))
        this.setData({ products, loading: false })
      })
      .catch(() => this.setData({ loading: false }))
  },

  toCoupon() {
    wx.navigateTo({ url: '/pages/coupon/index' })
  },

  /** 选券：没有可用券直接返回 null；用户取消同样按不用券处理。 */
  pickCoupon() {
    return request({ url: '/coupons/mine?status=UNUSED', showError: false })
      .then(
        (grants) =>
          new Promise((resolve) => {
            const list = grants || []
            if (list.length === 0) {
              resolve(null)
              return
            }
            // 动作面板最多 6 项：超出只展示前 6 张，不打断购买主流程
            const items = list
              .slice(0, 6)
              .map((g) => (g.couponName || '优惠券') + ' · ' + couponText(g))
            wx.showActionSheet({
              alertText: '选择优惠券（取消则不用券）',
              itemList: items,
              success: (res) => resolve(list[res.tapIndex].grantId),
              fail: () => resolve(null)
            })
          })
      )
      .catch(() => null)
  },

  buy(e) {
    if (this.data.buying) return
    const productId = e.currentTarget.dataset.id
    this.setData({ buying: true })
    wx.showLoading({ title: '下单中' })

    this.pickCoupon()
      .then((grantId) => {
        const orderData = { productId, quantity: 1 }
        if (grantId) {
          orderData.couponGrantId = grantId
        }
        return request({ url: '/orders', method: 'POST', data: orderData })
      })
      .then((order) => request({ url: `/orders/${order.id}/payments`, method: 'POST' }))
      .then((payment) => {
        // 真实微信支付：payParams 是服务端用商户私钥二次签名后给出的参数（含 paySign），
        // 少这一步直接拿 prepay_id 拉起收银台一定报「签名错误」
        if (payment.payParams && payment.payParams.paySign) {
          wx.hideLoading()
          return this.payWithWechat(payment)
        }
        // 演示环境：模拟支付平台回调（验签、金额核对、权益发放走服务端同一条链路）
        if (payment.mockNotifyUrl) {
          return this.mockNotify(payment)
        }
        wx.hideLoading()
        wx.showModal({ title: '提示', content: '支付参数缺失，请联系管理员', showCancel: false })
        return { done: false }
      })
      .then((result) => {
        wx.hideLoading()
        this.setData({ buying: false })
        if (!result || result.done === false) return
        if (result.cancelled) {
          wx.showToast({ title: '已取消支付', icon: 'none' })
          return
        }
        wx.showModal({
          title: result.pending ? '支付已完成' : '支付成功',
          // 前端 success 只代表用户付完钱，到账与否以服务端回调/查单为准
          content: result.pending
            ? '支付已完成，权益将在收到支付平台通知后自动到账，可在「我的 · 我的权益」查看'
            : '权益已发放，可在「我的 · 我的权益」查看',
          showCancel: false
        })
      })
      .catch(() => {
        wx.hideLoading()
        this.setData({ buying: false })
      })
  },

  /** 调起微信收银台。用户取消不是异常，不能按失败处理并催他重试。 */
  payWithWechat(payment) {
    const params = payment.payParams
    return new Promise((resolve) => {
      wx.requestPayment({
        timeStamp: params.timeStamp,
        nonceStr: params.nonceStr,
        package: params.package,
        signType: params.signType || 'RSA',
        paySign: params.paySign,
        success: () => resolve({ done: true, pending: true }),
        fail: (err) => {
          const message = (err && err.errMsg) || ''
          if (message.indexOf('cancel') >= 0) {
            resolve({ done: true, cancelled: true })
            return
          }
          wx.showToast({ title: '支付未完成，请重试', icon: 'none' })
          resolve({ done: false })
        }
      })
    })
  },

  /** 演示环境：让后端走一遍与真实回调完全相同的处理链路。 */
  mockNotify(payment) {
    const origin = config.baseUrl.replace('/api/v1', '')
    return new Promise((resolve) => {
      wx.request({
        url: origin + payment.mockNotifyUrl,
        method: 'POST',
        header: { 'Content-Type': 'application/json' },
        data: {},
        success: () => resolve({ done: true }),
        fail: () => resolve({ done: true })
      })
    })
  }
})
