const status = document.querySelector('#status')
async function send(message) {
  const result = await chrome.runtime.sendMessage(message)
  status.textContent = result.error || (result.connected ? '已连接' : '未连接；连接后扩展图标会显示 ON')
}
document.querySelector('#connect').addEventListener('click', () => send({ type: 'connect', url: document.querySelector('#url').value.trim() }).catch(error => { status.textContent = error.message }))
document.querySelector('#disconnect').addEventListener('click', () => send({ type: 'disconnect' }))
send({ type: 'status' })
