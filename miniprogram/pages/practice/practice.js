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
    loading: true
  },

  onLoad(query) {
    this.setData({
      subjectName: decodeURIComponent(query.subjectName || ''),
      mode: query.mode || 'SEQUENCE'
    })
    wx.setNavigationBarTitle({ title: (query.subjectName || '练习') + ' · 练习' })

    app
      .ready()
      .then(() =>
        request({
          url: '/practice/sessions',
          method: 'POST',
          data: { subjectId: Number(query.subjectId), mode: query.mode || 'SEQUENCE', limit: 10 }
        })
      )
      .then((data) => {
        const questions = (data.questions || []).map((q) => ({ ...q, options: q.options || [] }))
        this.setData({
          practiceId: data.practiceId,
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
