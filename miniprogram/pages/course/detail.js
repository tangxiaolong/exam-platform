const app = getApp()
const { request } = require('../../utils/request')
const { formatDuration, fen2yuan } = require('../../utils/format')

Page({
  data: {
    loading: true,
    course: null,
    lessons: [],
    current: null,
    accessible: false,
    buying: false
  },

  onLoad(query) {
    this.courseId = query.id
    app
      .ready()
      .then(() => request({ url: '/courses/' + this.courseId }))
      .then((data) => this.applyDetail(data))
      .catch(() => this.setData({ loading: false }))
  },

  onShow() {
    // 从商城购买返回后刷新解锁状态
    if (this.courseId && !this.data.loading) {
      request({ url: '/courses/' + this.courseId }).then((data) => this.applyDetail(data))
    }
  },

  applyDetail(data) {
    const lessons = (data.lessons || []).map((item) => ({
      ...item,
      durationText: formatDuration(item.durationSeconds || 0)
    }))
    const current = lessons.find((item) => !item.locked) || lessons[0] || null
    this.setData({
      loading: false,
      course: data,
      lessons,
      accessible: data.accessible,
      current
    })
    if (data.title) {
      wx.setNavigationBarTitle({ title: data.title })
    }
  },

  play(e) {
    const id = e.currentTarget.dataset.id
    const lesson = this.data.lessons.find((item) => item.id === id)
    if (!lesson) return
    if (lesson.locked) {
      wx.showModal({
        title: '尚未开通',
        content: '该课时需购买课程后观看，是否前往商城？',
        confirmText: '去购买',
        success: (res) => {
          if (res.confirm) this.toMall()
        }
      })
      return
    }
    this.setData({ current: lesson })
  },

  markFinished() {
    const lesson = this.data.current
    if (!lesson) return
    request({
      url: `/courses/${this.courseId}/lessons/${lesson.id}/progress?progressSeconds=${lesson.durationSeconds || 0}&finished=true`,
      method: 'POST'
    }).then(() => {
      wx.showToast({ title: '已标记完成', icon: 'success' })
      return request({ url: '/courses/' + this.courseId })
    }).then((data) => this.applyDetail(data))
      .catch(() => undefined)
  },

  buy() {
    if (this.data.buying) return
    if (!this.data.course) return
    wx.showModal({
      title: '开通课程',
      content: `课程价格 ${fen2yuan(this.data.course.priceFen)}，购买后可在「商城」完成支付。`,
      confirmText: '去商城',
      success: (res) => {
        if (res.confirm) this.toMall()
      }
    })
  },

  toMall() {
    wx.switchTab({ url: '/pages/mall/index' })
  }
})
