const { request } = require('../../utils/request')

const PAGE_SIZE = 20

Page({
  data: {
    list: [],
    loading: true,
    unread: 0,
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
      id: item.id,
      title: item.title || '（无标题）',
      content: item.content || '',
      read: !!item.read,
      expanded: false,
      at: String(item.createdAt || '').replace('T', ' ').slice(0, 16)
    }))
  },

  async load() {
    this.setData({ loading: true })
    try {
      const [page, unread] = await Promise.all([
        request({ url: `/inbox?pageNo=1&pageSize=${PAGE_SIZE}` }),
        request({ url: '/inbox/unread-count', showError: false })
      ])
      const list = this.mapItems(page && page.list)
      this.setData({
        list,
        pageNo: 1,
        finished: list.length < PAGE_SIZE,
        unread: unread || 0
      })
      // 标题带未读数：读完回列表时标题里的数字随之缩小，与页内红点互为印证
      wx.setNavigationBarTitle({
        title: unread ? `我的消息(${unread})` : '我的消息'
      })
    } catch (e) {
      // request 已统一提示错误，这里只负责收尾 loading
    } finally {
      this.setData({ loading: false })
    }
  },

  async loadMore() {
    if (this.data.finished || this.data.loading || this.data.moreLoading) return
    const pageNo = this.data.pageNo + 1
    this.setData({ moreLoading: true })
    try {
      const page = await request({ url: `/inbox?pageNo=${pageNo}&pageSize=${PAGE_SIZE}` })
      const records = this.mapItems(page && page.list)
      this.setData({
        list: this.data.list.concat(records),
        pageNo,
        finished: records.length < PAGE_SIZE
      })
    } catch (e) {
      // request 已统一提示错误；页码不前进，下次触底可重试
    } finally {
      this.setData({ moreLoading: false })
    }
  },

  async markAllRead() {
    if (!this.data.unread) return
    try {
      await request({ url: '/inbox/read-all', method: 'POST' })
      wx.showToast({ title: '已全部标记', icon: 'success' })
      this.load()
    } catch (e) {
      // request 已统一提示错误
    }
  },

  openMessage(e) {
    const index = e.currentTarget.dataset.index
    const item = this.data.list[index]
    if (!item) return
    const list = this.data.list.slice()
    list[index] = Object.assign({}, item, { expanded: !item.expanded })
    if (!item.read) {
      // 展开即视为已读：红点消失、未读数减一；失败静默——列表以服务端为准，下次刷新自纠
      list[index].read = true
      this.setData({ list, unread: Math.max(0, this.data.unread - 1) })
      request({ url: '/inbox/' + item.id + '/read', method: 'POST' }).catch(() => {})
    } else {
      this.setData({ list })
    }
  }
})
