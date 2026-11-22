/**
 * 离线答题队列的单元测试。
 *
 * 为什么单独测这一层：`answer-queue.js` 的全部语义（按题去重、200 条上限、按场次隔离、
 * 失败停止 / 业务错误出队）都只依赖 `wx.getStorageSync` / `wx.setStorageSync`，
 * 用最小桩就能在 Node 里跑，不需要微信开发者工具。剩下真正只能在真机验的只有
 * 「网络事件是否触发」与「toast 是否可见」，那部分留在 doc/08 的人工清单里。
 *
 * 跑法：node --test tools/miniprogram-tests/
 */
const test = require('node:test')
const assert = require('node:assert/strict')
const path = require('node:path')

const queue = require(path.join(__dirname, '..', '..', 'miniprogram', 'utils', 'answer-queue'))

const STORAGE_KEY = 'exam_answer_queue'

/** 最小 wx 桩：存储做深拷贝，避免测试之间因为共用引用而「假通过」。 */
function installWxStub() {
  const store = new Map()
  global.wx = {
    getStorageSync(key) {
      return store.has(key) ? JSON.parse(store.get(key)) : ''
    },
    setStorageSync(key, value) {
      store.set(key, JSON.stringify(value))
    },
    showToast() {}
  }
  return store
}

function item(questionId, answer, targetId = '1', scope = 'attempt') {
  return { scope, targetId, questionId, answer, clientVersion: null, savedAt: Date.now() }
}

test.beforeEach(() => {
  installWxStub()
})

test('MP-04 同一题多次作答只保留最后一次（重放的是最终想选的）', () => {
  queue.enqueue(item(10, ['A']))
  queue.enqueue(item(10, ['B']))
  queue.enqueue(item(10, ['C']))

  const pending = queue.pending('attempt', '1')
  assert.equal(pending.length, 1, '同一题必须只占一条，否则「待同步 N」会随点击次数增长')
  assert.deepEqual(pending[0].answer, ['C'], '重放的必须是最后一次答案，不是最早那次')
})

test('MP-01 不同题各自入队，「待同步」等于真实条数', () => {
  queue.enqueue(item(10, ['A']))
  queue.enqueue(item(11, ['B']))

  assert.equal(queue.pending('attempt', '1').length, 2)
})

test('MP-08 队列按场次隔离：清掉上一场不会影响下一场，也不会串题', () => {
  queue.enqueue(item(10, ['A'], '1'))
  queue.enqueue(item(10, ['B'], '2'))

  queue.clear('attempt', '1')

  assert.equal(queue.pending('attempt', '1').length, 0, '交卷成功后该场次必须清空')
  assert.equal(queue.pending('attempt', '2').length, 1, '下一场的队列不能被上一场的 clear 连坐')
  assert.deepEqual(queue.pending('attempt', '2')[0].answer, ['B'], '下一场不能出现上一场的答案')
})

test('MP-09 队列上限 200 条，超出时丢最早的而不是写爆本地存储', () => {
  for (let i = 0; i < 205; i += 1) {
    queue.enqueue(item(i, ['A']))
  }

  const pending = queue.pending('attempt', '1')
  assert.equal(pending.length, 200)
  assert.equal(pending[0].questionId, 5, '保留的是最近 200 条')
  assert.equal(pending[199].questionId, 204)
})

test('MP-02 恢复网络后按入队顺序重放，成功一条出队一条', async () => {
  queue.enqueue(item(10, ['A']))
  queue.enqueue(item(11, ['B']))
  queue.enqueue(item(12, ['C']))

  const sent = []
  const left = await queue.flush('attempt', '1', (x) => {
    sent.push(x.questionId)
    return Promise.resolve({ saved: true, serverVersion: 1 })
  })

  assert.deepEqual(sent, [10, 11, 12], '重放顺序应与入队顺序一致')
  assert.equal(left, 0)
  assert.equal(queue.pending('attempt', '1').length, 0, '全部同步后队列必须清空')
})

test('MP-03 仍在断网时整轮保留队列，不误删任何一条', async () => {
  queue.enqueue(item(10, ['A']))
  queue.enqueue(item(11, ['B']))

  const left = await queue.flush('attempt', '1', () => Promise.reject(new Error('request:fail')))

  assert.equal(left, 2, '网络错误必须原样保留，等下次网络恢复或 onShow 再试')
  assert.equal(queue.pending('attempt', '1').length, 2)
})

test('MP-10 业务错误（带 code）出队并继续，不阻塞后面的答案', async () => {
  queue.enqueue(item(10, ['A']))
  queue.enqueue(item(11, ['B']))
  queue.enqueue(item(12, ['C']))

  const sent = []
  const left = await queue.flush('attempt', '1', (x) => {
    sent.push(x.questionId)
    if (x.questionId === 11) {
      // 例如这场考试已在别处被交卷，重放多少次都不会成功
      return Promise.reject({ code: 'EXAM_ALREADY_SUBMITTED', message: '已交卷' })
    }
    return Promise.resolve({ saved: true })
  })

  assert.equal(left, 0, '业务错误不应留在队列里反复重试')
  assert.deepEqual(sent, [10, 11, 12], '注定失败的那条不能挡住后面本来能成功的答案')
  assert.equal(queue.pending('attempt', '1').length, 0)
})

test('MP-06 服务端保留更新版本（saved=false）时该条出队并回调冲突', async () => {
  queue.enqueue(item(10, ['A']))

  const conflicts = []
  await queue.flush(
    'attempt',
    '1',
    () => Promise.resolve({ saved: false, serverVersion: 9 }),
    (x) => conflicts.push(x.questionId)
  )

  assert.deepEqual(conflicts, [10], '冲突必须回调出去，否则用户以为存的是自己的答案')
  assert.equal(queue.pending('attempt', '1').length, 0, '该条已成功送达，只是被覆盖，不该残留')
})

test('drop 只删指定的一条，不动同场次的其他题', () => {
  queue.enqueue(item(10, ['A']))
  queue.enqueue(item(11, ['B']))

  queue.drop(item(10, ['A']))

  const pending = queue.pending('attempt', '1')
  assert.equal(pending.length, 1)
  assert.equal(pending[0].questionId, 11)
})

test('本地存储读失败时当作空队列，不抛异常阻断答题', () => {
  global.wx.getStorageSync = () => {
    throw new Error('storage unavailable')
  }

  assert.doesNotThrow(() => queue.pending('attempt', '1'))
  assert.deepEqual(queue.pending('attempt', '1'), [])
})

test('本地存储写失败时只提示，不抛出（不能因为暂存失败而阻断答题）', () => {
  const toasts = []
  global.wx.setStorageSync = () => {
    throw new Error('quota exceeded')
  }
  global.wx.showToast = (opts) => toasts.push(opts.title)

  assert.doesNotThrow(() => queue.enqueue(item(10, ['A'])))
  assert.equal(toasts.length, 1, '存储不可用必须让用户知道答案可能没保存')
})

test('队列键名稳定：升级后不能因为改名把用户没同步的答案丢掉', () => {
  const store = installWxStub()
  queue.enqueue(item(10, ['A']))

  assert.ok(store.has(STORAGE_KEY), `本地存储键必须保持 ${STORAGE_KEY}，改名等于清空所有未同步答案`)
})
