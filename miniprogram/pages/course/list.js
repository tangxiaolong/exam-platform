const app = getApp()
const { request } = require('../../utils/request')
const { fen2yuan } = require('../../utils/format')

Page({
  data: {
    courses: [],
    loading: true,
    projectId: null
  },

  onLoad(query) {
    if (query.projectId) {
      this.setData({ projectId: Number(query.projectId) })
    }
    this.load()
  },

  onShow() {
    if (!this.data.loading) this.load()
  },

  load() {
    const url = this.data.projectId
      ? `/courses?projectId=${this.data.projectId}&pageNo=1&pageSize=20`
      : '/courses?pageNo=1&pageSize=20'
    app
      .ready()
      .then(() => request({ url }))
      .then((data) => {
        const courses = (data.list || []).map((c) => ({
          ...c,
          priceText: c.accessType === 'FREE' ? '免费' : fen2yuan(c.priceFen)
        }))
        this.setData({ courses, loading: false })
      })
      .catch(() => this.setData({ loading: false }))
  },

  toDetail(e) {
    wx.navigateTo({ url: '/pages/course/detail?id=' + e.currentTarget.dataset.id })
  }
})
