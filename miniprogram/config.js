/**
 * 小程序全局配置。
 *
 * 说明：
 * 1) devtools 需勾选「不校验合法域名」才能直连 localhost；
 * 2) mockLogin=true 时用固定的 devCode 换取 openid，保证本地反复进入仍是同一个考生账号；
 *    真实联调时置为 false，走 wx.login 的 code 换 session（后端调用 jscode2session）。
 */
module.exports = {
  baseUrl: 'http://localhost:8080/api/v1',
  mockLogin: true,
  devCode: 'dev-code-001',
  businessName: '综合考试平台'
}
