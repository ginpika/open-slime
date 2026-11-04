const ACTIONS = new Set(['list', 'open', 'read', 'click', 'fill', 'select', 'close'])
const MUTATIONS = new Set(['open', 'click', 'fill', 'select', 'close'])

export function httpUrl(raw) {
  const url = new URL(raw)
  if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password) throw new Error('仅支持不含用户名密码的 HTTP / HTTPS 地址')
  return url.href
}

// Executed in Chrome's isolated world. No cookies, password values, eval, OS input or browser focus APIs.
export function pageOperation(command) {
  const action = command.action
  if (['click', 'fill', 'select'].includes(action)) {
    const ref = String(command.ref || '')
    if (!/^r[a-f0-9]{32}-\d+$/.test(ref)) throw new Error('ref 无效，请先 read')
    const element = document.querySelector(`[data-slime-computer-ref="${ref}"]`)
    if (!element || !element.getClientRects().length || getComputedStyle(element).visibility === 'hidden' || element.disabled) throw new Error('元素已失效或不可操作，请重新 read')
    // Consume refs before acting. A partial failure must never leave old references executable.
    document.querySelectorAll('[data-slime-computer-ref]').forEach(e => e.removeAttribute('data-slime-computer-ref'))
    if (action === 'click') {
      if (element.tagName === 'A' || element.tagName === 'FORM') element.target = '_self'
      if (element.form) element.form.target = '_self'
      element.click()
    } else if (action === 'fill') {
      if (element.type === 'password') throw new Error('请由用户在 Chrome 中填写登录凭证，工具不接管密码')
      if (!['INPUT', 'TEXTAREA'].includes(element.tagName) || typeof command.text !== 'string' || command.text.length > 10000) throw new Error('fill 需要可输入元素和不超过 10000 字符的 text')
      const proto = element.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype
      Object.getOwnPropertyDescriptor(proto, 'value').set.call(element, command.text)
      element.dispatchEvent(new Event('input', { bubbles: true }))
      element.dispatchEvent(new Event('change', { bubbles: true }))
    } else {
      if (element.tagName !== 'SELECT' || ![...element.options].some(o => o.value === command.value)) throw new Error('select 需要有效的选项 value')
      element.value = command.value
      element.dispatchEvent(new Event('input', { bubbles: true }))
      element.dispatchEvent(new Event('change', { bubbles: true }))
    }
    return { acted: true }
  }
  const prefix = `r${[...crypto.getRandomValues(new Uint8Array(16))].map(byte => byte.toString(16).padStart(2, '0')).join('')}-`
  document.querySelectorAll('[data-slime-computer-ref]').forEach(e => e.removeAttribute('data-slime-computer-ref'))
  const elements = [...document.querySelectorAll('a,button,input,textarea,select,[role=button]')]
    .filter(e => e.getClientRects().length && getComputedStyle(e).visibility !== 'hidden')
    .slice(0, 120).map((e, i) => {
      const ref = prefix + i
      e.setAttribute('data-slime-computer-ref', ref)
      return { ref, tag: e.tagName.toLowerCase(), label: (e.getAttribute('aria-label') || e.innerText || e.placeholder || e.name || e.type || '').slice(0, 160), href: e.href || '', ...(e.tagName === 'SELECT' ? { options: [...e.options].slice(0, 40).map(o => ({ value: o.value, label: o.label })) } : {}) }
    })
  const body = document.body?.innerText || ''
  return { url: location.href, title: document.title, text: body.slice(0, 12000), total_chars: body.length, elements }
}

export function createBrowserController(api, pause = ms => new Promise(resolve => setTimeout(resolve, ms))) {
  let serial = Promise.resolve()
  async function load() { return (await api.storage.session.get('ownedTabs')).ownedTabs || {} }
  async function save(tabs) { await api.storage.session.set({ ownedTabs: tabs }) }
  async function ready(id) {
    for (let i = 0; i < 100; i++) {
      const tab = await api.tabs.get(id)
      if (tab.status === 'complete') return tab
      await pause(200)
    }
    throw new Error('页面加载超时，操作结果未知；请先 read / list 重新观察')
  }
  async function inspect(id) {
    const result = await api.scripting.executeScript({ target: { tabId: id }, func: pageOperation, args: [{ action: 'read' }] })
    if (!result[0]?.result) throw new Error('无法读取页面，请检查扩展对此网站的访问权限')
    return { environment: 'user', tab_id: `user:${id}`, ...result[0].result }
  }
  async function execute(scope, command) {
    if (!/^[a-f0-9]{64}$/.test(scope) || !ACTIONS.has(command.action)) throw new Error('无效的 Chrome 命令')
    const owned = await load()
    const ids = owned[scope] || []
    const tabs = []
    for (const id of ids) { try { tabs.push(await api.tabs.get(id)) } catch { /* Closed by user. */ } }
    owned[scope] = tabs.map(t => t.id)
    await save(owned)
    if (command.action === 'list') return { environment: 'user', tabs: tabs.map(t => ({ tab_id: `user:${t.id}`, title: t.title || '', url: t.url || '' })) }
    let id
    if (command.tab_id) {
      if (!/^user:\d+$/.test(command.tab_id)) throw new Error('tab_id 无效，请先 list')
      id = Number(command.tab_id.slice(5))
      if (!owned[scope].includes(id)) throw new Error('不能操作用户原有标签页或其他会话的标签页')
      const tab = await api.tabs.get(id)
      if (tab.active && MUTATIONS.has(command.action)) throw new Error('该工具标签页正在前台使用，已停止修改；请先切换到其他标签页')
    } else if (command.action !== 'open') throw new Error('请传入本会话的 tab_id')
    if (command.action === 'open') {
      const url = httpUrl(command.url)
      if (id == null) {
        if (Object.values(owned).flat().length >= 64 || owned[scope].length >= 16) throw new Error('工具标签页已满，请先 close')
        const windows = (await api.windows.getAll({ windowTypes: ['normal'] })).filter(window => !window.incognito)
        const window = windows.find(window => window.focused) || windows[0]
        if (!window) throw new Error('请先打开一个普通 Chrome 窗口；工具不会自行创建或激活窗口')
        const tab = await api.tabs.create({ url, active: false, windowId: window.id })
        id = tab.id
        owned[scope].push(id)
        await save(owned)
      } else await api.tabs.update(id, { url })
      await ready(id)
    } else if (command.action === 'close') {
      await api.tabs.remove(id)
      owned[scope] = owned[scope].filter(value => value !== id)
      await save(owned)
      return { closed: true, tab_id: `user:${id}`, environment: 'user' }
    } else if (['click', 'fill', 'select'].includes(command.action)) {
      await api.scripting.executeScript({ target: { tabId: id }, func: pageOperation, args: [command] })
      await pause(250)
      await ready(id)
    }
    return inspect(id)
  }
  // Serialize commands across tabs so observations and mutations cannot race inside the extension.
  const run = (scope, command, deadline) => {
    const operation = serial.then(() => {
      if (deadline != null && Date.now() > deadline) throw new Error('命令已过期，未执行，请重新观察')
      return execute(scope, command)
    })
    serial = operation.catch(() => {})
    return operation
  }
  run.release = tabId => {
    const operation = serial.then(async () => {
      const owned = await load()
      for (const scope of Object.keys(owned)) owned[scope] = owned[scope].filter(id => id !== tabId)
      await save(owned)
    })
    serial = operation.catch(() => {})
    return operation
  }
  return run
}
