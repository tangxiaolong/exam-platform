const app = getApp()
const { request, getUser } = require('../../utils/request')

Page({
  data: {
    projects: [],
    filtered: [],
    notices: [],
    activities: [],
    keyword: '',
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
      .then(() =>
        Promise.all([
          request({ url: '/projects?pageNo=1&pageSize=20' }),
          request({ url: '/notices', showError: false }).catch(() => []),
          request({ url: '/notices?type=ACTIVITY&pageNo=1&pageSize=2', showError: false }).catch(() => [])
        ])
      )
      .then(([data, notices, activities]) => {
        const projects = data.list || []
        this.setData({
          projects,
          filtered: this.applyFilter(projects, this.data.keyword),
          notices: (notices || []).slice(0, 3).map((n) => ({
            id: n.id,
            title: n.title || '（无标题）'
          })),
          activities: (activities || []).slice(0, 2).map((n) => ({
            id: n.id,
            title: n.title || '（无标题）'
          })),
          loading: false
        })
      })
      .catch(() => this.setData({ loading: false }))
  },

  applyFilter(projects, keyword) {
    const kw = (keyword || '').trim().toLowerCase()
    if (!kw) return projects
    return projects.filter((p) => {
      const text = ((p.name || '') + ' ' + (p.code || '') + ' ' + (p.intro || '')).toLowerCase()
      return text.indexOf(kw) >= 0
    })
  },

  onSearchInput(e) {
    const keyword = e.detail.value || ''
    this.setData({
      keyword,
      filtered: this.applyFilter(this.data.projects, keyword)
    })
  },

  toNoticePage() {
    wx.navigateTo({ url: '/pages/notice/index' })
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
  },

  toWrong() {
    wx.navigateTo({ url: '/pages/wrong/index' })
  },

  toFavorite() {
    wx.navigateTo({ url: '/pages/favorite/index' })
  },

  toPracticeHistory() {
    wx.navigateTo({ url: '/pages/practice/history' })
  }
})
