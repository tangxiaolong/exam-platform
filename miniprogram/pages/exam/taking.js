const app = getApp()
const { request } = require('../../utils/request')
const { formatDuration, toTimestamp } = require('../../utils/format')
const answerQueue = require('../../utils/answer-queue')

Page({
  data: {
    loading: true,
    attemptId: null,
    sessionName: '',
    noticeContent: '',
    questions: [],
    index: 0,
    current: null,
    selectedMap: {},
    remainText: '--:--:--',
    savedCount: 0,
    pendingCount: 0,
    answeredMap: {},
    essayText: '',
    submitting: false
  },

  onLoad(query) {
    this.sessionId = query.sessionId
    this.answers = {}
    this.versions = {}
    this.submitted = false

    // 断网期间暂存的答案，在网络恢复的那一刻自动补交
    this.networkListener = (res) => {
      if (res.isConnected && this.data.attemptId) {
        this.flushQueue()
      }
    }
    wx.onNetworkStatusChange(this.networkListener)

    app
      .ready()
      .then(() =>
        request({ url: `/exam-sessions/${this.sessionId}/attempts`, method: 'POST' })
      )
      .then((data) => this.initData(data))
      .catch(() => this.setData({ loading: false }))
  },

  onShow() {
    // 从小程序切后台再回来、或刚进入页面：把上次没交成功的答案补交掉
    if (this.data.attemptId) {
      this.flushQueue()
    }
  },

  onUnload() {
    if (this.timer) clearInterval(this.timer)
    if (this.saveTimer) clearTimeout(this.saveTimer)
    if (this.essayTimer) clearTimeout(this.essayTimer)
    if (this.networkListener) wx.offNetworkStatusChange(this.networkListener)
  },

  initData(data) {
    // 以服务端时间校准倒计时，客户端计时只用于展示
    this.serverOffset = toTimestamp(data.serverTime) - Date.now()
    this.deadline = toTimestamp(data.deadlineAt)

    // 后端考试题目字段为 questionId，这里统一补一个 id 便于下游按题处理，
    // 同时保留 questionId 供保存答案时提交
    const questions = (data.questions || []).map((q) => ({
      ...q,
      id: q.questionId != null ? q.questionId : q.id,
      options: q.options || []
    }))
    const answers = {}
    const versions = {}
    const answeredMap = {}
    ;(data.answers || []).forEach((a) => {
      answers[a.questionId] = a.answer || []
      versions[a.questionId] = a.version
      if ((a.answer || []).length) answeredMap[a.questionId] = true
    })
    this.answers = answers
    this.versions = versions

    this.setData({
      loading: false,
      attemptId: data.attemptId,
      sessionName: data.sessionName,
      noticeContent: data.noticeContent,
      questions,
      current: questions[0] || null,
      selectedMap: this.buildSelectedMap(questions[0], answers),
      answeredMap,
      savedCount: Object.keys(answers).length
    })
    if (data.status !== 'IN_PROGRESS') {
      wx.redirectTo({ url: '/pages/exam/result?attemptId=' + data.attemptId })
      return
    }
    this.startTimer()
  },

  buildSelectedMap(question, answers) {
    const map = {}
    if (!question) return map
    ;(answers[question.id] || []).forEach((key) => {
      map[key] = true
    })
    return map
  },

  startTimer() {
    const tick = () => {
      const remain = Math.floor((this.deadline - (Date.now() + this.serverOffset)) / 1000)
      if (remain <= 0) {
        clearInterval(this.timer)
        this.setData({ remainText: '00:00' })
        wx.showToast({ title: '考试时间到，正在自动交卷', icon: 'none' })
        this.doSubmit()
        return
      }
      this.setData({ remainText: formatDuration(remain) })
    }
    tick()
    this.timer = setInterval(tick, 1000)
  },

  choose(e) {
    const key = e.currentTarget.dataset.key
    const question = this.data.current
    const type = question.type
    const selectedMap = { ...this.data.selectedMap }
    let answer = (this.answers[question.id] || []).slice()

    if (type === 'MULTIPLE') {
      if (selectedMap[key]) {
        delete selectedMap[key]
        answer = answer.filter((k) => k !== key)
      } else {
        selectedMap[key] = true
        answer.push(key)
      }
    } else {
      Object.keys(selectedMap).forEach((k) => delete selectedMap[k])
      selectedMap[key] = true
      answer = [key]
    }

    this.answers[question.id] = answer
    const answeredMap = { ...this.data.answeredMap }
    if (answer.length) {
      answeredMap[question.id] = true
    } else {
      delete answeredMap[question.id]
    }
    this.setData({ selectedMap, answeredMap })
    this.scheduleSave(question.id)
  },

  /** 主观题作答：本地实时回显，停止输入 1 秒后按批次落库 */
  onEssayInput(e) {
    const question = this.data.current
    const text = e.detail.value
    this.answers[question.id] = text.trim() ? [text] : []
    const answeredMap = { ...this.data.answeredMap }
    if (text.trim()) {
      answeredMap[question.id] = true
    } else {
      delete answeredMap[question.id]
    }
    this.setData({ essayText: text, answeredMap })
    if (this.essayTimer) clearTimeout(this.essayTimer)
    this.essayTimer = setTimeout(() => this.save(question.id), 1000)
  },

  scheduleSave(questionId) {
    if (this.saveTimer) clearTimeout(this.saveTimer)
    this.saveTimer = setTimeout(() => this.save(questionId), 400)
  },

  save(questionId, silent = true) {
    const answer = this.answers[questionId] || []
    return request({
      url: `/attempts/${this.data.attemptId}/answers/${questionId}`,
      method: 'PUT',
      showError: !silent,
      data: {
        answer,
        clientVersion: this.versions[questionId] || null,
        requestId: 'mp-' + this.data.attemptId + '-' + questionId + '-' + Date.now()
      }
    })
      .then((res) => {
        if (res.saved) {
          this.versions[questionId] = res.serverVersion
        } else {
          // 服务端保留了更新的一版：本地这份是旧的，必须让用户知道自己被覆盖了
          this.onAnswerConflict(questionId)
        }
        this.setData({ savedCount: Object.keys(this.answers).length })
      })
      .catch((err) => {
        // 带 code 的是业务错误（如已交卷），重放也不会成功，不必暂存；
        // 只有网络类失败才入队——原实现这里直接吞掉异常，等于断网就丢答案
        if (err && err.code) {
          return undefined
        }
        this.queueAnswer(questionId, answer)
        return undefined
      })
  },

  /** 网络失败时暂存到本地队列，等网络恢复后自动补交。 */
  queueAnswer(questionId, answer) {
    answerQueue.enqueue({
      scope: 'attempt',
      targetId: String(this.data.attemptId),
      questionId,
      answer,
      clientVersion: this.versions[questionId] || null,
      savedAt: Date.now()
    })
    const left = answerQueue.pending('attempt', String(this.data.attemptId)).length
    this.setData({ pendingCount: left })
    if (left === 1) {
      wx.showToast({ title: '网络异常，答案已暂存本地', icon: 'none' })
    }
  },

  /** 重放离线队列：成功一条出队一条，仍在断网则整轮保留。 */
  flushQueue() {
    if (!this.data.attemptId) return Promise.resolve(0)
    const targetId = String(this.data.attemptId)
    const before = answerQueue.pending('attempt', targetId).length
    return answerQueue
      .flush(
        'attempt',
        targetId,
        (item) =>
          request({
            url: `/attempts/${targetId}/answers/${item.questionId}`,
            method: 'PUT',
            showError: false,
            data: {
              answer: item.answer,
              clientVersion: item.clientVersion,
              requestId: 'mp-replay-' + item.questionId + '-' + Date.now()
            }
          }),
        (item) => this.onAnswerConflict(item.questionId)
      )
      .then((left) => {
        this.setData({ pendingCount: left, savedCount: Object.keys(this.answers).length })
        if (before > 0 && left === 0) {
          wx.showToast({ title: '离线答案已全部同步', icon: 'success' })
        }
        return left
      })
  },

  /** 版本冲突：以服务端为准刷新该题，并明确告知用户答案被服务端覆盖。 */
  onAnswerConflict(questionId) {
    return request({ url: `/attempts/${this.data.attemptId}/resume`, showError: false })
      .then((data) => {
        const serverAnswer = (data.answers || []).find((a) => a.questionId === questionId)
        if (!serverAnswer) return
        const serverKeys = serverAnswer.answer || []
        this.answers[questionId] = serverKeys
        this.versions[questionId] = serverAnswer.version
        const answeredMap = { ...this.data.answeredMap }
        if (serverKeys.length) {
          answeredMap[questionId] = true
        } else {
          delete answeredMap[questionId]
        }
        const patch = { answeredMap }
        const current = this.data.current
        if (current && current.id === questionId) {
          patch.selectedMap = this.buildSelectedMap(current, { [questionId]: serverKeys })
          if (current.type === 'ESSAY') {
            patch.essayText = serverKeys[0] || ''
          }
        }
        this.setData(patch)
        wx.showToast({ title: '该题已同步服务端最新答案', icon: 'none' })
      })
      .catch(() => undefined)
  },

  switchTo(e) {
    const index = Number(e.currentTarget.dataset.index)
    const question = this.data.questions[index]
    this.setData({
      index,
      current: question,
      selectedMap: this.buildSelectedMap(question, this.answers),
      essayText: question && question.type === 'ESSAY' ? (this.answers[question.id] || [''])[0] || '' : ''
    })
  },

  prev() {
    if (this.data.index === 0) return
    this.switchTo({ currentTarget: { dataset: { index: this.data.index - 1 } } })
  },

  next() {
    if (this.data.index >= this.data.questions.length - 1) {
      wx.showToast({ title: '已是最后一题', icon: 'none' })
      return
    }
    this.switchTo({ currentTarget: { dataset: { index: this.data.index + 1 } } })
  },

  confirmSubmit() {
    const unanswered = this.data.questions.filter((q) => !(this.answers[q.id] || []).length).length
    wx.showModal({
      title: '确认交卷',
      content: unanswered > 0 ? `还有 ${unanswered} 题未作答，交卷后不能再修改答案。` : '交卷后不能再修改答案，确认交卷？',
      success: (res) => {
        if (!res.confirm) return
        // 交卷前先把离线暂存的答案补交掉：否则服务端收到的还是断网前那一版
        if (this.data.pendingCount > 0) {
          wx.showLoading({ title: '正在同步离线答案' })
        }
        this.flushQueue().then(() => {
          if (this.data.pendingCount > 0) {
            wx.hideLoading()
          }
          this.doSubmit()
        })
      }
    })
  },

  doSubmit() {
    if (this.data.submitting || this.submitted) return
    this.submitted = true
    this.setData({ submitting: true })
    if (this.timer) clearInterval(this.timer)
    if (this.saveTimer) clearTimeout(this.saveTimer)

    request({
      url: `/attempts/${this.data.attemptId}/submit`,
      method: 'POST',
      data: { requestId: 'mp-submit-' + this.data.attemptId, reason: 'USER_SUBMIT' }
    })
      .then((res) => {
        // 交卷成功即清空该场次的离线队列，避免下一场考试把上一场的答案重放出去
        answerQueue.clear('attempt', String(this.data.attemptId))
        wx.redirectTo({ url: '/pages/exam/result?attemptId=' + res.attemptId })
      })
      .catch(() => {
        this.submitted = false
        this.setData({ submitting: false })
      })
  }
})
