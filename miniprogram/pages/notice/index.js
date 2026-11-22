const { request } = require('../../utils/request')

Page({
  data: {
    list: [],
    loading: true,
    expandedId: null,
    detail: {}
  },

  onLoad() {
    this.load()
  },

  // 公告是低频内容，下拉刷新足够；不做轮询，避免考生考试时被无谓请求打断
  onPullDownRefresh() {
    this.load().then(() => wx.stopPullDownRefresh())
  },

  async load() {
    this.setData({ loading: true })
    try {
      const list = await request({ url: '/notices' })
      // 后端字段是驼峰（pagePublished 手工构造的 LinkedHashMap）：id / title / pinned / publishAt
      // publishAt 是 Timestamp 的序列化结果，小程序没有日期格式化能力，统一压成 "yyyy-MM-dd HH:mm"
      const items = (list || []).map((item) => ({
        ...item,
        publishAtText: String(item.publishAt || '').replace('T', ' ').slice(0, 16)
      }))
      this.setData({ list: items })
    } catch (e) {
      // request 已统一提示错误，这里只需保证 loading 收尾
    } finally {
      this.setData({ loading: false })
    }
  },

  async toggle(e) {
    const id = e.currentTarget.dataset.id
    if (this.data.expandedId === id) {
      this.setData({ expandedId: null })
      return
    }
    try {
      const detail = await request({ url: '/notices/' + id })
      this.setData({ expandedId: id, detail: detail || {} })
    } catch (e) {
      // 列表里有、详情拿不到：多半是刚被撤回，保持当前状态即可
    }
  }
})
