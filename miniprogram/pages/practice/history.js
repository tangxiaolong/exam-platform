const app = getApp()
const { request } = require('../../utils/request')
const { formatDate } = require('../../utils/format')

const PAGE_SIZE = 20

function fmtDay(d) {
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

Page({
  data: {
    list: [],
    groups: [],
    subjects: [],
    subjectFilter: '',
    summary: { practiceCount: 0, totalAnswered: 0, rate: 0 },
    loading: true,
    pageNo: 1,
    finished: false,
    moreLoading: false
  },

  onShow() {
    this.load()
  },

  onPullDownRefresh() {
    this.load().then(() => wx.stopPullDownRefresh())
  },

  onReachBottom() {
    this.loadMore()
  },

  mapItems(records) {
    return (records || []).map((item) => ({
      practiceId: item.practice_id,
      subjectName: item.subject_name || '未命名科目',
      modeText: { SEQUENCE: '顺序练习', RANDOM: '随机练习', WRONG: '错题重练' }[item.mode] || item.mode || '练习',
      status: item.status,
      totalCount: item.total_count || 0,
      answeredCount: item.answered_count || 0,
      correctCount: item.correct_count || 0,
      startedAt: item.started_at ? formatDate(item.started_at) : ''
    }))
  },

  /** 顶部统计基于已加载记录：分页越滑越多，数字随之增长（诚实反映当前视图）。 */
  computeSummary(list) {
    const totalAnswered = list.reduce((s, i) => s + i.answeredCount, 0)
    const totalCorrect = list.reduce((s, i) => s + i.correctCount, 0)
    const rate = totalAnswered > 0 ? Math.round((totalCorrect / totalAnswered) * 100) : 0
    return { practiceCount: list.length, totalAnswered, rate }
  },

  /** 按开始日期分组：今天/昨天直接给中文标题，更早显示日期，让一眼扫清练习节奏。 */
  groupByDate(list) {
    const now = new Date()
    const todayKey = fmtDay(now)
    const yKey = fmtDay(new Date(now.getTime() - 86400000))
    const groups = []
    const map = {}
    list.forEach((item) => {
      const key = String(item.startedAt || '').slice(0, 10) || '未知日期'
      if (!map[key]) {
        map[key] = {
          title: key === todayKey ? '今天' : key === yKey ? '昨天' : key,
          items: []
        }
        groups.push(map[key])
      }
      map[key].items.push({
        ...item,
        ratePct: item.answeredCount > 0 ? Math.round((item.correctCount / item.answeredCount) * 100) : 0
      })
    })
    return groups
  },

  /** 汇总刷新：科目 chips 从全量记录聚合；分组与统计基于当前筛选结果。 */
  refresh(list) {
    const subjects = Array.from(new Set(list.map((i) => i.subjectName))).filter(Boolean)
    const filter = this.data.subjectFilter
    // 筛选值不在已加载科目清单里（如尚未翻到该科目的记录）时回退为「全部」
    const effectiveFilter = subjects.indexOf(filter) >= 0 ? filter : ''
    const filtered = effectiveFilter ? list.filter((i) => i.subjectName === effectiveFilter) : list
    this.setData({
      list,
      subjects,
      subjectFilter: effectiveFilter,
      groups: this.groupByDate(filtered),
      summary: this.computeSummary(filtered)
    })
  },

  switchSubject(e) {
    const subject = e.currentTarget.dataset.subject || ''
    if (subject === this.data.subjectFilter) return
    this.setData({ subjectFilter: subject })
    this.refresh(this.data.list)
  },

  async load() {
    this.setData({ loading: true })
    try {
      const page = await request({ url: `/practice/sessions?pageNo=1&pageSize=${PAGE_SIZE}` })
      const list = this.mapItems(page && page.list)
      this.setData({ pageNo: 1, finished: list.length < PAGE_SIZE })
      this.refresh(list)
    } catch (e) {
      // request 已统一提示错误
    } finally {
      this.setData({ loading: false })
    }
  },

  async loadMore() {
    if (this.data.finished || this.data.loading || this.data.moreLoading) return
    const pageNo = this.data.pageNo + 1
    this.setData({ moreLoading: true })
    try {
      const page = await request({ url: `/practice/sessions?pageNo=${pageNo}&pageSize=${PAGE_SIZE}` })
      const records = this.mapItems(page && page.list)
      this.setData({ pageNo, finished: records.length < PAGE_SIZE })
      this.refresh(this.data.list.concat(records))
    } catch (e) {
      // 失败不推进页码，下次触底可重试
    } finally {
      this.setData({ moreLoading: false })
    }
  },

  toPractice(e) {
    if (e.currentTarget.dataset.status !== 'IN_PROGRESS') {
      wx.showToast({ title: '该练习已完成', icon: 'none' })
      return
    }
    wx.navigateTo({ url: '/pages/practice/practice?resume=1' })
  }
})
