import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import ts from 'typescript'

const code = ts.transpileModule(readFileSync(new URL('../src/components/taggedText.ts', import.meta.url), 'utf8'), {
  compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ES2022 },
}).outputText
const { parseTaggedText } = await import('data:text/javascript;base64,' + Buffer.from(code).toString('base64'))
const restore = parts => parts.map(p => p.type === 'text' ? p.text : p.opening + restore(p.children) + p.closing).join('')

test('nested, repeated and adjacent prompt tags preserve exact text and boundaries', () => {
  const text = '前言\n<minis-memory>\n<item>甲</item><item>乙<item>内层</item></item>\n</minis-memory>尾声'
  const parts = parseTaggedText(text)
  assert.equal(restore(parts), text)
  assert.equal(parts[1].name, 'minis-memory')
  assert.equal(parts[1].children[2].children[1].name, 'item')
  assert.equal(parts[2].text, '尾声')
})

test('attributes, namespace names, quoted brackets and literal HTML stay text', () => {
  const text = '<ns:section a=">" b=\'x>y\'><script>alert(1)</script><!-- <fake> --><br/></ns:section>'
  const parts = parseTaggedText(text)
  assert.equal(restore(parts), text)
  assert.equal(parts[0].opening, '<ns:section a=">" b=\'x>y\'>')
  assert.equal(parts[0].children[0].name, 'script')
  assert.equal(parts[0].children[1].text, '<!-- <fake> --><br/>')
})

test('unmatched, crossed, empty and ordinary strings never lose characters', () => {
  for (const text of ['', '普通文本\n😀', '<something>正文</somgthing>', '<a><b>正文</a></b>', '<a>未闭合', 'x < y > z', '<one/><two />', '<empty></empty>', '<outer><inner>有效</inner>未闭合']) {
    assert.equal(restore(parseTaggedText(text)), text)
  }
  assert.equal(parseTaggedText('<something>正文</somgthing>')[0].type, 'text')
  assert.equal(parseTaggedText('<a><b>正文</a></b>')[0].type, 'text')
})
