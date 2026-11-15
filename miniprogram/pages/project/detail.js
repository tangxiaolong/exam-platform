const app = getApp()
const { request } = require('../../utils/request')

Page({
  data: {
    project: null,
    subjects: []
  },

  onLoad(query) {
    this.projectId = query.id
    app
      .ready()
      .then(() => Promise.all([
        request({ url: '/projects/' + query.id }),
        request({ url: '/projects/' + query.id + '/subjects' })
      ]))
      .then(([project, subjects]) => {
        this.setData({ project, subjects })
        wx.setNavigationBarTitle({ title: project.name })
      })
      .catch(() => undefined)
  },

  startPractice(e) {
    const subjectId = e.currentTarget.dataset.id
    const mode = e.currentTarget.dataset.mode
    wx.navigateTo({
      url: `/pages/practice/practice?subjectId=${subjectId}&mode=${mode}&subjectName=${encodeURIComponent(e.currentTarget.dataset.name)}`
    })
  },

  toExam() {
    wx.switchTab({ url: '/pages/exam/list' })
  },

  toCourses() {
    wx.navigateTo({ url: '/pages/course/list?projectId=' + this.projectId })
  }
})
