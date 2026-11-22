const app = getApp()
const { request, getUser, clearAuth } = require('../../utils/request')
const { formatDate } = require('../../utils/format')

function mapEntitlement(e) {
  // 权益类型/状态是后端枚举，翻译成考生可读文案；未知值原样展示兜底
  const typeText = { MEMBER: '平台会员', QUESTION_BANK: '题库额度', COURSE: '课程权益', EXAM: '考试权益' }[e.entitlementType] || (e.entitlementType || '权益')
  const iconText = { MEMBER: '会', QUESTION_BANK: '题', COURSE: '课', EXAM: '考' }[e.entitlementType] || (typeText.charAt(0) || '权')
  const iconClass = { MEMBER: 'm-purple', QUESTION_BANK: 'm-blue', COURSE: 'm-orange', EXAM: 'm-green' }[e.entitlementType] || 'm-blue'
  const statusText = { ACTIVE: '生效中', EXPIRED: '已过期', USED_UP: '已用完' }[e.status] || (e.status || '')
  return {
    ...e,
    typeText,
    iconText,
    iconClass,
    statusText,
    statusActive: e.status === 'ACTIVE',
    expireText: e.expireAt ? formatDate(e.expireAt) : '',
    leftText: typeof e.totalAmount === 'number' ? e.totalAmount - (e.usedAmount || 0) : (e.totalAmount || 0)
  }
}

function updateTabBarBadge(unread) {
  // 未读数挂到 tabBar「我的」（index 3）：考生在任何 tab 页都能看到"有消息"
  const index = 3
  if (unread > 0) {
    wx.setTabBarBadge({ index, text: unread > 99 ? '99+' : String(unread), fail: () => {} })
  } else {
    wx.removeTabBarBadge({ index, fail: () => {} })
  }
}

Page({
  data: {
    user: null,
    avatarText: '考',
    attempts: [],
    latestAttempts: [],
    entitlements: [],
    wrongCount: 0,
    favoriteCount: 0,
    messageUnread: 0,
    recentPractice: null,
    loading: true,
    statusText: {
      IN_PROGRESS: '进行中',
      SUBMITTED: '已交卷',
      REVIEWING: '阅卷中',
      GRADED: '已判分',
      EXPIRED: '已过期'
    }
  },

  onShow() {
    this.load()
  },

  load() {
    this.setData({ loading: true, user: getUser() })
    app
      .ready()
      .then(() =>
        Promise.all([
          request({ url: '/users/me' }),
          request({ url: '/attempts' }),
          request({ url: '/entitlements' }),
          request({ url: '/wrong-questions', showError: false }).catch(() => []),
          request({ url: '/inbox/unread-count', showError: false }).catch(() => 0),
          request({ url: '/practice/sessions/recent', showError: false }).catch(() => null),
          request({ url: '/favorites?pageNo=1&pageSize=100', showError: false }).catch(() => [])
        ])
      )
      .then(([user, attempts, entitlements, wrong, unread, recentPractice, favorites]) => {
        const displayName = (user && (user.nickname || user.username)) || '考'
        const attemptsMapped = (attempts || []).map((a) => ({
          ...a,
          submittedText: a.submittedAt ? formatDate(a.submittedAt) : '未交卷'
        }))
        this.setData({
          user,
          avatarText: displayName.charAt(0),
          attempts: attemptsMapped,
          latestAttempts: attemptsMapped.slice(0, 3),
          entitlements: (entitlements || []).map(mapEntitlement),
          wrongCount: (wrong || []).length,
          favoriteCount: Array.isArray(favorites) ? favorites.length : (favorites || []).length,
          messageUnread: unread || 0,
          recentPractice: recentPractice && recentPractice.practiceId
            ? {
                practiceId: recentPractice.practiceId,
                subjectName: recentPractice.subjectName || '',
                totalCount: recentPractice.totalCount || 0,
                answeredCount: recentPractice.answeredCount || 0,
                startedAt: recentPractice.startedAt ? formatDate(recentPractice.startedAt) : '',
                progressPct: recentPractice.totalCount
                  ? Math.min(100, Math.round(((recentPractice.answeredCount || 0) / recentPractice.totalCount) * 100))
                  : 0
              }
            : null,
          loading: false
        })
        updateTabBarBadge(unread || 0)
      })
      .catch(() => this.setData({ loading: false }))
  },

  toResult(e) {
    const attemptId = e.currentTarget.dataset.id
    wx.navigateTo({ url: '/pages/exam/result?attemptId=' + attemptId })
  },

  toExam() {
    wx.switchTab({ url: '/pages/exam/list' })
  },

  toWrong() {
    // 原来只弹一个 Toast，页面入口是死的——考生看得到数字却进不去
    wx.navigateTo({ url: '/pages/wrong/index' })
  },

  toFavorites() {
    wx.navigateTo({ url: '/pages/favorite/index' })
  },

  toMessages() {
    wx.navigateTo({ url: '/pages/message/index' })
  },

  toPracticeHistory() {
    wx.navigateTo({ url: '/pages/practice/history' })
  },

  toResume() {
    const recent = this.data.recentPractice
    if (!recent) return
    const subjectName = recent.subjectName
      ? '&subjectName=' + encodeURIComponent(recent.subjectName)
      : ''
    wx.navigateTo({ url: '/pages/practice/practice?resume=1' + subjectName })
  },

  toOrder() {
    wx.navigateTo({ url: '/pages/order/index' })
  },

  toCoupon() {
    wx.navigateTo({ url: '/pages/coupon/index' })
  },

  toNotice() {
    wx.navigateTo({ url: '/pages/notice/index' })
  },

  toPassword() {
    wx.navigateTo({ url: '/pages/password/index' })
  },

  toMall() {
    wx.switchTab({ url: '/pages/mall/index' })
  },

  logout() {
    wx.showModal({
      title: '提示',
      content: '确认退出当前账号？',
      success: (res) => {
        if (!res.confirm) return
        clearAuth()
        wx.reLaunch({ url: '/pages/index/index' })
      }
    })
  }
})
