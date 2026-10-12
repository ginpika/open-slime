import { execFileSync } from 'node:child_process'
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { basename, dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
const frontend = fileURLToPath(new URL('..', import.meta.url))
const root = resolve(tmpdir()), output = mkdtempSync(join(root, 'minis-memory-tests-'))
if (dirname(resolve(output)) !== root || !basename(output).startsWith('minis-memory-tests-')) throw new Error('临时测试目录校验失败')
try {
  execFileSync(process.execPath, [join(frontend, 'node_modules/typescript/bin/tsc'), '--ignoreConfig', '--outDir', output, '--rootDir', 'src', '--module', 'commonjs', '--moduleResolution', 'node', '--jsx', 'react-jsx', '--target', 'ES2022', '--lib', 'ES2022,DOM', '--skipLibCheck', '--ignoreDeprecations', '6.0', 'src/pages/memory/model.ts', 'src/components/formKit.tsx', 'src/components/ui.tsx'], { cwd: frontend, stdio: 'inherit' })
  writeFileSync(join(output, 'package.json'), JSON.stringify({ type: 'commonjs' }))
  execFileSync(process.execPath, ['--test', join(frontend, 'tests/memory-model.test.cjs'), join(frontend, 'tests/memory-controls.test.cjs')], { cwd: frontend, stdio: 'inherit', env: { ...process.env, MEMORY_MODEL_BUILD: join(output, 'pages/memory/model.js'), MEMORY_BUILD_ROOT: output, NODE_PATH: join(frontend, 'node_modules') } })
} finally {
  rmSync(output, { recursive: true, force: true })
}
