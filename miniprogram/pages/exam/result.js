const app = getApp()
const { request } = require('../../utils/request')
const { formatDate } = require('../../utils/format')

Page({
  data: {
    loading: true,
    result: null,
    filteredItems: [],
    wrongItemsCount: 0,
    filterWrong: false,
    scorePct: 0,
    submittedText: '',
    statusText: '',
    // 收藏/错题本按题 id 维护按钮态，避免每题打两次接口
    favoritedMap: {},
    wrongMap: {},
    // 成绩趋势/历史对比（未出分或无可对比数据时为 null）
    trend: null
  },

  onLoad(query) {
    this.attemptId = query.attemptId
    app
      .ready()
      .then(() => request({ url: `/attempts/${this.attemptId}/result` }))
      .then((data) => {
        // 答案数组在 WXML 里直接插值会按逗号挤成一行，这里先拼成可读文本
        const items = (data.items || []).map((item) => ({
          ...item,
          standardAnswerText: (item.standardAnswer || []).join('、'),
          yourAnswerText: (item.yourAnswer || []).join('、') || '未作答'
        }))
        const wrongCount = items.filter((i) => !i.correct && i.type !== 'ESSAY').length
        const fullScore = Number(data.fullScore) || 0
        const scorePct = fullScore > 0
          ? Math.min(100, Math.round(((Number(data.totalScore) || 0) / fullScore) * 100))
          : 0
        this.setData({
          loading: false,
          result: { ...data, items },
          filteredItems: items,
          wrongItemsCount: wrongCount,
          filterWrong: false,
          scorePct,
          totalScoreText: formatScore(data.totalScore),
          submittedText: formatDate(data.submittedAt),
          statusText: statusLabel(data.status)
        })
        this.loadExtras()
        // 只有成绩已出（GRADED/PUBLISHED）才有名次与趋势可言
        if (data.status === 'GRADED' || data.status === 'PUBLISHED') {
          this.loadRank()
          this.loadTrend()
        }
      })
      .catch(() => this.setData({ loading: false }))
  },

  loadRank() {
    // 名次是锦上添花：拉失败不打扰考生，成绩单本身已完整
    request({ url: `/attempts/${this.attemptId}/rank`, showError: false })
      .then((rank) => {
        if (rank && rank.rank) {
          this.setData({ rank })
        }
      })
      .catch(() => {})
  },

  onShareAppMessage() {
    const r = this.data.result
    // 分享链接落到首页而不是成绩单：他人无权查看本人答卷（后端按本人校验），
    // 把陌生访客带进成绩单只会得到一次"暂无成绩"的冷场
    return {
      title: r && r.sessionName
        ? `我在「${r.sessionName}」中获得了 ${this.data.totalScoreText} 分`
        : '在线考试平台',
      path: '/pages/index/index'
    }
  },

  toggleFilter(e) {
    const onlyWrong = e.currentTarget.dataset.wrong === '1'
    const items = (this.data.result && this.data.result.items) || []
    this.setData({
      filterWrong: onlyWrong,
      filteredItems: onlyWrong ? items.filter((i) => !i.correct && i.type !== 'ESSAY') : items
    })
  },

  /** 收藏与错题本状态：一次拉 id 全集，本地维护按钮态，避免每题打两次接口。 */
  loadExtras() {
    request({ url: '/favorites/ids', showError: false })
      .then((ids) => {
        const favoritedMap = {}
        ;(ids || []).forEach((id) => { favoritedMap[id] = true })
        this.setData({ favoritedMap })
      })
      .catch(() => {})
    request({ url: '/wrong-questions/ids', showError: false })
      .then((ids) => {
        const wrongMap = {}
        ;(ids || []).forEach((id) => { wrongMap[id] = true })
        this.setData({ wrongMap })
      })
      .catch(() => {})
  },

  /** 成绩趋势/历史对比：拉本人最近 10 次已出分成绩，按百分比画柱状趋势。 */
  loadTrend() {
    request({ url: '/attempts/history?limit=10', showError: false })
      .then((history) => {
        const trend = this.buildTrend(history, this.data.result && this.data.result.attemptId)
        this.setData({ trend })
      })
      .catch(() => {})
  },

  buildTrend(history, currentAttemptId) {
    const items = (history || [])
      .filter((h) => Number(h.fullScore) > 0 && h.totalScore != null)
      .map((h) => {
        const total = Number(h.totalScore)
        const full = Number(h.fullScore)
        const pct = Math.max(0, Math.min(100, Math.round((total / full) * 100)))
        return {
          attemptId: h.attemptId,
          sessionName: h.sessionName || '考试',
          scoreText: total.toFixed(0),
          pct,
          dateText: shortDate(h.submittedAt),
          isCurrent: Number(h.attemptId) === Number(currentAttemptId)
        }
      })
    if (!items.length) return null
    const pcts = items.map((i) => i.pct)
    const best = Math.max.apply(null, pcts)
    const avg = Math.round(pcts.reduce((a, b) => a + b, 0) / pcts.length)
    const current = items.find((i) => i.isCurrent) || items[items.length - 1]
    const idx = items.findIndex((i) => i.attemptId === current.attemptId)
    const prev = idx > 0 ? items[idx - 1] : null
    let deltaDir = 'none'
    let deltaLabel = '本次为首次成绩，暂无历史可对比'
    if (prev) {
      const delta = current.pct - prev.pct
      if (delta > 0) {
        deltaDir = 'up'
        deltaLabel = '较上次提升 ' + delta + ' 个百分点'
      } else if (delta < 0) {
        deltaDir = 'down'
        deltaLabel = '较上次下降 ' + Math.abs(delta) + ' 个百分点'
      } else {
        deltaDir = 'flat'
        deltaLabel = '较上次持平'
      }
    }
    return { items, best, avg, currentPct: current.pct, deltaDir, deltaLabel }
  },

  toggleFavorite(e) {
    const id = e.currentTarget.dataset.id
    if (id == null) return
    const map = Object.assign({}, this.data.favoritedMap)
    if (map[id]) {
      delete map[id]
      this.setData({ favoritedMap: map })
      request({ url: '/favorites/' + id, method: 'DELETE', showError: false }).catch(() => {})
      wx.showToast({ title: '已取消收藏', icon: 'none' })
      return
    }
    map[id] = true
    this.setData({ favoritedMap: map })
    request({ url: '/favorites/' + id, method: 'POST', showError: false })
      .then(() => wx.showToast({ title: '已收藏', icon: 'success' }))
      .catch((err) => {
        const rollback = Object.assign({}, this.data.favoritedMap)
        delete rollback[id]
        this.setData({ favoritedMap: rollback })
        wx.showToast({ title: (err && err.message) || '收藏失败', icon: 'none' })
      })
  },

  addWrongBook(e) {
    const id = e.currentTarget.dataset.id
    if (id == null) return
    if (this.data.wrongMap[id]) {
      wx.showToast({ title: '已在错题本中', icon: 'none' })
      return
    }
    const map = Object.assign({}, this.data.wrongMap)
    map[id] = true
    this.setData({ wrongMap: map })
    request({ url: '/wrong-questions/' + id, method: 'POST', showError: false })
      .then(() => wx.showToast({ title: '已加入错题本', icon: 'success' }))
      .catch((err) => {
        const rollback = Object.assign({}, this.data.wrongMap)
        delete rollback[id]
        this.setData({ wrongMap: rollback })
        wx.showToast({ title: (err && err.message) || '加入失败', icon: 'none' })
      })
  },

  goWrongBook() {
    // 成绩单里通常最想去复习刚错的题，给一条直达路径；wrong 页不是 tabBar 页，可 navigateTo
    wx.navigateTo({ url: '/pages/wrong/index' })
  },

  backToList() {
    wx.reLaunch({ url: '/pages/exam/list' })
  }
})

function formatScore(value) {
  const num = Number(value)
  if (Number.isNaN(num)) {
    return '0.00'
  }
  return num.toFixed(2)
}

/** 趋势柱下方的短日期：后端交卷时间是 "yyyy-MM-dd HH:mm:ss"，取 "MM-DD" 即可。 */
function shortDate(value) {
  const text = String(value || '')
  return text.length >= 10 ? text.slice(5, 10) : text
}

function statusLabel(status) {
  switch (status) {
    case 'PUBLISHED':
      return '成绩已发布'
    case 'GRADED':
      return '成绩待发布'
    case 'REVIEWING':
      return '人工阅卷中'
    default:
      return status || ''
  }
}
