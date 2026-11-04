import { createBrowserController } from './browser.js'
const execute = createBrowserController(chrome)
let socket = null
let reconnect = null
let heartbeat = null
let wanted = null

function validate(raw) {
  if (!/^ws:\/\/127\.0\.0\.1:\d{1,5}\/ws\/computer\/chrome\/[A-Za-z0-9_-]{43}$/.test(raw)) throw new Error('请粘贴桌面端本机模式生成的完整连接地址')
  const port = new URL(raw).port
  if (Number(port) < 1 || Number(port) > 65535) throw new Error('连接端口无效')
  return raw
}
async function connect(url) {
  wanted = validate(url)
  clearTimeout(reconnect)
  clearInterval(heartbeat)
  if (socket) { socket.onclose = null; socket.close() }
  const ws = new WebSocket(wanted)
  socket = ws
  ws.onopen = () => {
    chrome.action.setBadgeText({ text: 'ON' })
    heartbeat = setInterval(() => { if (ws.readyState === WebSocket.OPEN) ws.send('{"type":"ping"}') }, 20000)
  }
  ws.onmessage = async event => {
    let request
    try {
      request = JSON.parse(event.data)
      if (!/^[0-9a-f-]{36}$/.test(request.id) || !request.command) return
      const result = await execute(request.scope, request.command, request.deadline)
      if (ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify({ id: request.id, result }))
    } catch (error) {
      if (request?.id && ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify({ id: request.id, error: error.message || String(error) }))
    }
  }
  ws.onclose = () => {
    clearInterval(heartbeat)
    chrome.action.setBadgeText({ text: '' })
    if (wanted === url) reconnect = setTimeout(() => connect(url), 3000)
  }
  ws.onerror = () => ws.close()
}
chrome.runtime.onMessage.addListener((message, sender, respond) => {
  if (sender.id !== chrome.runtime.id) return false
  ;(async () => {
    if (message.type === 'connect') {
      const url = validate(message.url)
      await chrome.storage.session.set({ connectionUrl: url })
      await connect(url)
    } else if (message.type === 'disconnect') {
      wanted = null; clearTimeout(reconnect); clearInterval(heartbeat)
      if (socket) socket.close()
      await chrome.storage.session.remove('connectionUrl')
    }
    return { connected: socket?.readyState === WebSocket.OPEN }
  })().then(respond, error => respond({ error: error.message }))
  return true
})
chrome.storage.session.get('connectionUrl').then(({ connectionUrl }) => { if (connectionUrl) connect(connectionUrl).catch(() => {}) })
// Opening a tool tab is an explicit user takeover. It permanently leaves the tool's ownership list.
chrome.tabs.onActivated.addListener(async ({ tabId }) => {
  await execute.release(tabId)
})
