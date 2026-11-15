const app = getApp()
const { request, getUser } = require('../../utils/request')

Page({
  data: {
    projects: [],
    loading: true,
    nickname: '同学'
  },

  onShow() {
    const user = getUser()
    if (user && user.nickname) {
      this.setData({ nickname: user.nickname })
    }
    this.load()
  },

  load() {
    this.setData({ loading: true })
    app
      .ready()
      .then(() => request({ url: '/projects?pageNo=1&pageSize=20' }))
      .then((data) => {
        this.setData({ projects: data.list || [], loading: false })
      })
      .catch(() => this.setData({ loading: false }))
  },

  toProject(e) {
    const id = e.currentTarget.dataset.id
    wx.navigateTo({ url: '/pages/project/detail?id=' + id })
  },

  toExam() {
    wx.switchTab({ url: '/pages/exam/list' })
  },

  toMall() {
    wx.switchTab({ url: '/pages/mall/index' })
  }
})
