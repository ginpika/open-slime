import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import ts from 'typescript'

const code=ts.transpileModule(readFileSync(new URL('../src/pages/economy/strategy.ts',import.meta.url),'utf8'),{compilerOptions:{module:ts.ModuleKind.ES2022,target:ts.ScriptTarget.ES2022}}).outputText
const {overnightReturn}=await import(`data:text/javascript;base64,${Buffer.from(code).toString('base64')}`)
test('overnight long trade charges both entry and exit and has a verifiable break-even price',()=>{
  assert.ok(Math.abs(overnightReturn(100,110,10000,0,0).net-.1)<1e-12)
  const flat=overnightReturn(100,100,10000,3,5)
  assert.ok(flat.net<0)
  assert.ok(Math.abs(flat.net-((1-.0008)/(1+.0008)-1))<1e-12)
  const even=overnightReturn(100,flat.breakEven,10000,3,5)
  assert.ok(Math.abs(even.net)<1e-12)
  assert.equal(flat.profit,flat.net*10000)
})
test('overnight calculations reject absent prices and nonfinite or impossible costs',()=>{
  for(const args of [[0,100,10000,3,5],[100,-1,10000,3,5],[100,100,0,3,5],[100,100,10000,-1,5],[100,100,10000,5000,5000],[NaN,100,10000,3,5],[100,Infinity,10000,3,5]])assert.equal(overnightReturn(...args),null)
})
