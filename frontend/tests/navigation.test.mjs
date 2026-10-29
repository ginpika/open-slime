import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import ts from 'typescript'

function moduleUrl(relative, rewrite = source => source) {
  const source = readFileSync(new URL(relative, import.meta.url), 'utf8')
  const code = ts.transpileModule(source, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ES2022 } }).outputText
  return `data:text/javascript;base64,${Buffer.from(rewrite(code)).toString('base64')}`
}

const navUrl = moduleUrl('../src/shell/nav.ts')
const { NAV, KNOWLEDGE_NAV, ECONOMY_NAV, activePathOf, pageNavOf, breadcrumbsOf } = await import(navUrl)
const { routeFromHash, isKnownRoute } = await import(moduleUrl('../src/shell/routes.ts', source => source.replace("'./nav'", `'${navUrl}'`)))

test('economy has a workbench and separate database with shared navigation ownership', () => {
  assert.equal(NAV.find(i=>i.path==='/economy').group,'工作')
  assert.deepEqual(ECONOMY_NAV.map(i=>i.path),['/economy/research','/economy/database'])
  for(const route of ['/economy/market','/economy/stock']){
    assert.equal(routeFromHash(`#${route}`),'/economy/research')
    assert.equal(activePathOf(route),'/economy')
  }
  for(const item of ECONOMY_NAV) {
    assert.equal(isKnownRoute(item.path),true)
    assert.equal(activePathOf(item.path),'/economy')
    assert.equal(pageNavOf(item.path).label,item.label)
    assert.deepEqual(breadcrumbsOf(item.path),[{path:'/economy',label:'经济'},{path:item.path,label:item.label}])
  }
  assert.equal(isKnownRoute('/economy/unknown'),false)
})

test('knowledge pages keep direct links, individual titles and a single global destination', () => {
  const pages = [
    ['#/articles', '文档'],
    ['#/snapshots', '网页快照'],
    ['#/graph?entity=example', '知识图谱'],
    ['#/import', '文档导入'],
    ['#/analyze?doc=12', '文档分析'],
    ['#/article/a%20b', '文档'],
    ['#/snapshots/s1', '网页快照'],
  ]
  assert.equal(NAV.filter(item => item.path === '/knowledge').length, 1)
  assert.equal(isKnownRoute('/knowledge'), true)
  for (const [hash, label] of pages) {
    const route = routeFromHash(hash)
    assert.equal(isKnownRoute(route), true, hash)
    assert.equal(activePathOf(route), '/knowledge', hash)
    assert.equal(pageNavOf(route)?.label, label, hash)
    assert.equal(NAV.some(item => item.path === route), false, hash)
  }
})

test('knowledge grouping preserves unrelated navigation and rejects unknown routes', () => {
  for (const route of ['/', '/todos', '/multi-agent', '/memory', '/collaboration', '/coding', '/workspace', '/settings', '/stats']) {
    assert.equal(activePathOf(route), route)
    assert.equal(NAV.some(item => item.path === route), true)
    assert.equal(isKnownRoute(route), true)
  }
  assert.equal(activePathOf('/multi-agent/new'), '/multi-agent')
  for (const route of ['/roundtable', '/workflow']) {
    assert.equal(activePathOf(route), '/collaboration')
    assert.equal(isKnownRoute(route), true)
  }
  for (const route of ['/search', '/subconscious']) assert.equal(isKnownRoute(route), true)
  for (const route of ['/about', '/knowledge/missing', '/articles-missing', '/snapshots/a/b']) assert.equal(isKnownRoute(route), false)
})

test('breadcrumbs follow actual knowledge entry levels even on direct detail links', () => {
  const knowledge = { path: '/knowledge', label: '知识' }
  for (const item of KNOWLEDGE_NAV) {
    assert.deepEqual(breadcrumbsOf(item.path), [knowledge, { path: item.path, label: item.label }])
  }
  for (const [hash, parent, label] of [
    ['#/article/a%20b', '/articles', '文档详情'],
    ['#/article/https%3A%2F%2Fexample.com%2Fa', '/articles', '文档详情'],
    ['#/snapshots/s1', '/snapshots', '快照详情'],
  ]) {
    const route = routeFromHash(hash)
    assert.deepEqual(breadcrumbsOf(route), [...breadcrumbsOf(parent), { path: route, label }])
  }
})

test('breadcrumbs cover every global page, agent details and legacy direct entries', () => {
  const home = { path: '/', label: '首页' }
  assert.deepEqual(breadcrumbsOf('/'), [home])
  for (const item of NAV.filter(item => item.path !== '/')) {
    assert.deepEqual(breadcrumbsOf(item.path), [{ path: item.path, label: item.label }])
  }
  for (const [route, label] of [
    ['/multi-agent/new', '新建智能体'],
    ['/multi-agent/peer-1', '智能体详情'],
    ['/multi-agent/builtin-coding', '智能体详情'],
    ['/subconscious', '系统'],
  ]) {
    assert.deepEqual(breadcrumbsOf(route), [{ path: '/multi-agent', label: '智能体' }, { path: route, label }])
  }
  assert.deepEqual(breadcrumbsOf('/search'), [{ path: '/search', label: '检索' }])
})

test('each ancestor links to a real page and reconstructs the same hierarchy', () => {
  const routes = [...NAV, ...KNOWLEDGE_NAV].map(item => item.path)
  routes.push('/article/a%20b', '/snapshots/s1', '/multi-agent/new', '/multi-agent/peer-1', '/search', '/subconscious')
  for (const route of routes) {
    const crumbs = breadcrumbsOf(route)
    assert.equal(crumbs.at(-1).path, route)
    assert.equal(new Set(crumbs.map(item => item.path)).size, crumbs.length)
    crumbs.forEach((item, index) => {
      assert.equal(isKnownRoute(item.path), true, item.path)
      assert.deepEqual(breadcrumbsOf(item.path), crumbs.slice(0, index + 1))
    })
  }
  for (const route of ['/about', '/missing', '/article/', '/snapshots/a/b', '/multi-agent/']) {
    assert.deepEqual(breadcrumbsOf(route), [{ path: route, label: '页面不存在' }])
  }
})
