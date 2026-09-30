/**
 * 关于页：向用户说明整个项目。
 * 顶部滑块切换三种视角（默认「关于」）：
 * ① 关于——Mono 排版 hero：左侧 slogan，右侧「点 + 关系」构成的自转知识球（canvas 手写 3D，定位在右上并越出容器，只露出左侧大部分）；
 * ② 业务流程——知识从「导入」到「被问答/检索」的主线步骤（每个阶段标注对应页面入口，可直接跳转）；
 * ③ 数据流向——程序员视角的数据流树状图（前端 / 后端子 Tab，见 DataFlow.tsx）。
 * 纯静态展示页，不请求后端。
 */

import { useEffect, useRef, useState, type ReactNode } from 'react'
import DataFlow from './DataFlow'
import { BRAND } from '../branding'

/** 关于页 Tab 键 */
type AboutTab = 'about' | 'flow' | 'data'

const TABS: { key: AboutTab; label: string }[] = [
  { key: 'about', label: '关于' },
  { key: 'flow', label: '业务流程' },
  { key: 'data', label: '数据流向' },
]

/** 主 Tab 切换按钮 */
function MainTab({ active, onClick, children }: { active: boolean; onClick: () => void; children: ReactNode }) {
  return (
    <button
      type="button"
      onClick={onClick}
      className={`rounded-full px-4 py-1.5 text-[12.5px] font-semibold transition ${
        active ? 'bg-accent text-accent-fg' : 'text-muted hover:bg-fg/5 hover:text-fg'
      }`}
    >
      {children}
    </button>
  )
}

/* ---------------- 关于：Mono hero + 知识球 ---------------- */

/**
 * 知识球：点（实体）+ 线（关系）构成的自转球体。
 * 不引第三方 3D 库——斐波那契球面撒点、纬度分带成环、再连最近邻，
 * 自行做绕 Y 轴旋转 + 绕 X 轴倾角 + 透视投影，由远及近逐层绘制营造纵深。
 * 画布铺满外层容器，尺寸与裁切交给外层（球体越出页面右上）。
 */
function Orb() {
  const canvasRef = useRef<HTMLCanvasElement>(null)

  useEffect(() => {
    const cv = canvasRef.current
    if (!cv) return
    const ctx = cv.getContext('2d')
    if (!ctx) return

    /* 球面撒点：斐波那契分布保证疏密均匀 */
    const N = 132
    const p0: { x: number; y: number; z: number }[] = []
    for (let i = 0; i < N; i++) {
      const y = 1 - (i / (N - 1)) * 2
      const r = Math.sqrt(Math.max(0, 1 - y * y))
      const th = i * Math.PI * (3 - Math.sqrt(5))
      p0.push({ x: Math.cos(th) * r, y, z: Math.sin(th) * r })
    }

    /* 连线：先按纬度分带、带内连成闭合环（“环形图”骨架），再让每个点接上最近的异带邻居（“关系”） */
    const edges: number[][] = []
    const linked = new Set<string>()
    const link = (a: number, b: number) => {
      const key = a < b ? `${a}:${b}` : `${b}:${a}`
      if (linked.has(key)) return
      linked.add(key)
      edges.push([a, b])
    }
    const BANDS = 11
    const bandSize = Math.ceil(N / BANDS)
    const order = Array.from({ length: N }, (_, i) => i).sort((a, b) => p0[b].y - p0[a].y)
    const bandOf = new Array<number>(N).fill(0)
    for (let b = 0; b < BANDS; b++) {
      const seg = order.slice(b * bandSize, (b + 1) * bandSize)
      for (const i of seg) bandOf[i] = b
      for (let i = 0; i < seg.length; i++) link(seg[i], seg[(i + 1) % seg.length])
    }
    for (let i = 0; i < N; i++) {
      let best = -1
      let bd = Infinity
      for (let j = 0; j < N; j++) {
        if (bandOf[j] === bandOf[i]) continue
        const dx = p0[i].x - p0[j].x
        const dy = p0[i].y - p0[j].y
        const dz = p0[i].z - p0[j].z
        const d = dx * dx + dy * dy + dz * dz
        if (d < bd) {
          bd = d
          best = j
        }
      }
      if (best >= 0) link(i, best)
    }

    /* 度数最高的一成点视为「枢纽」，用主色点亮 */
    const degree = new Array<number>(N).fill(0)
    for (const e of edges) {
      degree[e[0]]++
      degree[e[1]]++
    }
    const hubCut = [...degree].sort((a, b) => b - a)[Math.floor(N * 0.1)]

    /* 配色取自语义令牌，主题切换时重读 */
    const read = (n: string, f: string) =>
      getComputedStyle(document.documentElement).getPropertyValue(n).trim() || f
    let pal = { accent: '#4b74ff', node: '#8aa0ff', line: '#9aa4b2', edge: '#6b7280' }
    const readPal = () => {
      pal = {
        accent: read('--c-accent', '#4b74ff'),
        node: read('--c-hint', '#8aa0ff'),
        line: read('--c-line-strong', '#9aa4b2'),
        edge: read('--c-muted', '#6b7280'), // 关系线用比轮廓更深的一档，避免在浅底上发灰
      }
    }
    readPal()
    const mo = new MutationObserver(readPal)
    mo.observe(document.documentElement, { attributes: true, attributeFilter: ['style'] })

    let w = 0
    let h = 0
    const resize = () => {
      const rect = cv.getBoundingClientRect()
      const dpr = Math.min(window.devicePixelRatio || 1, 2)
      w = rect.width
      h = rect.height
      cv.width = Math.max(1, Math.round(w * dpr))
      cv.height = Math.max(1, Math.round(h * dpr))
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0)
    }
    resize()
    const ro = new ResizeObserver(resize)
    ro.observe(cv)

    /* 每帧的投影结果 */
    const sx = new Float32Array(N)
    const sy = new Float32Array(N)
    const ss = new Float32Array(N) // 透视缩放
    const sd = new Float32Array(N) // 深度：0 最远 → 1 最近
    /* 边排序缓冲：复用同一组数组，避免每帧新建对象触发 GC */
    const eDep = new Float32Array(edges.length)
    const eOrder = Array.from({ length: edges.length }, (_, i) => i)
    const FOCAL = 3.1
    const TILT = 0.42 // 固定倾角，让环带看得出来
    const start = performance.now()
    let raf = 0

    const draw = (now: number) => {
      /* 兜底：布局尺寸与画布分辨率不一致时（含首帧晚于 mount 的情况）重新同步。
         clientWidth 是整数、getBoundingClientRect().width 常带小数（64% 宽度），
         必须都取整比较，否则每帧都会判定为"变了"而重设 canvas.width —— 那会重分配整张位图并强制重排，表现为掉帧、线条半天画不出来。 */
      if (Math.round(w) !== cv.clientWidth || Math.round(h) !== cv.clientHeight) resize()

      const t = (now - start) / 1000
      const cosY = Math.cos(t * 0.16) // 缓慢自转
      const sinY = Math.sin(t * 0.16)
      const cosX = Math.cos(TILT)
      const sinX = Math.sin(TILT)
      const R = Math.min(w, h) * 0.46 // 尽量撑满画布，越出的部分由外层裁切
      const cx = w / 2
      const cy = h / 2

      for (let i = 0; i < N; i++) {
        const p = p0[i]
        const x1 = p.x * cosY + p.z * sinY
        const z1 = p.z * cosY - p.x * sinY
        const y1 = p.y * cosX - z1 * sinX
        const z2 = p.y * sinX + z1 * cosX
        const s = FOCAL / (FOCAL - z2)
        sx[i] = cx + x1 * R * s
        sy[i] = cy + y1 * R * s
        ss[i] = s
        sd[i] = (z2 + 1) / 2
      }

      ctx.clearRect(0, 0, w, h)

      /* 球面轮廓：一条极淡的细线，让球体边缘是平滑的弧而不是散点的毛边 */
      ctx.strokeStyle = pal.line
      ctx.lineWidth = 1
      ctx.globalAlpha = 0.12
      ctx.beginPath()
      ctx.arc(cx, cy, R * (FOCAL / Math.sqrt(FOCAL * FOCAL - 1)), 0, Math.PI * 2)
      ctx.stroke()

      /* 关系：只画朝向观察者的一侧，越近越实；背面不画，避免边缘毛糙、中心发暗。
         注意：可见区域正好压在球体左下角的轮廓带上，那里边的深度均值长期在 0 附近漂移——
         若用 `f <= 0` 硬剔除，边会在"画/不画"之间反复跳变（看着就是迟迟渲染不出来）。
         改成在轮廓内侧留一小段软过渡，跨过轮廓时渐隐而非突现。 */
      const LIMB = 0.1
      for (let i = 0; i < edges.length; i++) {
        const e = edges[i]
        eDep[i] = (sd[e[0]] + sd[e[1]]) / 2
      }
      eOrder.sort((a, b) => eDep[a] - eDep[b])
      ctx.strokeStyle = pal.edge
      for (const i of eOrder) {
        const f = eDep[i] * 2 - 1 // 0 在轮廓处 → 1 离观察者最近
        if (f <= -LIMB) continue // 更靠里的背面才整条丢掉
        const a = f >= 0 ? 1 : 1 + f / LIMB // 轮廓处平滑收敛到 0
        const k = Math.max(0, f)
        /* 起手就给足底透明度，再线性加到实，避免线在转动中长时间"渐隐" */
        ctx.globalAlpha = a * (0.26 + k * 0.4)
        ctx.lineWidth = 0.8 + k * 0.5
        const e = edges[i]
        ctx.beginPath()
        ctx.moveTo(sx[e[0]], sy[e[0]])
        ctx.lineTo(sx[e[1]], sy[e[1]])
        ctx.stroke()
      }

      /* 实体：近侧的点更大更亮；枢纽用主色并缓慢呼吸，背面只留一层极淡的点交代体积 */
      const nOrder = Array.from({ length: N }, (_, i) => i).sort((a, b) => sd[a] - sd[b])
      for (const i of nOrder) {
        const f = sd[i] * 2 - 1
        const near = f > 0
        const hub = degree[i] >= hubCut
        const breathe = hub ? 1 + Math.sin(t * 1.1 + i) * 0.16 : 1
        const r = (hub ? 4.8 : 3) * (near ? 0.7 + f * 1.1 : 0.5) * ss[i] * breathe
        ctx.globalAlpha = near ? f * f * 0.95 : 0.05
        ctx.fillStyle = hub ? pal.accent : pal.node
        ctx.beginPath()
        ctx.arc(sx[i], sy[i], Math.max(1.6, r), 0, Math.PI * 2)
        ctx.fill()
      }
      ctx.globalAlpha = 1
    }

    const tick = (now: number) => {
      draw(now)
      raf = requestAnimationFrame(tick)
    }
    raf = requestAnimationFrame(tick)

    return () => {
      cancelAnimationFrame(raf)
      ro.disconnect()
      mo.disconnect()
    }
  }, [])

  return <canvas ref={canvasRef} className="h-full w-full" aria-hidden="true" />
}

/** 关于：Mono 排版 hero —— 左侧 slogan 与说明，右侧越出容器、被裁切的知识球托起整版 */
function Intro() {
  return (
    /* overflow-hidden：知识球越出容器后在此裁切，只留下球体靠左的大部分 */
    <div className="relative flex min-h-[440px] items-center overflow-hidden sm:min-h-[540px] lg:min-h-[600px]">
      {/* 知识球：定位到右上方并越出容器边界，球心落在容器右上之外 */}
      <div
        aria-hidden="true"
        className="pointer-events-none absolute -top-[14%] -right-[52%] aspect-square w-[150%] sm:-top-[16%] sm:-right-[24%] sm:w-[86%] lg:-top-[20%] lg:-right-[12%] lg:w-[64%]"
      >
        <Orb />
      </div>

      {/* 文案：压在知识球之上 */}
      <div className="relative z-10 flex max-w-[520px] flex-col gap-5 font-mono">
        <p className="flex items-center gap-3 text-[11px] tracking-[0.24em] text-faint">
          <span aria-hidden="true" className="h-px w-8 bg-line-strong" />
          {BRAND.nameUpper}
        </p>

        <h1 className="text-[30px] leading-[1.2] font-semibold tracking-tight text-fg sm:text-[40px]">
          把零散资料，
          <br />
          沉淀为<span className="text-accent">可检索</span>的知识。
        </h1>

        <p className="mt-1 max-w-[52ch] text-[12.5px] leading-[1.9] text-muted">
          {BRAND.name}
          是一套「个人知识库 + 检索问答」系统：把零散资料导入并切分为知识块，用大模型抽取其中的实体与关系沉淀为知识图谱，
          再为每个块补充利于检索的语义信息并向量化。知识入库完成后，即可通过检索或对话的方式随时取用。
        </p>
      </div>

      {/* 角落标注：Mono 排版的技术感 */}
      <p className="pointer-events-none absolute bottom-0 left-0 font-mono text-[10px] tracking-[0.2em] text-faint">
        ENTITY · RELATION SPHERE
      </p>
    </div>
  )
}

/* ---------------- 业务流程 ---------------- */

/** 一个业务阶段：序号、标题、对应页面入口（可选）、概述、要点列表 */
type Stage = {
  no: string
  title: string
  /** 对应页面路由（#/xxx），无则不显示跳转入口 */
  route?: string
  routeLabel?: string
  intro: string
  points: string[]
}

/** 主线：知识从入库到被消费的六个阶段，顺序即用户实际操作的推进路径 */
const STAGES: Stage[] = [
  {
    no: '1',
    title: '导入文档',
    route: '/import',
    routeLabel: '文档导入',
    intro:
      '把原始资料（Markdown / 纯文本）粘贴进来，作为一篇「文章」保存；导入即自动分块，为后续的抽取与检索准备好最小的知识单元。',
    points: [
      '粘贴 hexo / hugo 文档时自动识别 front-matter 的 title，可手动修改',
      '正文原样存为 Markdown 文章，同时建立文档索引',
      '保存时自动切分为若干 Chunk（段落级知识块），构成后续所有流程的基础',
    ],
  },
  {
    no: '2',
    title: '选择 Chunk',
    route: '/analyze',
    routeLabel: '文档分析',
    intro:
      '在「文档分析」页的步骤一，以文档折叠表的形式浏览已入库内容，展开文档即可看到其下的所有 Chunk，选一块推进知识提取。',
    points: [
      '文档与 Chunk 为父子结构，展开文档即可查看其下各块',
      '可对文档重新分块、生成全文摘要；可对单块删除或启用 / 禁用',
      '选中某块后经该行进入「知识提取」，工作流只能逐步推进，不支持跳步',
    ],
  },
  {
    no: '3',
    title: '知识提取',
    route: '/analyze',
    routeLabel: '文档分析',
    intro:
      '把 Chunk 交给大模型抽取「实体—关系—实体」三元组，并自动做消歧评估，确认后写入知识图谱并与该 Chunk 关联。',
    points: [
      '左侧为父文档摘要 + Chunk 原文，组装成上下文补完串送入模型',
      '抽取结果以「点—边—点」卡片呈现，标注实体「已存在 / 疑似 / 新实体」与关系是否重复',
      '点击实体可查看其 1 跳邻域图，支持合并疑似实体、重命名后自动重新消歧',
      '待添加的实体属性以蓝点提示，可逐项忽略；勾选后提交，写入图谱并关联 Chunk',
      '提交后 Chunk 仍为未分析状态，需完成「Chunk 增强」才真正启用',
    ],
  },
  {
    no: '4',
    title: 'Chunk 增强',
    route: '/analyze',
    routeLabel: '文档分析',
    intro:
      '为大模型检索准备更易定位的语义信息：为 Chunk 生成 RAG 语义定位头（Header）与假设性问题，组成最终提交预案。',
    points: [
      '「Chunk Header 生成」组装章节路径、段落位置、摘要、父文档概述、关联实体、入库时间、相邻段落等定位信息',
      '「生成假设性问题」生成该段落能回答的若干问题，提升召回命中率',
      '最终提交预案为可分段编辑的文本框，生成内容会自动留档，二次进入自动回显',
      '提交时先弹窗预览三分节全文，确认后向量化写入、分段留档，Chunk 正式启用——知识入库流程完成',
    ],
  },
  {
    no: '5',
    title: '浏览与维护知识图谱',
    route: '/graph',
    routeLabel: '知识图谱',
    intro:
      '入库的实体与关系汇聚成一张全局知识图谱，可自由探索、检索与订正，让知识随使用不断被校正与丰富。',
    points: [
      '满屏画布可视化实体与关系，支持实体搜索、跳数 / 规模调节与渲染引擎切换',
      '点击节点或关系查看详情，可编辑实体与关系的属性',
      '左下角提供图例与谓词过滤，便于聚焦某一类关系',
    ],
  },
  {
    no: '6',
    title: '检索与问答',
    route: '/',
    routeLabel: '首页',
    intro:
      '知识入库后即可被消费：首页在「检索」与「会话」两种模式间切换，分别对应结果式检索与基于知识库的对话式问答。',
    points: [
      '检索模式：返回文档与实体命中列表，实体命中可一键跳转到知识图谱',
      '会话模式：与大模型连续对话，可查看思维链（CoT）、切换本次会话使用的模型与系统提示词，并实时显示上下文占用',
      '另有「设置」页配置模型、提示词与向量化（Embedding），为提取与问答提供底层能力',
    ],
  },
]

/** 阶段跳转入口：小号链接按钮（有 route 时才渲染） */
function RouteLink({ route, label }: { route: string; label: string }) {
  return (
    <a
      href={`#${route}`}
      className="inline-flex shrink-0 items-center gap-1 rounded-full border border-line px-3 py-1 text-[12px] font-medium text-muted transition hover:border-line-strong hover:bg-fg/5 hover:text-fg"
    >
      前往{label}
      <span aria-hidden="true">→</span>
    </a>
  )
}

export default function About() {
  const [tab, setTab] = useState<AboutTab>('about')

  return (
    <section className="flex flex-col gap-8 pt-6 pb-4">
      {/* 主 Tab：关于（项目定位）/ 业务流程（用户视角）/ 数据流向（程序员视角） */}
      <div className="flex w-fit gap-1 rounded-full border border-line p-1">
        {TABS.map((t) => (
          <MainTab key={t.key} active={tab === t.key} onClick={() => setTab(t.key)}>
            {t.label}
          </MainTab>
        ))}
      </div>

      {tab === 'about' && <Intro />}

      {tab === 'flow' && (
        /* 主线：知识入库 → 知识消费 的分步说明（左侧时间线 + 右侧说明卡片） */
        <div className="flex flex-col gap-6">
          <h2 className="text-[15px] font-semibold text-fg">整体业务流程</h2>
          <ol className="flex flex-col">
            {STAGES.map((s, i) => (
              <li key={s.no} className="flex gap-4">
                {/* 左侧：序号圆点 + 竖直连线 */}
                <div className="flex flex-col items-center">
                  <span className="flex h-8 w-8 shrink-0 items-center justify-center rounded-full bg-accent text-[13px] font-semibold text-accent-fg">
                    {s.no}
                  </span>
                  {i < STAGES.length - 1 && <span aria-hidden="true" className="w-px flex-1 bg-line" />}
                </div>

                {/* 右侧：阶段卡片 */}
                <div className="flex-1 pb-6">
                  <div className="flex flex-wrap items-center justify-between gap-2">
                    <h3 className="text-[14px] font-semibold text-fg">{s.title}</h3>
                    {s.route && s.routeLabel && <RouteLink route={s.route} label={s.routeLabel} />}
                  </div>
                  <p className="mt-2 text-[13px] leading-relaxed text-muted">{s.intro}</p>
                  <ul className="mt-2.5 flex flex-col gap-1.5">
                    {s.points.map((p) => (
                      <li key={p} className="flex gap-2 text-[12.5px] leading-relaxed text-faint">
                        <span aria-hidden="true" className="mt-[7px] h-1 w-1 shrink-0 rounded-full bg-faint" />
                        <span>{p}</span>
                      </li>
                    ))}
                  </ul>
                </div>
              </li>
            ))}
          </ol>
        </div>
      )}

      {tab === 'data' && <DataFlow />}
    </section>
  )
}
