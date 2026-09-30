import test from 'node:test'
import assert from 'node:assert/strict'
import { mkdtemp, rm, readFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { randomBytes } from 'node:crypto'
import { createApp } from '../server/app.mjs'

const origin = 'https://sms.example.test'
const details = { lines: [{ slot: 0, number: '13800138000' }, { slot: 1, number: '13900139000' }], model: 'Test phone', androidVersion: '15', smsPermission: true, paused: false, appVersion: '0.1.0' }

async function fixture(t) {
  const directory = await mkdtemp(join(tmpdir(), 'sms-hub-test-'))
  let now = Date.now()
  const app = createApp({ directory, key: randomBytes(32), password: 'test-password-very-long', publicUrl: origin, staticDirectory: directory, now: () => now })
  await new Promise(resolve => app.server.listen(0, '127.0.0.1', resolve))
  t.after(async () => { await app.close(); await rm(directory, { recursive: true, force: true }) })
  const base = `http://127.0.0.1:${app.server.address().port}`
  async function request(path, { body, cookie, token, method = body ? 'POST' : 'GET', requestOrigin = origin } = {}) {
    const headers = { 'Content-Type': 'application/json' }
    if (requestOrigin) headers.Origin = requestOrigin
    if (cookie) headers.Cookie = cookie
    if (token) headers.Authorization = `Bearer ${token}`
    const response = await fetch(base + path, { method, headers, body: body ? JSON.stringify(body) : undefined })
    return { status: response.status, data: await response.json(), cookie: response.headers.get('set-cookie')?.split(';')[0], headers: response.headers }
  }
  const login = await request('/api/login', { body: { password: 'test-password-very-long' } })
  assert.equal(login.status, 200)
  const cookie = login.cookie
  async function pair(name = '测试手机') {
    const pairing = await request('/api/pairings', { cookie, body: { name } })
    assert.equal(pairing.status, 201)
    const result = await request('/api/device/pair', { body: { code: pairing.data.code, details }, requestOrigin: null })
    assert.equal(result.status, 201)
    return { ...result.data, code: pairing.data.code }
  }
  const message = (id = 'test-event-0001', slot = 0) => ({ id, slot, phone: details.lines[slot].number, body: '【测试】验证码 583921，请勿泄露。', code: '583921', sender: '10690000', receivedAt: now })
  return { app, request, cookie, pair, message, directory, advance(ms) { now += ms } }
}

test('登录保护、来源校验、HttpOnly 会话和退出后失效', async t => {
  const f = await fixture(t)
  assert.equal((await f.request('/api/dashboard')).status, 401)
  assert.equal((await f.request('/api/login', { body: { password: 'test-password-very-long' }, requestOrigin: 'https://evil.test' })).status, 403)
  assert.equal((await f.request('/api/login', { body: { password: 'wrong-password' } })).status, 401)
  const login = await f.request('/api/login', { body: { password: 'test-password-very-long' } })
  assert.match(login.headers.get('set-cookie'), /HttpOnly; SameSite=Strict/)
  assert.match(login.headers.get('set-cookie'), /Secure/)
  assert.equal((await f.request('/api/dashboard', { cookie: f.cookie })).status, 200)
  assert.equal((await f.request('/api/logout', { cookie: f.cookie, body: {} })).status, 200)
  assert.equal((await f.request('/api/dashboard', { cookie: f.cookie })).status, 401)
})

test('配对码一次使用、10 分钟失效、设备令牌不能读取收件箱', async t => {
  const f = await fixture(t), phone = await f.pair()
  assert.equal((await f.request('/api/device/pair', { body: { code: phone.code, details } })).status, 401)
  const next = await f.request('/api/pairings', { cookie: f.cookie, body: { name: '过期设备' } })
  f.advance(10 * 60000)
  assert.equal((await f.request('/api/device/pair', { body: { code: next.data.code, details } })).status, 401)
  assert.equal((await f.request('/api/dashboard', { token: phone.token })).status, 401)
  assert.equal((await f.request('/api/device/sync', { body: { messages: [], details }, token: 'x'.repeat(43) })).status, 401)
})

test('双卡、多设备、断网重复补传去重，停用后不能上传', async t => {
  const f = await fixture(t), phone = await f.pair(), second = await f.pair('手机 2')
  const batch = { messages: [f.message(), f.message('second-sim-0001', 1)], details }
  let result = await f.request('/api/device/sync', { token: phone.token, body: batch })
  assert.deepEqual(result.data.acknowledged, ['test-event-0001', 'second-sim-0001'])
  assert.equal((await f.request('/api/device/sync', { token: phone.token, body: batch })).status, 200)
  assert.equal((await f.request('/api/device/sync', { token: second.token, body: { messages: [f.message()], details } })).status, 200)
  const dashboard = await f.request('/api/dashboard', { cookie: f.cookie })
  assert.equal(dashboard.data.messages.length, 3)
  assert.equal(dashboard.data.devices.length, 2)
  assert.equal(dashboard.data.messages.find(m => m.slot === 1).phone, '13900139000')
  assert.ok(dashboard.data.messages.every(m => m.body.includes('583921')))
  assert.equal((await f.request(`/api/devices/${phone.id}`, { cookie: f.cookie, method: 'DELETE' })).status, 200)
  assert.equal((await f.request('/api/device/sync', { token: phone.token, body: batch })).status, 401)
})

test('24 小时清理、过期补传确认删除、短信原文不明文落盘', async t => {
  const f = await fixture(t), phone = await f.pair(), message = f.message()
  await f.request('/api/device/sync', { token: phone.token, body: { messages: [message], details } })
  for (const name of ['sms-hub.sqlite', 'sms-hub.sqlite-wal']) {
    const file = await readFile(join(f.directory, name))
    assert.equal(file.includes(Buffer.from('583921')), false)
    assert.equal(file.includes(Buffer.from('13800138000')), false)
  }
  f.advance(25 * 3600000)
  f.app.store.cleanup()
  assert.equal(f.app.store.messages().length, 0)
  const result = await f.request('/api/device/sync', { token: phone.token, body: { messages: [message], details } })
  assert.deepEqual(result.data.acknowledged, [message.id])
  assert.equal(f.app.store.messages().length, 0)
})

test('整批校验防止半批写入、拒绝未来时间与无效卡槽', async t => {
  const f = await fixture(t), phone = await f.pair()
  const invalid = { ...f.message('invalid-event'), receivedAt: Date.now() + 3600000 }
  assert.equal((await f.request('/api/device/sync', { token: phone.token, body: { messages: [f.message(), invalid], details } })).status, 400)
  assert.equal(f.app.store.messages().length, 0)
  assert.equal((await f.request('/api/device/sync', { token: phone.token, body: { messages: [{ ...f.message(), slot: 5 }], details } })).status, 400)
  const unknown = { ...f.message(), slot: -1, phone: '' }
  assert.equal((await f.request('/api/device/sync', { token: phone.token, body: { messages: [unknown], details } })).status, 200)
})

test('备注、暂停状态和显式清空记录', async t => {
  const f = await fixture(t), phone = await f.pair()
  await f.request('/api/device/sync', { token: phone.token, body: { messages: [f.message()], details: { ...details, paused: true } } })
  await f.request(`/api/devices/${phone.id}`, { cookie: f.cookie, method: 'PATCH', body: { name: '备用手机' } })
  const dashboard = (await f.request('/api/dashboard', { cookie: f.cookie })).data
  assert.equal(dashboard.devices[0].paused, true)
  assert.equal(dashboard.messages[0].deviceName, '备用手机')
  assert.equal((await f.request('/api/messages', { cookie: f.cookie, method: 'DELETE', requestOrigin: null })).status, 403)
  assert.equal((await f.request('/api/messages', { cookie: f.cookie, method: 'DELETE' })).status, 200)
  assert.equal(f.app.store.messages().length, 0)
})
