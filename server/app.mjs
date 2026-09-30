import { createServer } from 'node:http'
import { scryptSync, timingSafeEqual } from 'node:crypto'
import { readFile } from 'node:fs/promises'
import { resolve, extname } from 'node:path'
import { createStore } from './store.mjs'

class HttpError extends Error { constructor(status, message) { super(message); this.status = status } }
const fail = (status, message) => { throw new HttpError(status, message) }
function string(value, max, label, required = true) {
  if (typeof value !== 'string' || value.length > max || (required && !value.trim())) fail(400, `${label}格式不正确`)
  return value.trim()
}
function deviceDetails(input) {
  if (!input || typeof input !== 'object') fail(400, '缺少设备信息')
  if (!Array.isArray(input.lines) || input.lines.length > 2) fail(400, '最多配置两个号码')
  const lines = input.lines.map(line => {
    if (![0, 1].includes(line.slot)) fail(400, '卡槽格式不正确')
    const number = string(line.number, 24, '手机号')
    if (!/^\+?[0-9 ()-]{3,24}$/.test(number)) fail(400, '手机号格式不正确')
    return { slot: line.slot, number }
  })
  if (new Set(lines.map(line => line.slot)).size !== lines.length) fail(400, '卡槽不能重复')
  return { lines, model: string(input.model ?? '', 100, '型号', false),
    androidVersion: string(input.androidVersion ?? '', 30, '系统版本', false),
    appVersion: string(input.appVersion ?? '', 30, '应用版本', false),
    paused: Boolean(input.paused), smsPermission: Boolean(input.smsPermission) }
}
function messageBatch(input, now) {
  if (!Array.isArray(input) || input.length > 32) fail(400, '单次最多同步 32 条短信')
  return input.map(message => {
    if (!message || typeof message !== 'object') fail(400, '短信格式不正确')
    const id = string(message.id, 100, '短信编号')
    if (!/^[A-Za-z0-9_-]{8,100}$/.test(id)) fail(400, '短信编号格式不正确')
    if (!Number.isSafeInteger(message.receivedAt) || message.receivedAt < 0 || message.receivedAt > now + 300000) fail(400, '手机时间不正确，请开启自动时间')
    if (![-1, 0, 1].includes(message.slot)) fail(400, '短信卡槽不正确')
    return { id, receivedAt: message.receivedAt, slot: message.slot,
      phone: string(message.phone ?? '', 24, '手机号', false), sender: string(message.sender, 100, '发送方'),
      body: string(message.body, 8000, '短信内容'), code: string(message.code ?? '', 24, '验证码', false) }
  })
}
async function jsonBody(req) {
  if (!(req.headers['content-type'] ?? '').startsWith('application/json')) fail(415, '请使用 JSON 请求')
  let size = 0, chunks = []
  for await (const chunk of req) { size += chunk.length; if (size > 1100000) fail(413, '请求过大'); chunks.push(chunk) }
  try { const value = JSON.parse(Buffer.concat(chunks)); if (!value || typeof value !== 'object' || Array.isArray(value)) fail(400, '请求格式不正确'); return value }
  catch { fail(400, '请求格式不正确') }
}

export function createApp(config) {
  const now = config.now ?? Date.now
  if (typeof config.password !== 'string' || config.password.length < 12) throw new Error('ADMIN_PASSWORD 至少 12 位，请先运行 npm run setup')
  if (!Buffer.isBuffer(config.key) || config.key.length !== 32) throw new Error('DATA_KEY 必须是 32 字节密钥的 Base64 编码')
  const publicUrl = new URL(config.publicUrl)
  if (publicUrl.protocol !== 'https:' && !(publicUrl.protocol === 'http:' && ['localhost', '127.0.0.1', '[::1]'].includes(publicUrl.hostname))) throw new Error('公网访问必须使用 HTTPS')
  if (publicUrl.pathname !== '/' || publicUrl.search || publicUrl.hash || publicUrl.username || publicUrl.password) throw new Error('PUBLIC_URL 必须是网站根地址')
  const retentionHours = Number(config.retentionHours ?? 24)
  if (!Number.isFinite(retentionHours) || retentionHours < 1 || retentionHours > 168) throw new Error('RETENTION_HOURS 应为 1 至 168')
  const store = createStore({ directory: config.directory, key: config.key, retentionHours, now })
  const passwordHash = scryptSync(config.password, 'sms-hub-admin-v1', 32)
  const attempts = new Map()
  const secureCookie = publicUrl.protocol === 'https:' ? '; Secure' : ''
  const cookie = (token, age = 43200) => `sms_session=${token}; Path=/; HttpOnly; SameSite=Strict; Max-Age=${age}${secureCookie}`
  function throttle(key, limit) {
    let bucket = attempts.get(key)
    if (!bucket || bucket.until <= now()) { bucket = { count: 0, until: now() + 60000 }; attempts.set(key, bucket) }
    if (++bucket.count > limit) fail(429, '请求过于频繁，请稍后重试')
  }
  const maintenance = setInterval(() => {
    store.cleanup()
    for (const [key, value] of attempts) if (value.until <= now()) attempts.delete(key)
  }, 60000).unref()
  const server = createServer(async (req, res) => {
    const send = (status, value) => { res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8' }); res.end(JSON.stringify(value)) }
    res.setHeader('Cache-Control', 'no-store')
    res.setHeader('X-Content-Type-Options', 'nosniff')
    res.setHeader('Referrer-Policy', 'no-referrer')
    res.setHeader('X-Frame-Options', 'DENY')
    res.setHeader('Content-Security-Policy', "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'")
    if (secureCookie) res.setHeader('Strict-Transport-Security', 'max-age=31536000')
    try {
      if (!req.url || req.url.length > 2000) fail(400, '请求地址不正确')
      const path = new URL(req.url, 'http://localhost').pathname
      const method = req.method
      const session = /(?:^|;\s*)sms_session=([A-Za-z0-9_-]+)/.exec(req.headers.cookie ?? '')?.[1]
      if (path === '/api/health' && method === 'GET') return send(200, { ok: true })
      // Browsers authenticate with cookies and an exact Origin. Phones use revocable bearer tokens.
      if (path.startsWith('/api/') && !['GET', 'HEAD'].includes(method)
          && !['/api/device/pair', '/api/device/sync'].includes(path)) {
        const origins = [publicUrl.origin, ...(config.devOrigins ?? [])]
        if (!origins.includes(req.headers.origin)) fail(403, '请求来源不正确，请从配置的网站地址访问')
      }
      if (path === '/api/login' && method === 'POST') {
        throttle(`login:${req.socket.remoteAddress}`, 10)
        const body = await jsonBody(req)
        const password = string(body.password, 512, '密码')
        if (!timingSafeEqual(passwordHash, scryptSync(password, 'sms-hub-admin-v1', 32))) fail(401, '密码不正确')
        res.setHeader('Set-Cookie', cookie(store.session()))
        return send(200, { ok: true })
      }
      if (path === '/api/device/pair' && method === 'POST') {
        throttle(`pair:${req.socket.remoteAddress}`, 20)
        const body = await jsonBody(req)
        const code = string(body.code, 40, '配对码').replace(/[\s-]/g, '').toUpperCase()
        const result = store.redeem(code, deviceDetails(body.details))
        if (!result) fail(401, '配对码无效、已使用或已过期，请在网页重新生成')
        return send(201, result)
      }
      if (path === '/api/device/sync' && method === 'POST') {
        const token = /^Bearer ([A-Za-z0-9_-]{43})$/.exec(req.headers.authorization ?? '')?.[1]
        const device = token && store.device(token)
        if (!device) fail(401, '设备授权已失效，请重新配对')
        throttle(`device:${device.id}`, 120)
        const body = await jsonBody(req)
        const acknowledged = store.ingest(device.id, messageBatch(body.messages, now()), deviceDetails(body.details))
        return send(200, { acknowledged, serverTime: now() })
      }
      if (path.startsWith('/api/')) {
        if (!store.hasSession(session)) fail(401, '请先登录')
        if (path === '/api/logout' && method === 'POST') { store.logout(session); res.setHeader('Set-Cookie', cookie('', 0)); return send(200, { ok: true }) }
        if (path === '/api/dashboard' && method === 'GET') return send(200, { devices: store.devices(), messages: store.messages(), retentionHours, serverTime: now(), publicUrl: publicUrl.origin })
        if (path === '/api/pairings' && method === 'POST') {
          throttle('pairing-create', 30)
          const body = await jsonBody(req)
          return send(201, { ...store.pair(string(body.name, 60, '设备名称')), serverUrl: publicUrl.origin })
        }
        const match = /^\/api\/devices\/([a-f0-9-]{36})$/.exec(path)
        if (match && method === 'DELETE') { if (!store.revoke(match[1])) fail(404, '设备不存在'); return send(200, { ok: true }) }
        if (match && method === 'PATCH') { const body = await jsonBody(req); if (!store.rename(match[1], string(body.name, 60, '设备名称'))) fail(404, '设备不存在'); return send(200, { ok: true }) }
        if (path === '/api/messages' && method === 'DELETE') { store.clearMessages(); return send(200, { ok: true }) }
        fail(404, '接口不存在')
      }
      if (method !== 'GET' && method !== 'HEAD') fail(405, '不支持的请求方法')
      const staticPath = path === '/' ? 'index.html' : path.slice(1)
      if (!/^[a-zA-Z0-9_./-]+$/.test(staticPath) || staticPath.includes('..')) fail(404, '页面不存在')
      const types = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8', '.svg': 'image/svg+xml', '.ico': 'image/x-icon' }
      let file
      try { file = await readFile(resolve(config.staticDirectory, staticPath)) }
      catch { fail(404, '页面不存在，请先运行 npm run build') }
      res.writeHead(200, { 'Content-Type': types[extname(staticPath)] ?? 'application/octet-stream' })
      res.end(method === 'HEAD' ? undefined : file)
    } catch (error) {
      // Never include request bodies, OTPs or credentials in logs or error responses.
      if (!(error instanceof HttpError)) console.error('Request failed:', error.code ?? error.name)
      if (!res.headersSent) send(error.status ?? 500, { error: error.status ? error.message : '服务暂时不可用，请稍后重试' })
      else res.end()
    }
  })
  server.requestTimeout = 20000
  server.headersTimeout = 10000
  return { server, store, close() { clearInterval(maintenance); server.closeAllConnections(); return new Promise(resolveClose => server.close(() => { store.close(); resolveClose() })) } }
}
