const app = getApp()
const { request, getUser, clearAuth } = require('../../utils/request')
const { formatDate } = require('../../utils/format')

Page({
  data: {
    user: null,
    avatarText: '考',
    attempts: [],
    entitlements: [],
    wrongCount: 0,
    loading: true,
    statusText: {
      IN_PROGRESS: '进行中',
      SUBMITTED: '已交卷',
      REVIEWING: '阅卷中',
      GRADED: '已判分',
      EXPIRED: '已过期'
    }
  },

  onShow() {
    this.load()
  },

  load() {
    this.setData({ loading: true, user: getUser() })
    app
      .ready()
      .then(() =>
        Promise.all([
          request({ url: '/users/me' }),
          request({ url: '/attempts' }),
          request({ url: '/entitlements' }),
          request({ url: '/wrong-questions', showError: false }).catch(() => [])
        ])
      )
      .then(([user, attempts, entitlements, wrong]) => {
        const displayName = (user && (user.nickname || user.username)) || '考'
        this.setData({
          user,
          avatarText: displayName.charAt(0),
          attempts: (attempts || []).map((a) => ({
            ...a,
            submittedText: a.submittedAt ? formatDate(a.submittedAt) : '未交卷'
          })),
          entitlements: entitlements || [],
          wrongCount: (wrong || []).length,
          loading: false
        })
      })
      .catch(() => this.setData({ loading: false }))
  },

  toResult(e) {
    const attemptId = e.currentTarget.dataset.id
    wx.navigateTo({ url: '/pages/exam/result?attemptId=' + attemptId })
  },

  toExam() {
    wx.switchTab({ url: '/pages/exam/list' })
  },

  toWrong() {
    wx.showToast({ title: `共有 ${this.data.wrongCount} 道错题`, icon: 'none' })
  },

  logout() {
    wx.showModal({
      title: '提示',
      content: '确认退出当前账号？',
      success: (res) => {
        if (!res.confirm) return
        clearAuth()
        wx.reLaunch({ url: '/pages/index/index' })
      }
    })
  }
})
