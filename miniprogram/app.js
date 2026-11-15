const { ensureLogin, getUser } = require('./utils/request')

App({
  globalData: {
    user: null,
    loginReady: null
  },

  onLaunch() {
    this.globalData.loginReady = ensureLogin()
      .then((data) => {
        this.globalData.user = data.user
        return data.user
      })
      .catch(() => {
        this.globalData.user = null
        return null
      })
  },

  /** 页面统一入口：确保登录完成后再拉数据 */
  ready() {
    if (!this.globalData.loginReady) {
      this.globalData.loginReady = ensureLogin().then((data) => data.user)
    }
    return this.globalData.loginReady
  },

  currentUser() {
    return getUser()
  }
})
