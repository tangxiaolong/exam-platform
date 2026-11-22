const { request } = require('../../utils/request')

Page({
  data: {
    list: [],
    loading: true,
    expandedId: null,
    retry: null
  },

  onShow() {
    this.load()
  },

  onPullDownRefresh() {
    this.load().then(() => wx.stopPullDownRefresh())
  },

  async load() {
    this.setData({ loading: true })
    try {
      const raw = await request({ url: '/favorites?pageNo=1&pageSize=50' })
      // pageFavorites 返回行字段以 SQL 别名为准，这里做兼容读取，缺字段时不至于整页空白
      const list = (raw || []).map((item) => {
        // answer_json 是 JSON 数组字符串（如 ["A"]），解析失败按空答案兜底
        let answerKeys = []
        try {
          answerKeys = JSON.parse(item.answerJson || '[]')
        } catch (e) {
          answerKeys = []
        }
        if (!Array.isArray(answerKeys)) answerKeys = []
        return {
          id: item.questionId || item.question_id || item.questionid,
          stem: item.stem || '（题目已删除）',
          typeText: item.type || '',
          difficultyText: item.difficulty || '',
          correctAnswer: answerKeys.join('、'),
          answerKeys,
          analysis: item.analysis || '',
          favoriteAt: String(item.createdAt || item.favoriteAt || '').replace('T', ' ').slice(0, 16)
        }
      })
      this.setData({ list })
    } catch (e) {
      // request 已统一提示错误，这里只负责收尾 loading
    } finally {
      this.setData({ loading: false })
    }
  },

  toggle(e) {
    const id = e.currentTarget.dataset.id
    const expandedId = this.data.expandedId === id ? null : id
    // 收起时顺带关闭重做面板，避免面板挂在另一张卡上
    this.setData({ expandedId, retry: null })
  },

  async remove(e) {
    const id = e.currentTarget.dataset.id
    try {
      await request({ url: '/favorites/' + id, method: 'DELETE' })
      wx.showToast({ title: '已取消收藏', icon: 'none' })
      this.load()
    } catch (e) {
      // request 已统一提示错误
    }
  },

  // —— 重做本收藏题：与错题本同款本地判分 ——
  // 标准答案来自收藏列表行（answerKeys）；选项从题目详情接口取（该接口按设计不含答案）
  startRetry(e) {
    const id = e.currentTarget.dataset.id
    const item = this.data.list.find((i) => String(i.id) === String(id))
    if (!item || item.answerKeys.length === 0) {
      wx.showToast({ title: '标准答案缺失，暂时无法重做', icon: 'none' })
      return
    }
    wx.showLoading({ title: '加载题目' })
    request({ url: '/questions/' + id })
      .then((question) => {
        wx.hideLoading()
        if (question.type === 'ESSAY') {
          wx.showToast({ title: '主观题请参考解析', icon: 'none' })
          return
        }
        const options = (question.options || []).map((opt) => Object.assign({}, opt, { active: false }))
        this.setData({
          retry: {
            questionId: id,
            type: question.type,
            options,
            answer: item.answerKeys,
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
