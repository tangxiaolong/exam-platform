/** 时间与金额格式化工具。 */

function pad(n) {
  return n < 10 ? '0' + n : '' + n
}

function parseTime(value) {
  if (!value) return null
  // 后端返回 ISO 字符串（2026-11-14T18:13:26.064），部分 iOS 不认 "-" 连接，需替换
  return new Date(String(value).replace(/-/g, '/').replace('T', ' '))
}

function formatDate(value) {
  const d = parseTime(value)
  if (!d || isNaN(d.getTime())) return '-'
  return (
    d.getFullYear() +
    '-' + pad(d.getMonth() + 1) +
    '-' + pad(d.getDate()) +
    ' ' + pad(d.getHours()) +
    ':' + pad(d.getMinutes()) +
    ':' + pad(d.getSeconds())
  )
}

function formatDuration(seconds) {
  if (seconds == null || seconds < 0) seconds = 0
  const h = Math.floor(seconds / 3600)
  const m = Math.floor((seconds % 3600) / 60)
  const s = seconds % 60
  return (h > 0 ? pad(h) + ':' : '') + pad(m) + ':' + pad(s)
}

/** 后端返回的纳秒精度时间戳（.8198991）需要裁剪，否则低版本 iOS 解析失败 */
function toTimestamp(value) {
  if (!value) return 0
  const text = String(value)
    .replace('T', ' ')
    .replace(/(\.\d{3})\d+/, '$1')
    .replace(/-/g, '/')
  const time = new Date(text).getTime()
  return isNaN(time) ? 0 : time
}

function fen2yuan(fen) {
  return '¥' + ((fen || 0) / 100).toFixed(2)
}

module.exports = { formatDate, formatDuration, fen2yuan, parseTime, toTimestamp }
