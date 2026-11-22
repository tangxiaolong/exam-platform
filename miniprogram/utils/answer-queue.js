/**
 * 离线答题队列。
 *
 * 解决的问题：答题页保存答案的请求一旦失败，原实现是 `.catch(() => undefined)` ——
 * 用户的答案就这么没了，本人还以为已经保存（顶部「已保存 N 题」的数字不会变）。
 * 断网、切后台、弱信号下丢答案是考试类产品最不能接受的故障，因为后果不可挽回。
 *
 * 做法：失败就落到本地（wx.setStorageSync，小程序被杀也不丢），恢复后按题重放。
 * 同一道题只保留最后一次答案——重放的是「最终想选的」，不是「当时点过的每一次」。
 */
const STORAGE_KEY = 'exam_answer_queue'
// 上限：一场考试最多几百题，200 条足够；只是防止异常情况下把本地存储写爆
const MAX_ITEMS = 200

function readAll() {
  try {
    return wx.getStorageSync(STORAGE_KEY) || []
  } catch (e) {
    return []
  }
}

function saveAll(list) {
  try {
    wx.setStorageSync(STORAGE_KEY, list)
  } catch (e) {
    // 本地存储写失败时只能放弃暂存：不能因为暂存失败而阻断答题
    wx.showToast({ title: '本地存储不可用，答案可能未保存', icon: 'none' })
  }
}

function keyOf(item) {
  return item.scope + '|' + item.targetId + '|' + item.questionId
}

/** 入队：同 scope + targetId + questionId 只留最后一次。 */
function enqueue(item) {
  const list = readAll().filter((x) => keyOf(x) !== keyOf(item))
  list.push(item)
  saveAll(list.slice(Math.max(0, list.length - MAX_ITEMS)))
}

function pending(scope, targetId) {
  return readAll().filter((x) => x.scope === scope && String(x.targetId) === String(targetId))
}

function drop(item) {
  saveAll(readAll().filter((x) => keyOf(x) !== keyOf(item)))
}

/** 交卷/离开后清掉该场次的残留（正常情况队列本就为空）。 */
function clear(scope, targetId) {
  saveAll(readAll().filter((x) => !(x.scope === scope && String(x.targetId) === String(targetId))))
}

/**
 * 重放队列。
 *
 * @param scope      业务域，如 attempt / practice
 * @param targetId   attemptId 或 practiceId
 * @param send       单条发送函数，返回 Promise（resolve 后端结果）
 * @param onConflict 收到 saved=false（服务端保留了更新版本）时的回调
 * @returns Promise<剩余条数>
 */
function flush(scope, targetId, send, onConflict) {
  const items = pending(scope, targetId)
  let index = 0
  let stopped = false

  function step() {
    if (stopped || index >= items.length) {
      return Promise.resolve(pending(scope, targetId).length)
    }
    const item = items[index++]
    return send(item)
      .then((res) => {
        drop(item)
        if (res && res.saved === false && onConflict) {
          onConflict(item, res)
        }
        return step()
      })
      .catch((err) => {
        // 业务错误（带 code，如已交卷、场次过期）：重放多少次都不会成功。
        // 必须出队并继续下一条——留在队列里等于每次 onShow 都重试一次，
        // 而且它排在最前面时会挡住后面本来能同步成功的答案。
        if (err && err.code) {
          drop(item)
          return step()
        }
        // 网络错误：停止本轮，保留队列，等网络恢复或页面再进入
        stopped = true
        return pending(scope, targetId).length
      })
  }

  return step()
}

module.exports = {
  enqueue,
  pending,
  drop,
  clear,
  flush
}
