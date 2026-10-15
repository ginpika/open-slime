import { strict as assert } from 'node:assert'
import { test } from 'node:test'
import { mkdtempSync, readFileSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname, join, resolve, basename } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'
import ts from 'typescript'

const tempRoot = resolve(tmpdir())
const output = mkdtempSync(join(tempRoot, 'minis-url-tests-'))
if (dirname(resolve(output)) !== tempRoot || !basename(output).startsWith('minis-url-tests-')) throw new Error('临时测试目录校验失败')
const { writeFileSync } = await import('node:fs')
let readableUrl, navigationUrl
try {
  const source = readFileSync(fileURLToPath(new URL('../src/api/readableUrl.ts', import.meta.url)), 'utf8')
  writeFileSync(join(output, 'url.mjs'), ts.transpileModule(source, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ES2022 } }).outputText)
  ;({ readableUrl, navigationUrl } = await import(pathToFileURL(join(output, 'url.mjs')).href))
} finally { rmSync(output, { recursive: true, force: true }) }

test('Chinese and Japanese display readably and round-trip to the same URL', () => {
  const encoded = 'https://example.org/%E4%B8%AD%E6%96%87/%E6%97%A5%E6%9C%AC%E8%AA%9E?q=%E7%9F%A5%E8%AF%86'
  assert.equal(readableUrl(encoded), 'https://example.org/中文/日本語?q=知识')
  assert.equal(navigationUrl(readableUrl(encoded)), encoded)
})
test('reserved delimiters and double encoding retain their meaning', () => {
  const encoded = 'https://example.org/%E4%B8%AD%2F%E6%96%87?q=%E6%97%A5%26%E6%9C%AC&next=%252F%23%3F%25%20'
  assert.equal(readableUrl(encoded), 'https://example.org/中%2F文?q=日%26本&next=%252F%23%3F%25%20')
  assert.equal(navigationUrl(readableUrl(encoded)), encoded)
})
test('malformed UTF-8 and invisible direction controls stay encoded', () => {
  const encoded = 'https://example.org/%E4%B8/%ED%A0%80/%C0%AF/%E2%80%AE/%C2%85/%xx'
  assert.equal(readableUrl(encoded), encoded)
})
test('Unicode supplementary characters display without corrupting the URL', () => {
  assert.equal(readableUrl('https://example.org/%F0%A0%AE%B7/%F0%9F%8C%90'), 'https://example.org/𠮷/🌐')
})
