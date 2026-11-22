const app = getApp()
const { request } = require('../../utils/request')
const { formatDate } = require('../../utils/format')

const GRANT_STATUS = {
  UNUSED: '未使用',
  LOCKED: '下单锁定中',
  USED: '已核销',
  EXPIRED: '已过期'
}

function yuan(fen) {
  const value = (fen || 0) / 100
  return Number.isInteger(value) ? String(value) : value.toFixed(2)
}

function couponText(c) {
  if (c.type === 'DISCOUNT') {
    const percent = (c.discountPercent || 100) / 10
    const cap = c.maxDiscountFen ? '，封顶' + yuan(c.maxDiscountFen) + '元' : ''
    return percent + '折' + cap
  }
  const threshold = c.thresholdFen ? '满' + yuan(c.thresholdFen) + '元' : '无门槛'
  return threshold + '减' + yuan(c.valueFen) + '元'
}

Page({
  data: {
    tab: 'claimable',
    loading: false,
    claiming: false,
    claimable: [],
    mine: []
  },

  onShow() {
    app.ready().then(() => this.load())
  },

  load() {
    this.setData({ loading: true })
    Promise.all([
      request({ url: '/coupons/claimable' }),
      request({ url: '/coupons/mine', showError: false })
    ])
      .then(([claimable, mine]) => {
        this.setData({
          loading: false,
          claimable: (claimable || []).map((c) => ({
            ...c,
            text: couponText(c),
            period: formatDate(c.validFrom) + ' 至 ' + formatDate(c.validTo)
          })),
          mine: (mine || []).map((g) => ({
            ...g,
            text: couponText(g),
            statusText: GRANT_STATUS[g.status] || g.status,
            grantedText: formatDate(g.grantedAt)
          }))
        })
      })
      .catch(() => this.setData({ loading: false }))
  },

  switchTab(e) {
    this.setData({ tab: e.currentTarget.dataset.tab })
  },

  claim(e) {
    const code = e.currentTarget.dataset.code
    if (this.data.claiming) return
    this.setData({ claiming: true })
    request({ url: '/coupons/' + code + '/claim', method: 'POST' })
      .then(() => {
        wx.showToast({ title: '领取成功', icon: 'success' })
        this.setData({ claiming: false })
        this.load()
      })
      .catch(() => this.setData({ claiming: false }))
  }
})
