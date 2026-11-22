const app = getApp()
const { request } = require('../../utils/request')

Page({
  data: {
    project: null,
    subjects: [],
    sessions: [],
    entitlement: null,
    kpMap: {},
    kpOpen: {}
  },

  onLoad(query) {
    this.projectId = query.id
    app
      .ready()
      .then(() => Promise.all([
        request({ url: '/projects/' + query.id }),
        request({ url: '/projects/' + query.id + '/subjects' }),
        request({ url: '/exam-sessions', showError: false }).catch(() => []),
        request({ url: '/products?pageNo=1&pageSize=50', showError: false }).catch(() => ({ list: [] }))
      ]))
      .then(([project, subjects, sessions, products]) => {
        this.setData({
          project,
          subjects,
          sessions: this.mapSessions(sessions, Number(query.id)),
          entitlement: this.buildEntitlement(products.list, Number(query.id), subjects)
        })
        wx.setNavigationBarTitle({ title: project.name })
      })
      .catch(() => undefined)
  },

  /** 该项目下的考试安排：场次全量列表按 projectId 过滤，时间/状态一眼可见。 */
  /** 价格与权益：关联本项目的题库包商品（resourceType=PROJECT + resourceId=项目），取最低价与权益摘要。 */
  buildEntitlement(products, projectId, subjects) {
    const banks = (products || []).filter(
      (p) => p.type === 'QUESTION_BANK' && p.resourceType === 'PROJECT' && Number(p.resourceId) === projectId
    )
    if (banks.length === 0) {
      return null
    }
    const minPriceFen = Math.min.apply(null, banks.map((p) => p.priceFen || 0))
    const maxValidityDays = Math.max.apply(null, banks.map((p) => p.validityDays || 0))
    const questionCount = (subjects || []).reduce((sum, s) => sum + (s.questionCount || 0), 0)
    const priceText = minPriceFen % 100 === 0 ? String(minPriceFen / 100) : (minPriceFen / 100).toFixed(2)
    return {
      priceText,
      count: banks.length,
      questionCount,
      maxValidityDays
    }
  },

  mapSessions(sessions, projectId) {
    const statusText = { PUBLISHED: '报名中', CLOSED: '已结束', DRAFT: '筹备中' }
    return (sessions || [])
      .filter((s) => Number(s.projectId) === projectId)
      .map((s) => ({
        id: s.id,
        name: s.name,
        startText: s.startAt ? String(s.startAt).replace('T', ' ').slice(0, 19) : '待定',
        statusText: statusText[s.status] || s.status || ''
      }))
  },

  startPractice(e) {
    const subjectId = e.currentTarget.dataset.id
    const mode = e.currentTarget.dataset.mode
    const mockQuery = e.currentTarget.dataset.mock === '1' ? '&mock=1&limit=50' : ''
    wx.navigateTo({
      url: `/pages/practice/practice?subjectId=${subjectId}&mode=${mode}&subjectName=${encodeURIComponent(e.currentTarget.dataset.name)}${mockQuery}`
    })
  },

  toggleKnowledge(e) {
    const subjectId = e.currentTarget.dataset.id
    const kpOpen = { ...this.data.kpOpen, [subjectId]: !this.data.kpOpen[subjectId] }
    this.setData({ kpOpen })
    if (kpOpen[subjectId] && !this.data.kpMap[subjectId]) {
      request({ url: '/subjects/' + subjectId + '/knowledge-points', showError: false })
        .then((list) => {
          this.setData({ kpMap: { ...this.data.kpMap, [subjectId]: list || [] } })
        })
        .catch(() => undefined)
    }
  },

  startKnowledgePractice(e) {
    const { subjectId, name, kpId, kpName } = e.currentTarget.dataset
    wx.navigateTo({
      url: `/pages/practice/practice?subjectId=${subjectId}&mode=SEQUENCE&subjectName=${encodeURIComponent(name)}&knowledgePointId=${kpId}&knowledgePointName=${encodeURIComponent(kpName || '')}`
    })
  },

  toExam() {
    wx.switchTab({ url: '/pages/exam/list' })
  },

  toCourses() {
    wx.navigateTo({ url: '/pages/course/list?projectId=' + this.projectId })
  },

  toMall() {
    wx.switchTab({ url: '/pages/mall/index' })
  }
})
