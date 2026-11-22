const { request } = require('../../utils/request')

Page({
  data: {
    list: [],
    // 未过滤的完整列表：科目筛选在本地做，不为每次点击多发一次请求
    allItems: [],
    subjects: [],
    subjectFilter: '',
    loading: true,
    expandedId: null,
    // ALL / EXAM / PRACTICE，与后端 LearningService.wrongQuestions 的 source 取值一致
    source: 'ALL'
  },

  onLoad() {
    this.load()
  },

  onPullDownRefresh() {
    this.load().then(() => wx.stopPullDownRefresh())
  },

  async load() {
    this.setData({ loading: true })
    try {
      const query = this.data.source === 'ALL' ? '' : '?source=' + this.data.source
      const raw = await request({ url: '/wrong-questions' + query })
      // 字段来自 WrongQuestionView：question / lastAnswer / lastAnsweredAt / source
      // question 内部是 QuestionDetailView：id / stem / type / answer / analysis
      const items = (raw || []).map((item, index) => {
        const q = item.question || {}
        return {
          key: q.id || index,
          id: q.id || index,
          stem: q.stem || '（题干缺失）',
          typeText: q.type || '',
          correctAnswer: (q.answer || []).join('、'),
          myAnswer: (item.lastAnswer || []).join('、') || '未作答',
          sourceText: item.source === 'EXAM' ? '考试错题' : '练习错题',
          answeredAt: String(item.lastAnsweredAt || '').replace('T', ' ').slice(0, 16),
          analysis: q.analysis || '',
          // 后端已带 subjectName；缺失时给可读兜底，不留空
          subjectName: item.subjectName || '未知科目'
        }
      })
      // 科目清单从错题本身聚合：错题本不按项目分区，没有「科目列表」接口可查
      const subjects = Array.from(new Set(items.map((i) => i.subjectName))).filter(Boolean)
      this.setData({ allItems: items, subjects, subjectFilter: '' }, () => this.applyFilter())
    } catch (e) {
      // request 已统一提示错误，这里只负责收尾 loading
    } finally {
      this.setData({ loading: false })
    }
  },

  toggle(e) {
    const id = e.currentTarget.dataset.id
    this.setData({ expandedId: this.data.expandedId === id ? null : id })
  },

  switchSource(e) {
    const source = e.currentTarget.dataset.source
    if (source === this.data.source) return
    // 切换后收起已展开项：列表整体替换，保留展开态会让它指向一条已不在列表里的记录
    this.setData({ source, expandedId: null }, () => this.load())
  },

  applyFilter() {
    const filter = this.data.subjectFilter
    const list = filter
      ? this.data.allItems.filter((i) => i.subjectName === filter)
      : this.data.allItems
    this.setData({ list })
  },

  switchSubject(e) {
    const subject = e.currentTarget.dataset.subject || ''
    if (subject === this.data.subjectFilter) return
    this.setData({ subjectFilter: subject, expandedId: null }, () => this.applyFilter())
  },

  // —— 重做此题：懒加载带选项的题目详情，本地对照标准答案判分 ——
  // 注意：练习详情接口 GET /questions/{id} 返回的是 QuestionView（按设计不含标准答案），
  // 标准答案必须从错题列表的 correctAnswer 反查，否则 expected 恒为空、判分恒错。
  startRetry(e) {
    const id = e.currentTarget.dataset.id
    const item = this.data.allItems.find((i) => String(i.id) === String(id)) || {}
    const listAnswer = (item.correctAnswer || '')
      .split('、')
      .map((s) => s.trim())
      .filter(Boolean)
    wx.showLoading({ title: '加载题目' })
    request({ url: '/questions/' + id })
      .then((question) => {
        wx.hideLoading()
        if (question.type === 'ESSAY') {
          wx.showToast({ title: '主观题请参考解析', icon: 'none' })
          return
        }
        const answer = question.answer && question.answer.length ? question.answer : listAnswer
        const options = (question.options || []).map((opt) => Object.assign({}, opt, { active: false }))
        this.setData({
          retry: {
            questionId: id,
            type: question.type,
            options,
            answer,
            selected: [],
            submitted: false,
            matched: false
          }
        })
      })
      .catch(() => wx.hideLoading())
  },

  closeRetry() {
    this.setData({ retry: null })
  },

  chooseRetryOption(e) {
    const key = e.currentTarget.dataset.key
    const retry = this.data.retry
    if (!retry || retry.submitted) return
    const selected = retry.type === 'MULTIPLE'
      ? (retry.selected.indexOf(key) >= 0
          ? retry.selected.filter((k) => k !== key)
          : retry.selected.concat(key))
      : [key]
    const options = retry.options.map((opt) =>
      Object.assign({}, opt, { active: selected.indexOf(opt.key) >= 0 }))
    this.setData({ 'retry.selected': selected, 'retry.options': options })
  },

  submitRetry() {
    const retry = this.data.retry
    if (!retry) return
    if (retry.selected.length === 0) {
      wx.showToast({ title: '请先选择答案', icon: 'none' })
      return
    }
    const expected = retry.answer.map((s) => String(s).trim().toUpperCase()).sort()
    const actual = retry.selected.map((s) => String(s).trim().toUpperCase()).sort()
    if (expected.length === 0) {
      wx.showToast({ title: '标准答案缺失，无法判分', icon: 'none' })
      return
    }
    const matched = expected.length === actual.length && expected.every((k, i) => k === actual[i])
    this.setData({ 'retry.submitted': true, 'retry.matched': matched })
  }
})
