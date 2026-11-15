const app = getApp()
const config = require('../../config')
const { request } = require('../../utils/request')
const { fen2yuan } = require('../../utils/format')

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
          originText: fen2yuan(p.originalPriceFen)
        }))
        this.setData({ products, loading: false })
      })
      .catch(() => this.setData({ loading: false }))
  },

  buy(e) {
    if (this.data.buying) return
    const productId = e.currentTarget.dataset.id
    this.setData({ buying: true })
    wx.showLoading({ title: '下单中' })

    request({ url: '/orders', method: 'POST', data: { productId, quantity: 1 } })
      .then((order) => request({ url: `/orders/${order.id}/payments`, method: 'POST' }))
      .then((payment) => {
        // 演示环境：模拟支付平台回调（验签、金额核对、权益发放走服务端同一条链路）
        if (!payment.mockNotifyUrl) {
          wx.hideLoading()
          wx.showModal({ title: '提示', content: '未配置模拟支付，请接入真实微信支付', showCancel: false })
          return null
        }
        const origin = config.baseUrl.replace('/api/v1', '')
        return new Promise((resolve) => {
          wx.request({
            url: origin + payment.mockNotifyUrl,
            method: 'POST',
            header: { 'Content-Type': 'application/json' },
            data: {},
            success: resolve,
            fail: resolve
          })
        })
      })
      .then(() => {
        wx.hideLoading()
        this.setData({ buying: false })
        wx.showModal({
          title: '支付成功',
          content: '权益已发放，可在「我的 · 我的权益」查看',
          showCancel: false
        })
      })
      .catch(() => {
        wx.hideLoading()
        this.setData({ buying: false })
      })
  }
})
