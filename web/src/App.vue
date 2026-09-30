<script setup>
import { computed, nextTick, onMounted, onUnmounted, ref } from 'vue'

const authenticated = ref(false), checking = ref(true), password = ref(''), busy = ref(false)
const error = ref(''), connectionError = ref(''), toast = ref(''), tab = ref('inbox')
const devices = ref([]), messages = ref([]), retentionHours = ref(24), serverUrl = ref('')
const query = ref(''), selectedPhone = ref(''), selectedDevice = ref(''), clock = ref(Date.now()), lastRefresh = ref(0)
const pairDialog = ref(null), pairName = ref(''), pairing = ref(null), pairError = ref(''), pairingBusy = ref(false)
let interval, toastTimer, refreshing = false

async function api(path, options = {}) {
  const response = await fetch(path, { credentials: 'same-origin', ...options, headers: { 'Content-Type': 'application/json', ...options.headers } })
  const result = await response.json()
  if (!response.ok) {
    if (response.status === 401 && !path.endsWith('/login')) {
      authenticated.value = false; devices.value = []; messages.value = []; pairDialog.value?.close(); pairing.value = null
    }
    throw new Error(result.error ?? '请求失败')
  }
  return result
}
async function refresh() {
  if (refreshing) return
  refreshing = true
  try {
    const result = await api('/api/dashboard')
    authenticated.value = true; devices.value = result.devices; messages.value = result.messages
    retentionHours.value = result.retentionHours; serverUrl.value = result.publicUrl
    lastRefresh.value = Date.now(); connectionError.value = ''
  } catch (err) { if (authenticated.value) connectionError.value = err.message }
  finally { checking.value = false; refreshing = false }
}
async function login() {
  busy.value = true; error.value = ''
  try { await api('/api/login', { method: 'POST', body: JSON.stringify({ password: password.value }) }); password.value = ''; await refresh() }
  catch (err) { error.value = err.message }
  finally { busy.value = false }
}
async function logout() {
  try {
    await api('/api/logout', { method: 'POST', body: '{}' })
    authenticated.value = false; messages.value = []; devices.value = []; selectedDevice.value = ''; selectedPhone.value = ''; query.value = ''
  } catch (err) { notify(err.message) }
}
function notify(value) { toast.value = value; clearTimeout(toastTimer); toastTimer = setTimeout(() => { toast.value = '' }, 3000) }
async function copy(value) {
  try { await navigator.clipboard.writeText(value); notify('已复制') }
  catch { notify('复制失败，请长按或选中文字复制') }
}
function relative(time) {
  if (!time) return '尚未连接'
  const seconds = Math.max(0, Math.floor((clock.value - time) / 1000))
  if (seconds < 60) return `${seconds} 秒前`
  if (seconds < 3600) return `${Math.floor(seconds / 60)} 分钟前`
  return `${Math.floor(seconds / 3600)} 小时前`
}
function exact(time) { return new Date(time).toLocaleString('zh-CN', { hour12: false }) }
function source(message) { return /[【\[]([^】\]]{1,24})[】\]]/.exec(message.body)?.[1] || message.sender }
function status(device) {
  if (device.revoked) return '已停用'
  if (device.paused) return '已暂停'
  if (!device.smsPermission) return '未授权短信'
  if (clock.value - device.lastSeen > 30 * 60000) return '连接待确认'
  return '最近已连接'
}
const activeDevices = computed(() => devices.value.filter(device => !device.revoked))
const boundPhones = computed(() => [...new Set(activeDevices.value.flatMap(device => device.lines.map(line => line.number)))])
const phones = computed(() => [...new Set([...activeDevices.value.flatMap(device => device.lines.map(line => line.number)), ...messages.value.map(message => message.phone)].filter(Boolean))])
const filtered = computed(() => {
  const text = query.value.trim().toLowerCase()
  return messages.value.filter(message => (!selectedPhone.value || message.phone === selectedPhone.value)
    && (!selectedDevice.value || message.deviceId === selectedDevice.value)
    && (!text || `${message.phone} ${message.deviceName} ${message.sender} ${message.body}`.toLowerCase().includes(text)))
})
const recentCount = computed(() => messages.value.filter(message => clock.value - message.receivedAt < 10 * 60000).length)
const pairExpired = computed(() => pairing.value && clock.value >= pairing.value.expiresAt)
async function openPair() {
  pairName.value = `手机 ${activeDevices.value.length + 1}`; pairing.value = null; pairError.value = ''
  await nextTick(); pairDialog.value.showModal()
}
async function createPairing() {
  pairingBusy.value = true; pairError.value = ''
  try { pairing.value = await api('/api/pairings', { method: 'POST', body: JSON.stringify({ name: pairName.value }) }) }
  catch (err) { pairError.value = err.message }
  finally { pairingBusy.value = false }
}
async function rename(device) {
  const name = window.prompt('新的设备备注', device.name)
  if (!name?.trim() || name.trim() === device.name) return
  try { await api(`/api/devices/${device.id}`, { method: 'PATCH', body: JSON.stringify({ name }) }); await refresh() }
  catch (err) { notify(err.message) }
}
async function revoke(device) {
  if (!window.confirm(`停用「${device.name}」后，这部手机将无法继续上传。已有记录保留至自动清理，恢复使用需要重新配对。`)) return
  try { await api(`/api/devices/${device.id}`, { method: 'DELETE' }); await refresh(); notify('设备已停用') }
  catch (err) { notify(err.message) }
}
async function clearMessages() {
  if (!window.confirm('清空服务器上的全部短信记录？这不会删除手机上的短信，已上传的记录也不会自动重新上传。')) return
  try { await api('/api/messages', { method: 'DELETE' }); await refresh(); notify('短信记录已清空') }
  catch (err) { notify(err.message) }
}
onMounted(() => {
  refresh()
  interval = setInterval(() => { clock.value = Date.now(); if (authenticated.value && !document.hidden) refresh() }, 3000)
})
onUnmounted(() => { clearInterval(interval); clearTimeout(toastTimer) })
</script>

<template>
  <main v-if="checking" class="loading" aria-live="polite">正在连接收件箱…</main>
  <main v-else-if="!authenticated" class="login-page">
    <div class="login-brand"><span class="brand-mark">S</span><strong>SMS Hub</strong></div>
    <form class="login-card" @submit.prevent="login">
      <span class="eyebrow">你的验证码收件箱</span>
      <h1>所有号码，一处查看。</h1>
      <p>登录以查看手机同步的验证码和设备状态。</p>
      <label for="password">管理密码</label>
      <input id="password" v-model="password" type="password" autocomplete="current-password" required autofocus placeholder="输入管理密码" />
      <p v-if="error" class="error" role="alert">{{ error }}</p>
      <button class="primary" :disabled="busy">{{ busy ? '正在登录…' : '进入收件箱' }}</button>
      <small>短信仅在登录后可见。</small>
    </form>
  </main>
  <div v-else class="app-shell">
    <aside class="sidebar">
      <a class="brand" href="/" aria-label="SMS Hub 首页"><span class="brand-mark">S</span><span>SMS Hub<small>个人短信中心</small></span></a>
      <button class="mobile-logout" @click="logout">退出登录</button>
      <nav aria-label="主要导航">
        <button :class="{ active: tab === 'inbox' }" @click="tab = 'inbox'"><span>▤</span>验证码收件箱<b>{{ messages.length }}</b></button>
        <button :class="{ active: tab === 'devices' }" @click="tab = 'devices'"><span>▣</span>我的设备<b>{{ activeDevices.length }}</b></button>
      </nav>
      <div class="sidebar-bottom"><p>自动清理</p><strong>保留最近 {{ retentionHours }} 小时</strong><small>验证码有效期以发送平台为准</small><button class="logout" @click="logout">退出登录</button></div>
    </aside>
    <main class="workspace">
      <header class="page-header">
        <div><span class="eyebrow">{{ tab === 'inbox' ? '收件箱' : '设备管理' }}</span><h1>{{ tab === 'inbox' ? '验证码' : '我的设备' }}</h1><p>{{ tab === 'inbox' ? '新验证码会自动出现在这里。' : '配对你的安卓手机，查看最近连接状态。' }}</p></div>
        <button class="primary" @click="openPair">＋ 添加手机</button>
      </header>
      <div v-if="connectionError" class="banner error" role="alert">连接中断，当前显示 {{ exact(lastRefresh) }} 的记录。{{ connectionError }}<button @click="refresh">重试</button></div>
      <template v-if="tab === 'inbox'">
        <section class="stats" aria-label="收件箱统计">
          <div><span>已绑定号码</span><strong>{{ boundPhones.length }}<small>个</small></strong></div>
          <div><span>已配对设备</span><strong>{{ activeDevices.length }}<small>部</small></strong></div>
          <div><span>近 10 分钟</span><strong>{{ recentCount }}<small>条</small></strong></div>
          <div class="sync-state"><span :class="['status-dot', { warning: connectionError }]"></span><span>{{ connectionError ? '正在重新连接' : '每 3 秒自动更新' }}<small>最近更新 {{ relative(lastRefresh) }}</small></span></div>
        </section>
        <section class="inbox-panel">
          <div class="toolbar">
            <input v-model="query" type="search" aria-label="搜索验证码短信" placeholder="搜索号码、平台或短信内容" />
            <select v-model="selectedPhone" aria-label="按手机号筛选"><option value="">全部手机号</option><option v-for="phone in phones" :key="phone" :value="phone">{{ phone }}</option></select>
            <select v-model="selectedDevice" aria-label="按设备筛选"><option value="">全部设备</option><option v-for="device in devices" :key="device.id" :value="device.id">{{ device.name }}</option></select>
          </div>
          <div class="list-caption"><span>最新短信 <b>{{ filtered.length }}</b></span><span>最多显示最近 1,000 条</span></div>
          <div v-if="!filtered.length" class="empty-state">
            <div class="empty-symbol">▤</div><h2>{{ messages.length ? '没有匹配的短信' : '等待第一条验证码' }}</h2>
            <p>{{ messages.length ? '试试其他号码，或清空搜索条件。' : activeDevices.length ? '手机收到新的验证码短信后，会自动同步到这里。' : '先添加一部安卓手机，在 App 中完成配对和短信授权。' }}</p>
            <button v-if="!activeDevices.length" class="primary" @click="openPair">添加第一部手机</button>
          </div>
          <article v-for="message in filtered" :key="`${message.deviceId}:${message.id}`" class="message-row">
            <div class="message-main"><div class="message-heading"><strong>{{ source(message) }}</strong><time :datetime="new Date(message.receivedAt).toISOString()" :title="exact(message.receivedAt)">{{ relative(message.receivedAt) }}</time></div>
              <div class="message-phone">{{ message.phone || '来源卡待确认' }}<span>{{ message.deviceName }} · {{ message.slot < 0 ? '卡槽未知' : `卡 ${message.slot + 1}` }}</span></div>
              <details><summary>查看短信原文</summary><p class="message-body">{{ message.body }}</p><small>发送方 {{ message.sender }} · 同步于 {{ exact(message.uploadedAt) }}</small></details>
            </div>
            <div class="code-block"><button v-if="message.code" class="code-copy" :aria-label="`复制 ${source(message)} 验证码 ${message.code}`" @click="copy(message.code)"><code>{{ message.code }}</code><span>复制验证码</span></button><span v-else class="unrecognized">请查看原文</span></div>
          </article>
        </section>
        <footer v-if="messages.length" class="inbox-footer"><span>仅同步开启后新收到的验证码短信</span><button class="text-danger" @click="clearMessages">清空记录</button></footer>
      </template>
      <template v-else>
        <div class="device-note">后台任务可能被系统延迟。“最近已连接”表示近期同步成功，不代表手机始终在线。</div>
        <div v-if="!devices.length" class="empty-state panel"><div class="empty-symbol">▣</div><h2>还没有连接手机</h2><p>每部手机安装同一个 App，使用各自的配对码连接。</p><button class="primary" @click="openPair">添加手机</button></div>
        <section v-else class="devices-grid" aria-label="设备列表">
          <article v-for="device in devices" :key="device.id" class="device-card" :class="{ revoked: device.revoked }">
            <div class="device-card-header"><span class="device-icon">▣</span><span class="device-status" :class="{ muted: status(device) !== '最近已连接' }">{{ status(device) }}</span></div>
            <h2>{{ device.name }}</h2><p class="device-model">{{ device.model || '安卓手机' }} · Android {{ device.androidVersion || '未知' }}</p>
            <div class="device-lines"><p v-for="line in device.lines" :key="line.slot"><span>卡 {{ line.slot + 1 }}</span><strong>{{ line.number }}</strong></p><p v-if="!device.lines.length">尚未填写号码</p></div>
            <p class="last-seen" :title="exact(device.lastSeen)">最近连接 {{ relative(device.lastSeen) }}</p>
            <div v-if="!device.revoked" class="device-actions"><button @click="rename(device)">修改备注</button><button class="text-danger" @click="revoke(device)">停用设备</button></div>
          </article>
        </section>
      </template>
    </main>
    <dialog ref="pairDialog" class="pair-dialog" aria-labelledby="pair-title">
      <div class="dialog-heading"><h2 id="pair-title">添加安卓手机</h2><button aria-label="关闭" class="close-button" @click="pairDialog.close()">×</button></div>
      <template v-if="!pairing">
        <p>给这部手机起个容易识别的名字。</p>
        <form @submit.prevent="createPairing"><label for="device-name">设备备注</label><input id="device-name" v-model="pairName" maxlength="60" required autofocus /><p v-if="pairError" class="error" role="alert">{{ pairError }}</p><button class="primary" :disabled="pairingBusy">{{ pairingBusy ? '正在生成…' : '生成配对码' }}</button></form>
      </template>
      <template v-else>
        <p>在手机 App 中输入以下地址和配对码，然后允许接收短信。</p>
        <label>服务器地址</label><div class="copy-field"><code>{{ pairing.serverUrl }}</code><button @click="copy(pairing.serverUrl)">复制</button></div>
        <label>一次性配对码</label><div class="pair-code"><code>{{ pairing.code.match(/.{1,4}/g).join(' ') }}</code><button @click="copy(pairing.code)">复制</button></div>
        <p :class="{ error: pairExpired }">{{ pairExpired ? '配对码已过期，请重新生成。' : `有效至 ${new Date(pairing.expiresAt).toLocaleTimeString('zh-CN', { hour12: false })}，使用一次后失效。` }}</p>
        <p v-if="serverUrl.startsWith('http:')" class="error">当前是本机预览地址。安卓 App 需要可访问的 HTTPS 地址，部署后再进行配对。</p>
        <p v-if="pairError" class="error" role="alert">{{ pairError }}</p>
        <button v-if="pairExpired" class="primary" :disabled="pairingBusy" @click="createPairing">重新生成</button><button v-else class="primary" @click="pairDialog.close(); tab = 'devices'">查看设备列表</button>
      </template>
    </dialog>
  </div>
  <div v-if="toast" class="toast" role="status">{{ toast }}</div>
</template>
