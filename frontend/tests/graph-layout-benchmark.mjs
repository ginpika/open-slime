import { readFileSync } from 'node:fs'
import ts from 'typescript'

const code = ts.transpileModule(readFileSync(new URL('../src/components/graphLayout.ts', import.meta.url), 'utf8'), {
  compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ES2022 },
}).outputText
const { forceLayout } = await import('data:text/javascript;base64,' + Buffer.from(code).toString('base64'))
for (const count of [100, 300, 1000]) {
  const nodes = Array.from({ length: count }, (_, i) => ({ id: String(i) }))
  const edges = nodes.slice(1).map(n => ({ from: '0', to: n.id }))
  const started = performance.now()
  forceLayout(nodes, edges)
  console.log(JSON.stringify({ nodes: count, iterations: 300, milliseconds: Math.round(performance.now() - started) }))
}
