const app = getApp()
const { request } = require('../../utils/request')
const { formatDate } = require('../../utils/format')

Page({
  data: {
    loading: true,
    result: null,
    submittedText: '',
    statusText: ''
  },

  onLoad(query) {
    this.attemptId = query.attemptId
    app
      .ready()
      .then(() => request({ url: `/attempts/${this.attemptId}/result` }))
      .then((data) => {
        this.setData({
          loading: false,
          result: data,
          submittedText: formatDate(data.submittedAt),
          statusText: statusLabel(data.status)
        })
      })
      .catch(() => this.setData({ loading: false }))
  },

  backToList() {
    wx.reLaunch({ url: '/pages/exam/list' })
  }
})

function statusLabel(status) {
  switch (status) {
    case 'PUBLISHED':
      return '成绩已发布'
    case 'GRADED':
      return '成绩待发布'
    case 'REVIEWING':
      return '人工阅卷中'
    default:
      return status || ''
  }
}
