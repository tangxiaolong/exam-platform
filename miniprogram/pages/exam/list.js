const app = getApp()
const { request } = require('../../utils/request')
const { formatDate } = require('../../utils/format')

Page({
  data: {
    sessions: [],
    loading: true
  },

  onShow() {
    this.load()
  },

  load() {
    this.setData({ loading: true })
    app
      .ready()
      .then(() => Promise.all([request({ url: '/exam-sessions' }), request({ url: '/attempts' })]))
      .then(([sessions, attempts]) => {
        const attemptMap = {}
        ;(attempts || []).forEach((a) => {
          if (!attemptMap[a.sessionId]) attemptMap[a.sessionId] = a
        })
        const regRequests = (sessions || []).map((s) =>
          request({ url: `/exam-sessions/${s.id}/registration`, showError: false }).catch(() => null)
        )
        return Promise.all(regRequests).then((regs) => {
          const list = (sessions || []).map((s, i) => ({
            ...s,
            startText: formatDate(s.startAt),
            endText: formatDate(s.endAt),
            registerEndAtText: s.registerEndAt ? formatDate(s.registerEndAt) : '不限',
            registration: regs[i],
            attempt: attemptMap[s.id] || null
          }))
          this.setData({ sessions: list, loading: false })
        })
      })
      .catch(() => this.setData({ loading: false }))
  },

  register(e) {
    const id = e.currentTarget.dataset.id
    request({ url: `/exam-sessions/${id}/registrations`, method: 'POST', data: { requestId: 'mp-' + id } })
      .then((data) => {
        wx.showToast({ title: '报名成功', icon: 'success' })
        wx.showModal({
          title: '准考信息',
          content: `准考证号：${data.ticketNo}\n开始时间：${formatDate(data.startAt)}`,
          showCancel: false
        })
        this.load()
      })
      .catch(() => undefined)
  },

  enterExam(e) {
    const { id, status, attemptId } = e.currentTarget.dataset
    if (status && status !== 'IN_PROGRESS') {
      wx.navigateTo({ url: '/pages/exam/result?attemptId=' + attemptId })
      return
    }
    wx.navigateTo({ url: '/pages/exam/taking?sessionId=' + id })
  },

  viewResult(e) {
    const attemptId = e.currentTarget.dataset.attempt
    wx.navigateTo({ url: '/pages/exam/result?attemptId=' + attemptId })
  }
})
