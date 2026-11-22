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
    answeredCount: 0,
    essayText: '',
    submitting: false,
    // 底部抽屉答题卡开关
    drawerVisible: false
  },

  onLoad(query) {
    this.sessionId = query.sessionId
    this.answers = {}
    this.versions = {}
    this.submitted = false
    // 已改动但还没落库的题号。用集合而不是「一个定时器 + 一个题号」：
    // 后者在连续答几题时，后一次会把前一次的定时器清掉，前一题就再也不会被发出，
    // 交卷也不会带上它——这属于「答案丢失」，不是「保存慢一点」
    this.dirty = new Set()

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
    // 从小程序切后台再回来、或刚进入页面：把上次没交成功的答案补交掉。
    // 先冲内存里待落库的，再冲离线队列——顺序反了会把同一题的两个版本按旧的后写
    if (this.data.attemptId) {
      this.flushDirty()
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
      answeredCount: Object.keys(answeredMap).length,
      savedCount: Object.keys(answers).length
    })
    if (data.status !== 'IN_PROGRESS') {
      wx.redirectTo({ url: '/pages/exam/result?attemptId=' + data.attemptId })
      return
    }
    this.restoreDraft()
    this.startTimer()
  },

  draftKey() {
    return 'exam_draft_' + (this.data.attemptId || this.sessionId)
  },

  /**
   * 本地草稿：答案在 400ms 防抖窗口内还没落库、或进程直接被系统杀掉时，
   * 内存里的选择不至于彻底丢失（PRD 4.5 中断不丢答案的最后一道兜底）。
   */
  writeDraft(questionId) {
    if (!this.data.attemptId) return
    try {
      const key = this.draftKey()
      const draft = wx.getStorageSync(key) || {}
      draft[questionId] = { answer: this.answers[questionId] || [], at: Date.now() }
      wx.setStorageSync(key, draft)
    } catch (e) {
      // 本地存储不可用时静默：答题主链路不受影响
    }
  },

  /**
   * 恢复本地草稿：只填充「服务端尚无答案」的题（服务端已有答案时以服务端为准），
   * 恢复出的答案走既有落库通道（save 失败自动入离线队列），不另起一套同步机制。
   */
  restoreDraft() {
    let draft = {}
    try {
      draft = wx.getStorageSync(this.draftKey()) || {}
    } catch (e) {
      return
    }
    const ids = Object.keys(draft || {})
    if (!ids.length) return
    const answers = { ...this.answers }
    const answeredMap = { ...this.data.answeredMap }
    let restored = 0
    ids.forEach((rawId) => {
      const item = draft[rawId]
      const questionId = Number(rawId)
      if (!item || !(item.answer || []).length) return
      if ((answers[questionId] || []).length) return
      answers[questionId] = item.answer
      answeredMap[questionId] = true
      this.dirty.add(questionId)
      restored++
    })
    if (!restored) return
    this.answers = answers
    const current = this.data.current
    const patch = { answeredMap, answeredCount: Object.keys(answeredMap).length, savedCount: Object.keys(answers).length }
    if (current && answeredMap[current.id]) {
      patch.selectedMap = this.buildSelectedMap(current, answers)
      if (current.type === 'ESSAY') {
        patch.essayText = (answers[current.id] || [''])[0] || ''
      }
    }
    this.setData(patch)
    this.flushDirty()
    wx.showToast({ title: `已恢复 ${restored} 题本地答案`, icon: 'none' })
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
    this.setData({ selectedMap, answeredMap, answeredCount: Object.keys(answeredMap).length })
    this.writeDraft(question.id)
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
    this.setData({ essayText: text, answeredMap, answeredCount: Object.keys(answeredMap).length })
    this.writeDraft(question.id)
    if (this.essayTimer) clearTimeout(this.essayTimer)
    this.essayTimer = setTimeout(() => this.save(question.id), 1000)
  },

  scheduleSave(questionId) {
    this.dirty.add(questionId)
    // 只有一个定时器，但它冲刷的是「所有待保存的题」，而不是被它覆盖掉的那一题
    if (this.saveTimer) return
    this.saveTimer = setTimeout(() => {
      this.saveTimer = null
      this.flushDirty()
    }, 400)
  },

  /** 把内存里还没落库的答案全部发出。交卷前必须先做，否则最近 400ms 内的答案不会被提交。 */
  flushDirty() {
    const ids = Array.from(this.dirty)
    this.dirty.clear()
    if (!ids.length) return Promise.resolve([])
    return Promise.all(ids.map((id) => this.save(id)))
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
        const patch = { answeredMap, answeredCount: Object.keys(answeredMap).length }
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

  // —— 底部抽屉答题卡 ——
  openDrawer() {
    this.setData({ drawerVisible: true })
  },

  closeDrawer() {
    this.setData({ drawerVisible: false })
  },

  // 抽屉遮罩/面板用来吞掉点击与滑动，避免点面板时触发遮罩关闭、滑动穿透到页面
  noop() {},

  switchFromCard(e) {
    const index = Number(e.currentTarget.dataset.index)
    const question = this.data.questions[index]
    if (!question) return
    this.switchTo({ currentTarget: { dataset: { index } } })
    this.setData({ drawerVisible: false })
  },

  confirmSubmit() {
    if (this.data.drawerVisible) {
      this.setData({ drawerVisible: false })
    }
    const unanswered = this.data.questions.filter((q) => !(this.answers[q.id] || []).length).length
    wx.showModal({
      title: '确认交卷',
      content: unanswered > 0 ? `还有 ${unanswered} 题未作答，交卷后不能再修改答案。` : '交卷后不能再修改答案，确认交卷？',
      success: (res) => {
        if (!res.confirm) return
        // 交卷前必须两件事都做完：
        //   1) 把内存里还在 400ms 防抖窗口内的答案发出去（否则最后点的那几题不会被提交）；
        //   2) 把离线队列补交掉（否则服务端收到的还是断网前那一版）。
        // 任何一件没做完就交卷，等于用一份更旧的答案结束考试。
        if (this.saveTimer) {
          clearTimeout(this.saveTimer)
          this.saveTimer = null
        }
        const needLoading = this.data.pendingCount > 0 || this.dirty.size > 0
        if (needLoading) {
          wx.showLoading({ title: '正在同步离线答案' })
        }
        this.flushDirty()
          .then(() => this.flushQueue())
          .then((left) => {
            if (needLoading) wx.hideLoading()
            if (left > 0) {
              // 仍有答案没同步完：此时交卷会把断网前那一版当成最终答案，宁可让用户重试
              wx.showModal({
                title: '还有答案未同步',
                content: `仍有 ${left} 条答案没有提交成功，请恢复网络后重新交卷。`,
                showCancel: false
              })
              return
            }
            this.doSubmit()
          })
          .catch(() => {
            if (needLoading) wx.hideLoading()
            wx.showToast({ title: '交卷前同步失败，请重试', icon: 'none' })
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
        // 交卷成功即清本地草稿，避免下一场考试或重进页面时恢复出上一场的答案
        try {
          wx.removeStorageSync(this.draftKey())
        } catch (e) {}
        wx.redirectTo({ url: '/pages/exam/result?attemptId=' + res.attemptId })
      })
      .catch(() => {
        this.submitted = false
        this.setData({ submitting: false })
      })
  }
})
