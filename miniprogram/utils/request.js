const config = require('../config')

const TOKEN_KEY = 'exam_token'
const USER_KEY = 'exam_user'

function getToken() {
  return wx.getStorageSync(TOKEN_KEY) || ''
}

function setToken(token) {
  wx.setStorageSync(TOKEN_KEY, token)
}

function getUser() {
  return wx.getStorageSync(USER_KEY) || null
}

function setUser(user) {
  wx.setStorageSync(USER_KEY, user)
}

function clearAuth() {
  wx.removeStorageSync(TOKEN_KEY)
  wx.removeStorageSync(USER_KEY)
}

/**
 * 统一请求：自动带 token，按后端 { code, message, data } 约定拆包。
 */
function request(options) {
  const { url, method = 'GET', data, needAuth = true, showError = true } = options
  return new Promise((resolve, reject) => {
    const header = { 'Content-Type': 'application/json' }
    if (needAuth && getToken()) {
      header.Authorization = 'Bearer ' + getToken()
    }
    wx.request({
      url: config.baseUrl + url,
      method,
      data,
      header,
      success(res) {
        const body = res.data || {}
        if (body.code === '0') {
          resolve(body.data)
          return
        }
        if (body.code && String(body.code).indexOf('AUTH_') === 0) {
          clearAuth()
        }
        if (showError) {
          wx.showToast({ title: body.message || '请求失败', icon: 'none' })
        }
        reject(body)
      },
      fail(err) {
        if (showError) {
          wx.showToast({ title: '网络异常，请检查后端是否启动', icon: 'none' })
        }
        reject(err)
      }
    })
  })
}

/** 小程序登录：mock 模式下用固定 code，真实模式用 wx.login。 */
function login() {
  return new Promise((resolve, reject) => {
    const exchange = (code) => {
      request({
        url: '/auth/wechat-login',
        method: 'POST',
        needAuth: false,
        showError: false,
        data: { code }
      })
        .then((data) => {
          setToken(data.token)
          setUser(data.user)
          resolve(data)
        })
        .catch(reject)
    }

    if (config.mockLogin) {
      exchange(config.devCode)
      return
    }
    wx.login({
      success(res) {
        if (!res.code) {
          reject(new Error('wx.login 未返回 code'))
          return
        }
        exchange(res.code)
      },
      fail: reject
    })
  })
}

/** 确保已登录（页面 onLoad 调用即可）。 */
function ensureLogin() {
  if (getToken()) {
    return Promise.resolve({ token: getToken(), user: getUser() })
  }
  return login()
}

module.exports = {
  request,
  login,
  ensureLogin,
  getToken,
  getUser,
  setUser,
  clearAuth
}
