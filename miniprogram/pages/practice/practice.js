const app = getApp()
const { request } = require('../../utils/request')
const answerQueue = require('../../utils/answer-queue')

Page({
  data: {
    practiceId: null,
    subjectName: '',
    mode: 'SEQUENCE',
    questions: [],
    index: 0,
    current: null,
    selectedMap: {},
    result: null,
    answeredCount: 0,
    correctCount: 0,
    loading: true,
    favorited: {}
  },

  async loadFavorites() {
    try {
      const ids = await request({ url: '/favorites/ids', showError: false })
      const favorited = {}
      ;(ids || []).forEach((id) => { favorited[id] = true })
      this.setData({ favorited })
    } catch (e) {
      // 收藏状态加载失败不影响练习主流程
    }
  },

  toggleFavorite() {
    const current = this.data.current
    if (!current || !current.id) return
    const id = current.id
    const favorited = Object.assign({}, this.data.favorited)
    if (favorited[id]) {
      delete favorited[id]
      request({ url: '/favorites/' + id, method: 'DELETE' }).catch(() => {})
      wx.showToast({ title: '已取消收藏', icon: 'none' })
    } else {
      favorited[id] = true
      request({ url: '/favorites/' + id, method: 'POST' }).catch(() => {})
      wx.showToast({ title: '已收藏', icon: 'success' })
    }
    this.setData({ favorited })
  },

  createSession(query) {
    const body = {
      subjectId: Number(query.subjectId),
      mode: query.mode || 'SEQUENCE',
      limit: query.limit ? Math.min(Number(query.limit), 50) : 10
    }
    if (query.knowledgePointId) {
      body.knowledgePointId = Number(query.knowledgePointId)
    }
    return request({ url: '/practice/sessions', method: 'POST', data: body })
  },

  onLoad(query) {
    const isMock = query.mock === '1'
    this.setData({
      subjectName: decodeURIComponent(query.subjectName || ''),
      mode: query.mode || 'SEQUENCE',
      mock: isMock,
      mockRemain: isMock ? '参考时长 60 分钟' : ''
    })
    if (isMock) {
      // 模拟考试：参考限时仅作展示（到点提示，不强制交卷——无 PRD 强制依据）
      let remain = 60
      this.mockTimer = setInterval(() => {
        remain -= 1
        if (remain <= 0) {
          clearInterval(this.mockTimer)
          this.setData({ mockRemain: '参考时间已到' })
          wx.showToast({ title: '参考时间到，可继续完成作答', icon: 'none' })
          return
        }
        this.setData({ mockRemain: '参考时长剩余 ' + remain + ' 分钟' })
      }, 60000)
    }
    this.loadFavorites()
    wx.setNavigationBarTitle({ title: (query.subjectName || '练习') + ' · 练习' })

    app
      .ready()
      .then(() => {
        if (query.resume === '1') {
          // 断点续练（PRD 4.3）：优先恢复最近一次练习；无记录则照常新建
          return request({ url: '/practice/sessions/recent', showError: false })
            .then((data) => (data && data.practiceId ? data : null))
            .catch(() => null)
            .then((restored) => restored || this.createSession(query))
        }
        return this.createSession(query)
      })
      .then((data) => {
        const questions = (data.questions || []).map((q) => ({ ...q, options: q.options || [] }))
        this.setData({
          practiceId: data.practiceId,
          subjectName: data.subjectName || this.data.subjectName,
          questions,
          current: questions[0] || null,
          answeredCount: data.answeredCount || 0,
          correctCount: data.correctCount || 0,
          loading: false
        })
      })
      .catch(() => this.setData({ loading: false }))
  },

  choose(e) {
    if (this.data.result) return
    const key = e.currentTarget.dataset.key
    const type = this.data.current.type
    let selectedMap = { ...this.data.selectedMap }
    if (type === 'MULTIPLE') {
      if (selectedMap[key]) {
        delete selectedMap[key]
      } else {
        selectedMap[key] = true
      }
    } else {
      selectedMap = { [key]: true }
    }
    this.setData({ selectedMap })
  },

  selectedKeys() {
    return Object.keys(this.data.selectedMap)
  },

  onUnload() {
    if (this.mockTimer) clearInterval(this.mockTimer)
  },

  onShow() {
    // 从后台回来时把上次没交成功的答案补交掉
    if (this.data.practiceId) {
      this.flushQueue()
    }
  },

  submit() {
    const answer = this.selectedKeys()
    if (!answer.length) {
      wx.showToast({ title: '请先选择答案', icon: 'none' })
      return
    }
    const questionId = this.data.current.id
    request({
      url: `/practice/sessions/${this.data.practiceId}/answers?questionId=${questionId}`,
      method: 'POST',
      showError: false,
      data: { answer }
    })
      .then((res) => {
        this.setData({
          result: res,
          answeredCount: res.answeredCount,
          correctCount: res.correctCount
        })
      })
      .catch((err) => {
        // 业务错误（如场次已结束）不重放；网络失败才暂存，恢复后补交
        if (err && err.code) {
          wx.showToast({ title: err.message || '提交失败', icon: 'none' })
          return
        }
        answerQueue.enqueue({
          scope: 'practice',
          targetId: String(this.data.practiceId),
          questionId,
          answer,
          savedAt: Date.now()
        })
        this.setData({
          pendingCount: answerQueue.pending('practice', String(this.data.practiceId)).length
        })
        wx.showToast({ title: '网络异常，本题已暂存，恢复后自动补交', icon: 'none' })
      })
  },

  /** 重放练习的离线答案：练习不要求即时判定，补交成功即可推进进度。 */
  flushQueue() {
    const targetId = String(this.data.practiceId)
    const before = answerQueue.pending('practice', targetId).length
    if (!before) return Promise.resolve(0)
    return answerQueue
      .flush('practice', targetId, (item) =>
        request({
          url: `/practice/sessions/${targetId}/answers?questionId=${item.questionId}`,
          method: 'POST',
          showError: false,
          data: { answer: item.answer }
        })
      )
      .then((left) => {
        this.setData({ pendingCount: left })
        if (left === 0) {
          wx.showToast({ title: '离线答案已补交', icon: 'success' })
        }
        return left
      })
  },

  next() {
    const nextIndex = this.data.index + 1
    if (nextIndex >= this.data.questions.length) {
      wx.showModal({
        title: '练习完成',
        content: `共 ${this.data.questions.length} 题，答对 ${this.data.correctCount} 题`,
        showCancel: false,
        success: () => wx.navigateBack()
      })
      return
    }
    this.setData({
      index: nextIndex,
      current: this.data.questions[nextIndex],
      selectedMap: {},
      result: null
    })
  }
})
