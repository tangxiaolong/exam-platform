const { request } = require('../../utils/request')

Page({
  data: {
    username: '',
    password: '',
    submitting: false
  },

  onInput(e) {
    const field = e.currentTarget.dataset.field
    this.setData({ [field]: e.detail.value })
  },

  async submit() {
    const username = this.data.username.trim()
    const password = this.data.password.trim()

    if (!/^[a-zA-Z0-9_]{4,32}$/.test(username)) {
      wx.showToast({ title: '账号名需 4-32 位字母、数字或下划线', icon: 'none' })
      return
    }
    if (password.length < 8) {
      wx.showToast({ title: '密码至少 8 位', icon: 'none' })
      return
    }

    this.setData({ submitting: true })
    try {
      // 字段名必须是 newPassword：后端 setupPassword 读的就是 body.get("newPassword")
      // 而且必须带 username——wx_ 开头的账号名考生不可能知道，只设密码等于设了个用不上的密码
      await request({
        url: '/users/me/password/setup',
        method: 'POST',
        data: { username, newPassword: password }
      })
      wx.showModal({
        title: '设置成功',
        content: '以后可以用「' + username + '」和这个密码登录',
        showCancel: false,
        success: () => wx.navigateBack()
      })
    } catch (e) {
      // request 已统一提示错误
    } finally {
      this.setData({ submitting: false })
    }
  }
})
