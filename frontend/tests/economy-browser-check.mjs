import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import path from 'node:path'
import {createRequire} from 'node:module'
import {fileURLToPath} from 'node:url'

const frontend=fileURLToPath(new URL('../',import.meta.url))
const require=createRequire(path.join(process.env.MINIS_PLAYWRIGHT_DIR||frontend,'package.json'))
const {chromium}=require('playwright')
const dist=path.join(frontend,'dist'),output=path.resolve(frontend,'../backend/target/economy-browser-check')
await fs.mkdir(output,{recursive:true})
const browser=await chromium.launch({channel:'chrome',headless:true})
const context=await browser.newContext({viewport:{width:1680,height:1100}})
await context.addInitScript(()=>localStorage.setItem('minis.theme.v4',JSON.stringify({v:4,themeId:'old',schemeMode:'light',override:{seed:{}},mono:{sidebar:true,hideChrome:false}})))
const page=await context.newPage();page.setDefaultTimeout(10000)
const errors=[],commands=[],stepRequests=[],summaryRequests=[],retryRequests=[],listRequests=[],imageRequests=[],studies=new Map()
page.on('pageerror',e=>errors.push(e.message))
const jpeg=await page.evaluate(()=>{
  const c=document.createElement('canvas');c.width=1280;c.height=800;const x=c.getContext('2d');x.fillStyle='#fbfcfe';x.fillRect(0,0,1280,800)
  x.fillStyle='#263348';x.font='24px sans-serif';x.fillText('THREE PERIODS · TEST · Fixture',35,45)
  for(let i=0;i<65;i++){const y=430-Math.sin(i*.2)*75-i*2;x.strokeStyle=i%3?'#128467':'#d04d57';x.beginPath();x.moveTo(i*17+60,y-35);x.lineTo(i*17+60,y+35);x.stroke();x.fillStyle=x.strokeStyle;x.fillRect(i*17+55,y-12,10,28);x.fillRect(i*17+55,720-i%5*14,10,i%5*14+10)}
  return c.toDataURL('image/jpeg',.8).split(',')[1]
})
const view={browserId:'chart1',chatSessionId:null,title:'Three-period fixture',url:'https://www.tradingview.com/chart/?symbol=TEST&interval=W',text:'TEST',totalChars:4,offset:0,elements:[],blocked:false,screenshot:jpeg}
let sequence=0,dailyFails=true,listFails=false,detailFails=false,summaryFails=false,heldAction=null
const waitForHeldAction=async()=>{const pending=heldAction;heldAction=null;if(pending)await pending}
const analysis=timeframe=>({sufficientEvidence:true,summary:`${timeframe} 分析完成`,technical:'截图周期和日期已核对；仅是夹具。',risks:'隔夜风险待核对'})
const findObservation=id=>[...studies.values()].flatMap(s=>s.observations).find(o=>o.id===id)
for(let i=0;i<25;i++){
  const study={id:`seed${i}`,instrumentId:'old',kind:'market',symbol:'OLD',tradeDate:'2026-10-18',createdAt:Date.now()-i*1000}
  studies.set(study.id,{study,observations:[],reports:[]})
}
studies.get('seed0').observations.push({id:'seed-weekly',studyId:'seed0',timeframe:'weekly',capturedAt:Date.now(),createdAt:Date.now(),sourceTitle:'saved old fixture',sourceUrl:view.url,imageHash:'seed-hash',agentId:'a',agentName:'分析员甲',model:'vision-test',notes:'保留的观测关注点',status:'COMPLETE',analysis:analysis('weekly'),error:null})
await context.route('**/*',async route=>{
  const req=route.request(),url=new URL(req.url()),method=req.method()
  if(url.origin!=='http://economy.test')return route.abort()
  if(!url.pathname.startsWith('/api/')){
    const file=path.resolve(dist,url.pathname==='/'?'index.html':'.'+url.pathname)
    if(!file.startsWith(dist+path.sep))return route.abort()
    try{return await route.fulfill({path:file})}catch{return route.fulfill({status:404,body:''})}
  }
  const json=value=>route.fulfill({json:value})
  if(url.pathname==='/api/auth/me')return json({id:'fixture',username:'fixture',nickname:'验收用户'})
  if(url.pathname==='/api/agents/peers')return json([{id:'a',name:'分析员甲',builtin:true,sort:0},{id:'b',name:'分析员乙',sort:1},...Array.from({length:30},(_,i)=>({id:`extra-${i}`,name:`观察员 ${i+1}`,sort:i+2})),{id:'builtin-coding',executionKind:'CODING',name:'编程Agent',sort:99}])
  if(url.pathname==='/api/economy/browser'){const cmd=req.postDataJSON();commands.push(cmd);await new Promise(r=>setTimeout(r,20));return json(cmd.action==='close'?null:view)}
  if(url.pathname==='/api/economy/studies'){
    if(method==='POST'){
      const input=req.postDataJSON(),study={...input,id:`s${++sequence}`,instrumentId:`i-${input.kind}-${input.symbol}`,createdAt:Date.now()}
      studies.set(study.id,{study,observations:[],reports:[]});return json(study)
    }
    listRequests.push(url.search)
    if(listFails){listFails=false;return route.fulfill({status:503,json:{message:'观测列表暂不可用'}})}
    const kind=url.searchParams.get('kind'),symbol=url.searchParams.get('symbol'),from=url.searchParams.get('from'),to=url.searchParams.get('to'),p=Number(url.searchParams.get('page')||1)
    const values=[...studies.values()].reverse().filter(s=>(!kind||s.study.kind===kind)&&(!symbol||s.study.symbol.includes(symbol))&&(!from||s.study.tradeDate>=from)&&(!to||s.study.tradeDate<=to))
    return json({items:values.slice((p-1)*20,p*20).map(s=>({study:s.study,completedSteps:new Set(s.observations.filter(o=>o.status==='COMPLETE').map(o=>o.timeframe)).size,observationCount:s.observations.length,reportCount:s.reports.length,latestSummary:s.reports[0]?.summary??null,latestScore:s.reports[0]?.score??null})),total:values.length,page:p,pageSize:20})
  }
  let match=url.pathname.match(/^\/api\/economy\/studies\/([^/]+)(?:\/(observations|submit))?$/)
  if(match){
    const detail=studies.get(match[1]);assert.ok(detail)
    if(!match[2]){if(detailFails){detailFails=false;return route.fulfill({status:503,json:{message:'观测详情暂不可用'}})}return json(detail)}
    const input=req.postDataJSON()
    await waitForHeldAction()
    if(match[2]==='observations'){
      stepRequests.push(input);const failed=input.timeframe==='daily'&&dailyFails;if(failed)dailyFails=false
      const o={id:`o${++sequence}`,studyId:detail.study.id,timeframe:input.timeframe,capturedAt:Date.now(),createdAt:Date.now(),sourceTitle:'saved fixture',sourceUrl:view.url,imageHash:`hash${sequence}`,agentId:input.agentId,agentName:input.agentId==='b'?'分析员乙':'分析员甲',model:'vision-test',notes:input.notes,status:failed?'FAILED':'COMPLETE',analysis:failed?null:analysis(input.timeframe),error:failed?'fixture unavailable':null}
      detail.observations.unshift(o);return json(o)
    }
    if(summaryFails){summaryFails=false;return route.fulfill({status:503,json:{message:'总结服务暂不可用'}})}
    summaryRequests.push(input)
    const r={id:`r${++sequence}`,studyId:detail.study.id,instrumentId:detail.study.instrumentId,kind:detail.study.kind,symbol:detail.study.symbol,tradeDate:detail.study.tradeDate,strategy:'close-open-v1',observationIds:input.observationIds,agentId:input.agentId,agentName:'分析员乙',model:'vision-test',analyzedAt:Date.now(),sufficientEvidence:true,entryDecision:'ENTER',overnightDecision:'BUY_CLOSE',score:80,stage:5,summary:`三图总结版本 ${detail.reports.length+1}`,factors:[{name:'周线环境',points:20,maximum:25,reason:'环境'},{name:'日线结构',points:25,maximum:30,reason:'结构'},{name:'尾盘分时',points:25,maximum:30,reason:'尾盘'},{name:'隔夜执行风险',points:10,maximum:15,reason:'执行'}],technical:'三周期相互支持，保留限制。',fundamentals:'基本面资料不足。',conditions:'需确认尾盘承接。',exitPlan:'下一交易日开盘后立即卖出，不延长持仓。',risks:'隔夜跳空和事件。',retrievalStatus:'未启用网页检索'}
    detail.reports.unshift(r);return json(r)
  }
  match=url.pathname.match(/^\/api\/economy\/observations\/([^/]+)\/(image|retry)$/)
  if(match){
    const o=findObservation(match[1]);assert.ok(o)
    if(match[2]==='image'){imageRequests.push(o.id);return json({image:`data:image/jpeg;base64,${jpeg}`})}
    await waitForHeldAction();retryRequests.push(o.id);o.status='COMPLETE';o.error=null;o.analysis=analysis(o.timeframe);return json(o)
  }
  if(url.pathname==='/api/economy/reports')return json([{id:'old',symbol:'旧标的',kind:'stock',analyzedAt:Date.now(),summary:'旧版单图结论',technical:'旧技术分析',score:50}])
  return json([])
})
let checks=0
const clickWithLoading=async(name,screenshot)=>{
  let release;heldAction=new Promise(resolve=>{release=resolve})
  const button=page.getByRole('button',{name,exact:true}),sourceText=await page.locator('.eco-source-status').innerText()
  try{
    await button.click();await button.locator('svg.animate-spin').waitFor()
    assert.notEqual(await button.locator('svg.animate-spin').evaluate(e=>getComputedStyle(e).animationName),'none')
    assert.equal(await button.getAttribute('aria-busy'),'true');assert.equal(await button.isDisabled(),true)
    assert.equal(await page.locator('.eco-research button[aria-busy="true"]').count(),1)
    assert.equal(await page.locator('.eco-source-status').innerText(),sourceText)
    await button.click({force:true});assert.equal(await page.locator('.eco-research button[aria-busy="true"]').count(),1)
    if(screenshot)await page.screenshot({path:path.join(output,screenshot),fullPage:true})
    checks++
  }finally{release()}
}
const chooseOption=async(label,name)=>{
  await page.getByRole('combobox',{name:label,exact:true}).click()
  await page.getByRole('listbox',{name:label,exact:true}).getByRole('option',{name,exact:true}).click()
  await page.getByRole('listbox',{name:label,exact:true}).waitFor({state:'hidden'})
}
const auditScrollbars=async()=>{
  const failures=await page.evaluate(()=>[...document.querySelectorAll('.eco-page,.eco-page *,[role="listbox"]')].filter(e=>e.checkVisibility()).filter(e=>{
    const s=getComputedStyle(e);return [s.overflowY,s.overflowX].some(v=>v==='auto'||v==='scroll')&&s.scrollbarWidth!=='thin'
  }).map(e=>({tag:e.tagName,className:e.className})))
  assert.deepEqual(failures,[]);assert.equal(await page.locator('.eco-page select').count(),0);checks++
}
const auditContrast=async()=>{
  const failures=await page.evaluate(()=>{
    const canvas=document.createElement('canvas');canvas.width=canvas.height=1;const ctx=canvas.getContext('2d',{willReadFrequently:true})
    const lum=rgb=>rgb.slice(0,3).map(v=>v/255).map(v=>v<=.04045?v/12.92:((v+.055)/1.055)**2.4).reduce((sum,v,i)=>sum+v*[.2126,.7152,.0722][i],0)
    return [...document.querySelectorAll('.eco-help,.eco-meta,.eco-badge,.eco-source-status,.eco-history p,.eco-record-detail h3,.eco-primary:not(:disabled),.eco-decisions dt,.eco-factor strong')].filter(el=>el.checkVisibility()).flatMap(el=>{
      ctx.fillStyle='#fff';ctx.fillRect(0,0,1,1)
      const ancestors=[];for(let node=el;node;node=node.parentElement)ancestors.unshift(node)
      for(const node of ancestors){ctx.fillStyle=getComputedStyle(node).backgroundColor;ctx.fillRect(0,0,1,1)}
      const background=lum([...ctx.getImageData(0,0,1,1).data]);ctx.fillStyle=getComputedStyle(el).color;ctx.fillRect(0,0,1,1)
      const foreground=lum([...ctx.getImageData(0,0,1,1).data]),contrast=(Math.max(foreground,background)+.05)/(Math.min(foreground,background)+.05)
      return contrast<4.5?[{text:el.textContent.slice(0,60),contrast}]:[]
    })
  });assert.deepEqual(failures,[]);checks++
}
const leaveResearch=async()=>{
  await page.evaluate(()=>location.hash='/economy')
  await page.getByRole('heading',{name:'三张图，一份隔夜策略报告'}).waitFor()
}
const returnResearch=async()=>{
  await page.evaluate(()=>location.hash='/economy/research')
  await page.getByRole('heading',{name:'隔夜策略工作台',exact:true}).waitFor()
  await page.waitForFunction(()=>document.querySelector('[role="tab"][aria-selected="true"]')?.textContent==='已打开窗口')
}
try{
  await page.goto('http://economy.test/#/economy')
  await page.getByRole('heading',{name:'三张图，一份隔夜策略报告'}).waitFor();checks++
  assert.equal(await page.getByRole('link',{name:/观测数据库/}).count(),1);await page.screenshot({path:path.join(output,'economy-start.png'),fullPage:true});checks++
  await page.getByRole('link',{name:/隔夜策略工作台/}).click()
  await page.getByRole('heading',{name:'隔夜策略工作台',exact:true}).waitFor()
  assert.equal(await page.getByRole('button',{name:'日线分析',exact:true}).isDisabled(),true)
  assert.equal(await page.getByRole('button',{name:'提交三图总结'}).isDisabled(),true);checks++
  const kindPicker=page.getByRole('combobox',{name:'标的类型',exact:true})
  await kindPicker.focus();await page.keyboard.press('Enter');await page.keyboard.press('End');await page.keyboard.press('Enter')
  assert.equal(await kindPicker.textContent(),'个股');assert.equal(await kindPicker.evaluate(e=>e===document.activeElement),true);checks++
  await page.getByLabel('标的名称 / 代码').fill('TEST')
  const agentPicker=page.getByRole('combobox',{name:'分析 Agent',exact:true})
  await agentPicker.scrollIntoViewIfNeeded();const beforeMenu=await page.locator('.eco-analysis').evaluate(e=>e.scrollTop)
  await agentPicker.click();const agentList=page.getByRole('listbox',{name:'分析 Agent',exact:true})
  assert.equal(await agentList.getByRole('option',{name:'编程Agent',exact:true}).count(),0)
  assert.ok(await agentList.evaluate(e=>e.scrollHeight>e.clientHeight));await auditScrollbars()
  await agentList.hover();await page.mouse.wheel(0,400);await page.waitForFunction(()=>document.querySelector('[role="listbox"]')?.scrollTop>0)
  assert.equal(await page.locator('.eco-analysis').evaluate(e=>e.scrollTop),beforeMenu);checks++
  await page.screenshot({path:path.join(output,'tailwind-agent-menu.png'),fullPage:true})
  await page.getByRole('combobox',{name:'搜索分析 Agent',exact:true}).fill('没有这个分析员');await page.getByText('没有匹配的选项',{exact:true}).waitFor();checks++
  await page.getByRole('combobox',{name:'搜索分析 Agent',exact:true}).fill('分析员乙');await page.keyboard.press('Enter')
  assert.equal(await agentPicker.textContent(),'分析员乙');assert.equal(await page.locator('.eco-analysis').evaluate(e=>e.scrollTop),beforeMenu);checks++
  await agentPicker.click();await page.keyboard.press('Escape');assert.equal(await agentPicker.getAttribute('aria-expanded'),'false');assert.equal(await agentPicker.evaluate(e=>e===document.activeElement),true);checks++
  await page.getByRole('button',{name:'打开图表'}).click();const screen=page.getByRole('application');await screen.waitFor();const rect=await screen.boundingBox()
  await page.mouse.click(rect.x+rect.width*.5,rect.y+rect.height*.5);await page.waitForTimeout(150)
  assert.ok(commands.some(c=>c.action==='click'&&Math.abs(c.x-640)<5&&Math.abs(c.y-400)<5));checks++
  const beforeDouble=commands.filter(c=>c.action==='click').length
  await page.mouse.dblclick(rect.x+rect.width*.6,rect.y+rect.height*.5);await page.waitForTimeout(150)
  assert.equal(commands.filter(c=>c.action==='click').slice(beforeDouble)[1].text,'double');checks++
  await page.mouse.move(rect.x+rect.width*.2,rect.y+rect.height*.3);await page.mouse.down();await page.mouse.move(rect.x+rect.width*.4,rect.y+rect.height*.5,{steps:6});await page.mouse.up();await page.waitForTimeout(150)
  assert.ok(commands.some(c=>c.action==='drag'));checks++
  await page.mouse.move(rect.x+rect.width*.5,rect.y+rect.height*.5);await page.mouse.wheel(0,200);await page.waitForTimeout(250)
  assert.ok(commands.some(c=>c.action==='scroll'&&c.deltaY>0));checks++
  await page.getByText('网页文字输入',{exact:true}).click();await page.getByRole('textbox',{name:'输入到图表'}).fill('日线');await page.getByRole('button',{name:'输入文字',exact:true}).click();await page.waitForTimeout(100)
  assert.ok(commands.some(c=>c.action==='type'&&c.text==='日线'));checks++
  await clickWithLoading('周线分析','button-loading-weekly.png');await page.getByText('weekly 分析完成',{exact:true}).waitFor()
  assert.equal(await page.locator('.eco-research button[aria-busy="true"]').count(),0);checks++
  assert.equal(stepRequests[0].timeframe,'weekly');assert.equal(stepRequests[0].agentId,'b');assert.equal(stepRequests[0].browserId,'chart1');checks++
  assert.equal(await page.getByLabel('标的类型',{exact:true}).isDisabled(),true)
  await clickWithLoading('日线分析');await page.getByRole('button',{name:'重试已保存日线截图'}).waitFor()
  assert.equal(await page.locator('.eco-research button[aria-busy="true"]').count(),0);checks++
  assert.equal(await page.getByRole('button',{name:'分时分析',exact:true}).isDisabled(),true);checks++
  const storedDaily=[...studies.values()].flatMap(s=>s.observations).find(o=>o.timeframe==='daily'),capturedAt=storedDaily.capturedAt
  await clickWithLoading('重试已保存日线截图');await page.getByText('daily 分析完成',{exact:true}).waitFor()
  assert.deepEqual(retryRequests,[storedDaily.id]);assert.equal(storedDaily.capturedAt,capturedAt);assert.equal(stepRequests.length,2);checks++
  await clickWithLoading('分时分析');await page.getByText('intraday 分析完成',{exact:true}).waitFor()
  assert.deepEqual(stepRequests.map(r=>r.timeframe),['weekly','daily','intraday']);checks++
  summaryFails=true;await clickWithLoading('提交三图总结');await page.getByRole('alert').filter({hasText:'总结服务暂不可用'}).waitFor()
  assert.equal(await page.getByRole('button',{name:'提交三图总结'}).isEnabled(),true);assert.equal(await page.locator('.eco-research button[aria-busy="true"]').count(),0);checks++
  await clickWithLoading('提交三图总结','button-loading-summary.png');await page.getByRole('article',{name:'三图总结报告'}).waitFor()
  await page.getByRole('list',{name:'观测进度',exact:true}).getByRole('button',{name:/总结/}).click()
  assert.equal(await page.evaluate(()=>document.activeElement.id),'eco-summary');checks++
  const submittedIds=[...summaryRequests[0].observationIds]
  assert.deepEqual(submittedIds.map(id=>findObservation(id).timeframe),['weekly','daily','intraday']);checks++
  await page.getByRole('button',{name:'查看周线证据'}).click();await page.getByRole('img',{name:'已保存周线截图'}).waitFor();checks++
  await page.getByRole('button',{name:'重新周线分析'}).click();await page.waitForFunction(()=>document.querySelector('.eco-help')!==null);await page.getByText('有步骤已更新；下方总结仍引用旧版本，可重新提交生成新总结。',{exact:true}).waitFor()
  assert.deepEqual(summaryRequests[0].observationIds,submittedIds);checks++
  await page.getByRole('button',{name:'提交三图总结'}).click();await page.getByRole('article',{name:'三图总结报告'}).getByText('三图总结版本 2',{exact:true}).waitFor()
  assert.notEqual(summaryRequests[1].observationIds[0],submittedIds[0]);assert.equal(summaryRequests[1].observationIds[1],submittedIds[1]);checks++
  await page.getByText('尾盘买入 / 次日早盘卖出收益试算',{exact:true}).click()
  await page.getByLabel('尾盘买入价',{exact:true}).fill('100');await page.getByLabel('次日早盘卖出价（假设）').fill('102');await page.getByText('1.84%',{exact:true}).waitFor();checks++
  const retainedChart=await screen.boundingBox();assert.ok(retainedChart.y>=0&&retainedChart.y+retainedChart.height<=1100,'right-side reading must retain the browser viewport');checks++
  await page.getByRole('article',{name:'三图总结报告'}).evaluate(e=>e.scrollIntoView({block:'start'}));await page.screenshot({path:path.join(output,'workbench-report.png'),fullPage:true})
  await page.locator('.eco-analysis').evaluate(e=>e.scrollTo(0,0));await page.locator('.eco-chart-pane').evaluate(e=>e.scrollTo(0,0))
  await page.screenshot({path:path.join(output,'three-step-desktop.png'),fullPage:true})
  const stepCount=stepRequests.length
  await page.reload();await page.getByRole('application').waitFor();await page.getByRole('article',{name:'三图总结报告'}).getByText('三图总结版本 2',{exact:true}).waitFor()
  assert.equal(stepRequests.length,stepCount);assert.equal(await page.getByRole('button',{name:'提交三图总结'}).isEnabled(),true);checks++
  await chooseOption('总结版本',/^版本 1 ·/);await page.getByRole('article',{name:'三图总结报告'}).getByText('三图总结版本 1',{exact:true}).waitFor()
  await chooseOption('总结版本',/^版本 2 ·/);await page.getByRole('article',{name:'三图总结报告'}).getByText('三图总结版本 2',{exact:true}).waitFor();checks++
  const decisionReport=page.getByRole('article',{name:'三图总结报告'})
  await decisionReport.getByText('是，符合进场条件',{exact:true}).waitFor();await decisionReport.getByText('是，尾盘买入',{exact:true}).waitFor();checks++
  const reportFixture=[...studies.values()].find(s=>s.reports.length===2).reports[0],originalReport={...reportFixture}
  Object.assign(reportFixture,{sufficientEvidence:false,entryDecision:'AVOID',overnightDecision:'AVOID'})
  await page.reload();await decisionReport.getByText('否，当前不进场',{exact:true}).waitFor();await decisionReport.getByText('否，当前不买入',{exact:true}).waitFor()
  assert.match(await decisionReport.locator('.eco-score').innerText(),/80/)
  await decisionReport.getByText('截图证据存在缺口，评分仅基于可核实部分；本次决策为不进场、不买入。',{exact:true}).waitFor();checks++
  Object.assign(reportFixture,{score:null,stage:null,entryDecision:'WAIT',overnightDecision:'WAIT',factors:[]})
  await page.reload();await decisionReport.getByText('等待确认（历史报告）',{exact:true}).waitFor()
  await decisionReport.getByText('历史报告：三图证据不足，未形成评分和买入结论。',{exact:true}).waitFor();checks++
  Object.assign(reportFixture,originalReport);await page.reload();await decisionReport.getByText('是，尾盘买入',{exact:true}).waitFor()
  assert.equal(listRequests.length,0,'the workbench must not load the database list');checks++
  await page.getByRole('link',{name:'观测数据库',exact:true}).click();await page.getByRole('heading',{name:'观测数据库',exact:true}).waitFor()
  assert.ok(page.url().endsWith('#/economy/database'));assert.equal(await page.getByRole('application').count(),0);checks++
  await page.reload();await page.getByRole('heading',{name:'观测数据库',exact:true}).waitFor();await page.getByText(/共 26 轮观测/).waitFor();checks++
  await chooseOption('查询标的类型','个股');await page.getByRole('button',{name:'查询观测'}).click();await page.getByText(/共 1 轮观测/).waitFor();await page.getByRole('button',{name:'重置筛选'}).click();await page.getByText(/共 26 轮观测/).waitFor();checks++
  await page.getByLabel('查询标的',{exact:true}).fill('NO-MATCH');await page.getByRole('button',{name:'查询观测'}).click();await page.getByRole('heading',{name:'还没有匹配的观测'}).waitFor()
  await page.getByRole('button',{name:'重置筛选'}).click();await page.getByText(/共 26 轮观测/).waitFor();assert.equal(await page.getByLabel('查询标的',{exact:true}).inputValue(),'');checks++
  await page.getByLabel('查询标的',{exact:true}).fill('OLD');await page.getByRole('button',{name:'查询观测'}).click();await page.getByText(/共 25 轮观测/).waitFor();await page.getByRole('button',{name:'下一页',exact:true}).click();await page.getByText(/第 2 \/ 2 页/).waitFor();checks++
  await page.locator('.eco-history button').filter({hasText:'1/3 步完成'}).click();await page.getByText('本轮尚无总结，可继续完成三步分析后提交。',{exact:true}).waitFor()
  await page.getByRole('button',{name:'继续本轮观测'}).click();await page.getByText('weekly 分析完成',{exact:true}).waitFor()
  assert.equal(await page.getByLabel('标的名称 / 代码').inputValue(),'OLD');assert.equal(stepRequests.length,stepCount);assert.equal(await page.getByLabel('补充资料与关注点',{exact:true}).inputValue(),'保留的观测关注点');checks++
  await page.getByRole('link',{name:'观测数据库',exact:true}).click();await page.getByRole('heading',{name:'观测数据库',exact:true}).waitFor()
  await page.getByLabel('查询标的',{exact:true}).fill('TEST');await page.getByRole('button',{name:'查询观测'}).click();await page.getByText(/共 1 轮观测/).waitFor();checks++
  await page.getByLabel('开始日期',{exact:true}).fill('2026-10-20');await page.getByLabel('结束日期',{exact:true}).fill('2026-10-01');const beforeInvalid=listRequests.length
  await page.getByRole('button',{name:'查询观测'}).click();await page.getByText('开始日期不能晚于结束日期。',{exact:true}).waitFor();assert.equal(listRequests.length,beforeInvalid);checks++
  await page.getByLabel('开始日期',{exact:true}).fill('');await page.getByLabel('结束日期',{exact:true}).fill('');await page.getByRole('button',{name:'查询观测'}).click();await page.getByText(/共 1 轮观测/).waitFor()
  listFails=true;await page.getByRole('button',{name:'刷新记录'}).click();await page.getByRole('button',{name:'重试读取',exact:true}).click();await page.getByText(/共 1 轮观测/).waitFor();checks++
  await page.screenshot({path:path.join(output,'database-list.png'),fullPage:true})
  detailFails=true;await page.getByRole('button',{name:/查看 TEST .* 观测/}).click();await page.getByRole('button',{name:'重试观测详情'}).click()
  const recordDetail=page.getByRole('region',{name:'观测详情',exact:true})
  await recordDetail.getByRole('article',{name:'三图总结报告'}).getByText('三图总结版本 2',{exact:true}).waitFor();checks++
  await chooseOption('总结版本',/^版本 1 ·/)
  await recordDetail.getByText('三图总结版本 1',{exact:true}).waitFor();await recordDetail.getByText('本总结引用的三张截图',{exact:true}).click();await recordDetail.getByRole('img',{name:'总结引用周线截图'}).waitFor()
  assert.ok(imageRequests.includes(submittedIds[0]));assert.equal(stepRequests.length,stepCount);checks++
  await recordDetail.evaluate(e=>e.scrollTo(0,0));await page.screenshot({path:path.join(output,'database-detail.png'),fullPage:true});await auditContrast();await auditScrollbars()
  await page.getByRole('button',{name:'主题',exact:true}).click();await page.getByRole('button',{name:'深色',exact:true}).click();await page.keyboard.press('Escape');await page.waitForFunction(()=>document.documentElement.dataset.scheme==='dark')
  await page.getByRole('combobox',{name:'总结版本',exact:true}).click();await page.screenshot({path:path.join(output,'tailwind-report-menu-dark.png'),fullPage:true});await auditScrollbars();await page.keyboard.press('Escape')
  await page.screenshot({path:path.join(output,'database-dark.png'),fullPage:true});await auditContrast()
  await page.getByRole('button',{name:'主题',exact:true}).click();await page.getByRole('button',{name:'浅色',exact:true}).click();await page.keyboard.press('Escape')
  await page.setViewportSize({width:390,height:844});await recordDetail.evaluate(e=>e.scrollIntoView({block:'start'}));await page.screenshot({path:path.join(output,'database-mobile.png'),fullPage:true})
  assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1));checks++
  await page.getByRole('combobox',{name:'总结版本',exact:true}).click();const mobileList=page.getByRole('listbox',{name:'总结版本',exact:true});const menuBox=await mobileList.boundingBox()
  assert.ok(menuBox.x>=0&&menuBox.x+menuBox.width<=390&&menuBox.y>=0&&menuBox.y+menuBox.height<=844)
  assert.ok(await mobileList.getByRole('option').first().evaluate(e=>parseFloat(getComputedStyle(e).fontSize)>=16));await page.screenshot({path:path.join(output,'tailwind-menu-mobile.png'),fullPage:true});await page.keyboard.press('Escape');checks++
  await recordDetail.getByRole('button',{name:'关闭观测详情'}).click();await page.getByRole('button',{name:/查看 TEST .* 观测/}).click();await recordDetail.getByRole('article',{name:'三图总结报告'}).waitFor()
  const detailBox=await recordDetail.boundingBox();assert.ok(detailBox.y>=0&&detailBox.y<844);checks++
  await page.evaluate(()=>document.documentElement.style.zoom='2');assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1));checks++
  await page.evaluate(()=>document.documentElement.style.zoom='1')
  await page.setViewportSize({width:1680,height:1100})
  const spacing=await page.addStyleTag({content:'.eco-page *{line-height:1.5!important;letter-spacing:.12em!important;word-spacing:.16em!important}.eco-page p{margin-bottom:2em!important}'})
  assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1));await spacing.evaluate(e=>e.remove());checks++
  await page.getByText('旧版单图分析记录',{exact:true}).click();await page.getByText(/旧标的 ·/).waitFor();checks++
  await recordDetail.getByRole('button',{name:'继续本轮观测'}).click();await page.getByRole('article',{name:'三图总结报告'}).getByText('三图总结版本 2',{exact:true}).waitFor()
  await page.getByRole('combobox',{name:'分析 Agent',exact:true,includeHidden:true}).filter({hasText:'分析员乙'}).waitFor({state:'attached'})
  assert.equal(stepRequests.length,stepCount);assert.equal(await page.getByRole('combobox',{name:'分析 Agent',exact:true,includeHidden:true}).textContent(),'分析员乙');checks++
  await page.getByRole('button',{name:'新一轮观测',exact:true}).click();await chooseOption('标的类型','大盘')
  assert.equal(await page.getByRole('heading',{name:'隔夜策略工作台',exact:true}).count(),1);checks++
  await page.getByRole('tab',{name:'已打开窗口'}).click()
  await page.evaluate(()=>Object.defineProperty(navigator,'mediaDevices',{configurable:true,value:{getDisplayMedia:async()=>{const canvas=document.createElement('canvas');canvas.width=800;canvas.height=600;canvas.getContext('2d').fillRect(0,0,800,600);const stream=canvas.captureStream(2);const track=stream.getVideoTracks()[0];track.getSettings=()=>({displaySurface:'window'});window.__testTrack=track;return stream}}}))
  await page.getByRole('button',{name:'选择共享窗口'}).click();await page.getByRole('img',{name:/窗口画面/}).waitFor()
  await page.getByRole('button',{name:'周线分析',exact:true}).click();await page.getByText('weekly 分析完成',{exact:true}).waitFor()
  assert.ok(stepRequests.at(-1).screenshot.startsWith('data:image/jpeg;base64,'));assert.equal(stepRequests.at(-1).browserId,undefined);checks++
  // Leaving an active web share releases the permission stream; the same window
  // can be selected again when returning, without a stale disabled button.
  await leaveResearch();assert.equal(await page.evaluate(()=>window.__testTrack.readyState),'ended')
  await returnResearch();assert.equal(await page.getByRole('img',{name:/窗口画面/}).count(),0)
  assert.equal(await page.getByRole('button',{name:'选择共享窗口'}).isEnabled(),true);checks++
  await page.getByRole('button',{name:'选择共享窗口'}).click();await page.getByRole('img',{name:/窗口画面/}).waitFor();checks++
  await page.evaluate(()=>{window.__oldTrack=window.__testTrack;window.__oldEnded=window.__testTrack.onended})
  await page.getByRole('button',{name:'更换共享窗口'}).click();await page.getByRole('img',{name:/窗口画面/}).waitFor()
  await page.evaluate(()=>window.__oldEnded());assert.equal(await page.evaluate(()=>window.__oldTrack.readyState),'ended')
  assert.equal(await page.evaluate(()=>window.__testTrack.readyState),'live');checks++
  await page.evaluate(()=>{window.__getDisplay=navigator.mediaDevices.getDisplayMedia;navigator.mediaDevices.getDisplayMedia=async()=>{throw new DOMException('取消共享','NotAllowedError')}})
  await page.getByRole('button',{name:'更换共享窗口'}).click();await page.getByRole('alert').filter({hasText:'取消共享'}).waitFor()
  await page.evaluate(()=>navigator.mediaDevices.getDisplayMedia=window.__getDisplay)
  await page.getByRole('button',{name:'选择共享窗口'}).click();await page.getByRole('img',{name:/窗口画面/}).waitFor();checks++
  await page.getByRole('button',{name:'停止观察'}).click();assert.equal(await page.evaluate(()=>window.__testTrack.readyState),'ended');checks++
  // A chooser resolving after navigation must release its late stream too.
  await page.evaluate(()=>navigator.mediaDevices.getDisplayMedia=()=>new Promise(resolve=>{window.__resolveDisplay=resolve}))
  await page.getByRole('button',{name:'选择共享窗口'}).click();await page.waitForFunction(()=>!!window.__resolveDisplay)
  await leaveResearch();await page.evaluate(async()=>window.__resolveDisplay(await window.__getDisplay()))
  await page.waitForFunction(()=>window.__testTrack.readyState==='ended');await returnResearch()
  assert.equal(await page.getByRole('button',{name:'选择共享窗口'}).isEnabled(),true);checks++
  await page.evaluate(()=>navigator.mediaDevices.getDisplayMedia=window.__getDisplay)
  await page.getByRole('button',{name:'选择共享窗口'}).click();await page.getByRole('img',{name:/窗口画面/}).waitFor()
  await page.getByRole('button',{name:'停止观察'}).click();checks++
  await page.evaluate(()=>{window.__TAURI_INTERNALS__={};window.__captureCount=0;window.__windowList=[{id:123,pid:456,title:'本地三图测试',app:'Fixture'},{id:124,pid:457,title:'第二窗口',app:'Fixture'}];window.__TAURI__={core:{invoke:async(cmd,args)=>{if(cmd==='economy_windows')return window.__windowList;if(cmd==='economy_capture_window'){window.__captureCount++;if(window.__captureFailure)throw new Error('截图失败');const c=document.createElement('canvas');c.width=100;c.height=100;return{image:c.toDataURL('image/jpeg'),title:args.id===123?'本地三图测试':'第二窗口',capturedAt:Date.now()}}return null}}}})
  await page.getByRole('tab',{name:'内置浏览器'}).click();await page.getByRole('tab',{name:'已打开窗口'}).click();await page.getByRole('button',{name:'列出已打开窗口'}).click();await chooseOption('选择观察窗口','Fixture · 本地三图测试');await page.getByRole('button',{name:'开始观察'}).click();await page.getByRole('img',{name:'窗口画面：本地三图测试'}).waitFor()
  assert.equal(await page.getByLabel('选择观察窗口').isEnabled(),true);checks++
  await leaveResearch();const leftCount=await page.evaluate(()=>window.__captureCount);await page.waitForTimeout(3200)
  assert.equal(await page.evaluate(()=>window.__captureCount),leftCount);checks++
  await returnResearch();await page.getByRole('img',{name:'窗口画面：本地三图测试'}).waitFor()
  assert.equal(await page.getByRole('combobox',{name:'选择观察窗口',exact:true}).textContent(),'Fixture · 本地三图测试');assert.ok(await page.evaluate(()=>window.__captureCount)>leftCount);checks++
  await chooseOption('选择观察窗口','Fixture · 第二窗口');assert.equal(await page.getByRole('img',{name:/窗口画面/}).count(),0)
  await page.getByRole('button',{name:'开始观察'}).click();await page.getByRole('img',{name:'窗口画面：第二窗口'}).waitFor();checks++
  await page.getByRole('button',{name:'停止观察'}).click();const count=await page.evaluate(()=>window.__captureCount);await page.waitForTimeout(3200);assert.equal(await page.evaluate(()=>window.__captureCount),count);checks++
  await leaveResearch();await returnResearch();await page.getByRole('button',{name:'列出已打开窗口'}).waitFor()
  assert.equal(await page.getByRole('img',{name:/窗口画面/}).count(),0);assert.equal(await page.getByRole('combobox',{name:'选择观察窗口',exact:true}).textContent(),'Fixture · 第二窗口')
  assert.equal(await page.getByRole('button',{name:'开始观察'}).isEnabled(),true);checks++
  await page.getByRole('button',{name:'开始观察'}).click();await page.getByRole('img',{name:'窗口画面：第二窗口'}).waitFor()
  await leaveResearch();await page.evaluate(()=>window.__windowList=window.__windowList.filter(w=>w.id===123))
  await returnResearch();await page.getByRole('alert').filter({hasText:'原观察窗口已关闭或最小化'}).waitFor()
  assert.equal(await page.getByRole('combobox',{name:'选择观察窗口',exact:true}).textContent(),'请选择一个窗口');assert.equal(await page.getByLabel('选择观察窗口').isEnabled(),true);checks++
  await chooseOption('选择观察窗口','Fixture · 本地三图测试');await page.evaluate(()=>window.__captureFailure=true)
  await page.getByRole('button',{name:'开始观察'}).click();await page.getByRole('alert').filter({hasText:'截图失败'}).waitFor()
  assert.equal(await page.getByLabel('选择观察窗口').isEnabled(),true);assert.equal(await page.getByRole('button',{name:'开始观察'}).isEnabled(),true);checks++
  await page.evaluate(()=>window.__captureFailure=false);await page.getByRole('button',{name:'开始观察'}).click();await page.getByRole('img',{name:'窗口画面：本地三图测试'}).waitFor()
  await page.screenshot({path:path.join(output,'window-restored.png'),fullPage:true})
  await page.getByRole('button',{name:'停止观察'}).click()
  await page.evaluate(()=>{delete window.__TAURI_INTERNALS__;delete window.__TAURI__})
  await page.goto('http://economy.test/#/economy/stock');await page.reload();await page.getByRole('heading',{name:'隔夜策略工作台',exact:true}).waitFor();checks++
  await page.setViewportSize({width:390,height:844});await page.screenshot({path:path.join(output,'unified-mobile.png'),fullPage:true});assert.ok((await page.locator('.eco-research').boundingBox()).width>=350);assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1));checks++
  await page.setViewportSize({width:1440,height:1000});await page.evaluate(()=>document.documentElement.style.zoom='2');await page.screenshot({path:path.join(output,'unified-zoom200.png'),fullPage:true});assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1));checks++
  assert.deepEqual(errors,[])
  console.log(JSON.stringify({browserChecks:checks,browserErrors:errors,output}))
}finally{await browser.close()}
