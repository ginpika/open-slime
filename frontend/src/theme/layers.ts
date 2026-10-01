/**
 * 六层投影：形状 / 深度 / 材质 / 排印 / 动效（色彩层由 derive.ts 从种子派生）
 *
 * 设计原则：
 * 1. 任何能写成 CSS 变量的东西都写成变量（包括切角 clip-path），
 *    这样组件骨架不因主题而分叉。
 * 2. 深度层一律用 rgb()/color-mix() 表达，两档明暗共用同一份声明；
 *    深浅必须不同的（如 Endfield 只有深档是玻璃）走 overrides[scheme]。
 * 3. 排印层只声明「策略」（字重 / 斜体 / 字距 / 大小写），不声明具体字号——
 *    字号谱系是全局常量（见 TYPE_SCALE），任何主题都不改它。
 */
import type { Vars } from './types'

/* ------------------------------------------------------------ 全局常量 */

/** 字体栈（可用全局字体覆盖面板替换 --ui-font-sans / --ui-font-mono） */
export const FONT_STACKS = {
  sans: '"Segoe UI","PingFang SC","Microsoft YaHei",system-ui,-apple-system,sans-serif',
  mono: 'ui-monospace,"Cascadia Mono","Consolas","SFMono-Regular",Menlo,monospace',
}

/**
 * 字号谱系：由原来散落的 20 档（10/10.5/11/11.5/…/40）收敛为 9 档。
 * 覆盖 Tailwind 的 --text-* 刻度，于是 text-xs / text-sm / text-base 等
 * 现有工具类会立刻变成主题无关的语义刻度。
 */
export const TYPE_SCALE: Vars = {
  '--ui-fs-micro': '10px', // 角标 / 微标签
  '--ui-fs-xs': '11px', // 辅助说明 / hint
  '--ui-fs-sm': '12px', // 次要正文 / 按钮
  '--ui-fs-base': '13px', // 正文
  '--ui-fs-md': '15px', // 强调正文 / 小节标题
  '--ui-fs-lg': '18px', // 卡片标题 / 文章标题
  '--ui-fs-xl': '22px', // 区块标题
  '--ui-fs-2xl': '28px', // 页面标题
  '--ui-fs-3xl': '36px', // Hero / 关键数据
}

/** 与主题无关的骨架常量 */
export const SKELETON: Vars = {
  '--ui-ring-w': '1px', // 描边环宽度（切角描边靠内外双缩实现）
  '--ui-disabled-opacity': '.4',
  ...TYPE_SCALE,
  '--ui-font-sans': FONT_STACKS.sans,
  '--ui-font-mono': FONT_STACKS.mono,
}

/* ------------------------------------------------------------- 各主题层 */

/** Old（现有默认）——近纯黑墨、中性灰线、无彩点缀、直角偏小圆角、无装饰阴影 */
const oldShape: Vars = {
  '--ui-r-xs': '2px',
  '--ui-r-sm': '2px',
  '--ui-r-md': '6px',
  '--ui-r-lg': '8px',
  '--ui-r-xl': '16px',
  '--ui-r-card': '6px',
  '--ui-r-pill': '999px',
  '--ui-clip': 'none',
  '--ui-clip-sm': 'none',
}
const oldDepth: Vars = {
  '--ui-e0': 'none',
  '--ui-e1': '0 1px 2px rgb(0 0 0 / .06)',
  '--ui-e2': '0 2px 6px rgb(0 0 0 / .08)',
  '--ui-e3': '0 8px 20px rgb(0 0 0 / .10)',
  '--ui-e4': '0 16px 36px rgb(0 0 0 / .14)',
  '--ui-e5': '0 26px 56px rgb(0 0 0 / .18)',
}
const oldSurface: Vars = { '--ui-panel-alpha': '100%', '--ui-blur': '0px', '--ui-lift': '0px' }
const oldTypo: Vars = {
  '--ui-font-heading': FONT_STACKS.sans,
  '--ui-heading-weight': '600',
  '--ui-heading-style': 'normal',
  '--ui-heading-tracking': '-0.01em',
  '--ui-body-tracking': '0em',
  '--ui-label-tracking': '0.12em',
  '--ui-label-transform': 'none',
  '--ui-btn-fs': 'var(--ui-fs-sm)',
  '--ui-btn-weight': '600',
  '--ui-btn-style': 'normal',
  '--ui-btn-tracking': '0em',
  '--ui-btn-transform': 'none',
  '--ui-btn-py': '6px',
  '--ui-btn-px': '14px',
}
const oldMotion: Vars = {
  '--ui-dur-fast': '120ms',
  '--ui-dur-base': '150ms',
  '--ui-dur-slow': '300ms',
  '--ui-ease': 'cubic-bezier(.2,.8,.2,1)',
  '--ui-ease-mech': 'steps(6,end)',
}

/** Clean（Tailwind 现代 SaaS）——石板灰、单一品牌蓝、克制的多层柔影、中圆角 */
const cleanShape: Vars = {
  '--ui-r-xs': '4px',
  '--ui-r-sm': '6px',
  '--ui-r-md': '8px',
  '--ui-r-lg': '12px',
  '--ui-r-xl': '16px',
  '--ui-r-card': '12px',
  '--ui-r-pill': '999px',
  '--ui-clip': 'none',
  '--ui-clip-sm': 'none',
}
const cleanDepth: Vars = {
  '--ui-e0': 'none',
  '--ui-e1': '0 1px 2px rgb(15 23 42 / .06)',
  '--ui-e2': '0 1px 3px rgb(15 23 42 / .08), 0 8px 24px rgb(15 23 42 / .06)',
  '--ui-e3': '0 2px 6px rgb(15 23 42 / .08), 0 16px 40px rgb(15 23 42 / .10)',
  '--ui-e4': '0 4px 10px rgb(15 23 42 / .10), 0 24px 56px rgb(15 23 42 / .14)',
  '--ui-e5': '0 8px 20px rgb(15 23 42 / .12), 0 36px 80px rgb(15 23 42 / .18)',
}
const cleanSurface: Vars = { '--ui-panel-alpha': '100%', '--ui-blur': '0px', '--ui-lift': '1px' }
const cleanTypo: Vars = {
  '--ui-font-heading': FONT_STACKS.sans,
  '--ui-heading-weight': '600',
  '--ui-heading-style': 'normal',
  '--ui-heading-tracking': '-0.02em',
  '--ui-body-tracking': '0em',
  '--ui-label-tracking': '0.10em',
  '--ui-label-transform': 'none',
  '--ui-btn-fs': 'var(--ui-fs-sm)',
  '--ui-btn-weight': '600',
  '--ui-btn-style': 'normal',
  '--ui-btn-tracking': '0em',
  '--ui-btn-transform': 'none',
  '--ui-btn-py': '7px',
  '--ui-btn-px': '16px',
}
const cleanMotion: Vars = {
  '--ui-dur-fast': '120ms',
  '--ui-dur-base': '150ms',
  '--ui-dur-slow': '250ms',
  '--ui-ease': 'cubic-bezier(.4,0,.2,1)',
  '--ui-ease-mech': 'steps(6,end)',
}

/** MD3（Material Design 3）——大圆角、官方五级 elevation、medium 字重、标准缓动 */
const md3Shape: Vars = {
  '--ui-r-xs': '4px',
  '--ui-r-sm': '8px',
  '--ui-r-md': '12px',
  '--ui-r-lg': '16px',
  '--ui-r-xl': '20px',
  '--ui-r-card': '24px',
  '--ui-r-pill': '999px',
  '--ui-clip': 'none',
  '--ui-clip-sm': 'none',
}
const md3Depth: Vars = {
  '--ui-e0': 'none',
  '--ui-e1': '0 1px 2px rgb(0 0 0 / .30), 0 1px 3px 1px rgb(0 0 0 / .15)',
  '--ui-e2': '0 1px 2px rgb(0 0 0 / .30), 0 2px 6px 2px rgb(0 0 0 / .15)',
  '--ui-e3': '0 1px 3px rgb(0 0 0 / .30), 0 4px 8px 3px rgb(0 0 0 / .15)',
  '--ui-e4': '0 2px 3px rgb(0 0 0 / .30), 0 6px 10px 4px rgb(0 0 0 / .15)',
  '--ui-e5': '0 4px 4px rgb(0 0 0 / .30), 0 8px 12px 6px rgb(0 0 0 / .15)',
}
const md3Surface: Vars = { '--ui-panel-alpha': '100%', '--ui-blur': '0px', '--ui-lift': '0px' }
const md3Typo: Vars = {
  '--ui-font-heading': FONT_STACKS.sans,
  '--ui-heading-weight': '500',
  '--ui-heading-style': 'normal',
  '--ui-heading-tracking': '0em',
  '--ui-body-tracking': '0.02em',
  '--ui-label-tracking': '0.08em',
  '--ui-label-transform': 'none',
  '--ui-btn-fs': 'var(--ui-fs-sm)',
  '--ui-btn-weight': '500',
  '--ui-btn-style': 'normal',
  '--ui-btn-tracking': '0.01em',
  '--ui-btn-transform': 'none',
  '--ui-btn-py': '8px',
  '--ui-btn-px': '20px',
}
const md3Motion: Vars = {
  '--ui-dur-fast': '150ms',
  '--ui-dur-base': '200ms',
  '--ui-dur-slow': '400ms',
  '--ui-ease': 'cubic-bezier(.2,0,0,1)',
  '--ui-ease-mech': 'steps(6,end)',
}

/**
 * Endfield（终末地）—— 双登记簿，基准 = docs/endfield-style-demo.html v3
 *
 *   深档（基准）碳黑战术玻璃 HUD：
 *     碳黑底 + 暗玻璃面板（比底色略亮 + blur16）+ 白 alpha 线 + 黄光晕替代投影 + 全息背景层
 *   浅档（overrides.light）工业印刷体：
 *     暖纸底 + 纯白实底 + 暖灰实线 + 硬偏移阴影（6px 6px 0）+ 无玻璃无光晕无全息
 *
 * 色彩补丁走 overrides（layerVars 在 colorVars 之后合并，因此可覆写 --c-*）：
 * 终末地的线条是「玻璃上的白 alpha 描边」而非「辅色实线」；灰阶直接取 demo 的
 * 五级冷灰；黄色只做几何与描边，hover 用更亮的黄而非掺底色。
 */
const endfieldShape: Vars = {
  '--ui-r-xs': '0px',
  '--ui-r-sm': '0px',
  '--ui-r-md': '0px',
  '--ui-r-lg': '0px',
  '--ui-r-xl': '0px',
  '--ui-r-card': '0px',
  '--ui-r-pill': '999px',
  /* 工业风：按钮 4px 圆角、原生控件 2px 微圆角（卡片仍走大切角 --ui-clip） */
  '--ui-r-btn': '4px',
  '--ui-r-btn-sm': '4px',
  '--ui-r-control': '2px',
  '--ui-clip':
    'polygon(12px 0,100% 0,100% calc(100% - 12px),calc(100% - 12px) 100%,0 100%,0 12px)',
  /* 小切角弃用：按钮 / 控件改为工业微圆角，仅卡片保留大切角 */
  '--ui-clip-sm': 'none',
}
const endfieldDepth: Vars = {
  '--ui-e0': 'none',
  /* 面板不投影：靠描边环 + 玻璃分层 */
  '--ui-e1': 'none',
  /* 黄光晕是深档唯一的"浮起"语汇（demo --acc-glow: 0 0 14px） */
  '--ui-e2': '0 0 14px rgb(255 212 0 / .42)',
  '--ui-e3': '0 0 20px rgb(255 212 0 / .5)',
  /* 模态 / 大浮层仍用黑色软投影压住背景全息层 */
  '--ui-e4': '0 24px 60px rgb(0 0 0 / .65)',
  '--ui-e5': '0 30px 70px rgb(0 0 0 / .72)',
}
const endfieldSurface: Vars = {
  /* 暗玻璃：62% + blur；卡片底比背景略亮（demo --surface #0f1215） */
  '--ui-panel-alpha': '62%',
  '--ui-blur': '16px',
  '--ui-lift': '0px',
  '--ui-card-fill': '#0f1215',
  /* 第二主行动青蓝：描边钮共用（定义点唯一） */
  '--c-cyan': '#38c2ff',
  /* 导航激活指示条：深档青蓝（浅档 overrides 覆写为黑） */
  '--ui-nav-edge': 'var(--c-cyan)',
  /* 面板默认环 = 白 alpha 强档（rule-2），黄环只留给强调态 */
  '--ui-card-ring': 'var(--c-line-strong)',
  /* 可交互卡 hover：描边环切到 55% 黄（demo --acc-edge） */
  '--ui-card-ring-hover': 'color-mix(in srgb, var(--c-accent) 55%, transparent)',
}
const endfieldTypo: Vars = {
  '--ui-font-heading': FONT_STACKS.sans,
  '--ui-heading-weight': '900',
  '--ui-heading-style': 'normal',
  /* 微标签工业强调：黑体重量级 800（通用 700） */
  '--ui-label-weight': '800',
  '--ui-heading-tracking': '0.02em',
  '--ui-body-tracking': '0.01em',
  '--ui-label-tracking': '0.22em',
  '--ui-label-transform': 'uppercase',
  '--ui-btn-fs': 'var(--ui-fs-base)',
  '--ui-btn-weight': '800',
  '--ui-btn-style': 'normal',
  '--ui-btn-tracking': '0.16em',
  '--ui-btn-transform': 'uppercase',
  '--ui-btn-py': '8px',
  '--ui-btn-px': '16px',
}
const endfieldMotion: Vars = {
  '--ui-dur-fast': '110ms',
  '--ui-dur-base': '130ms',
  '--ui-dur-slow': '220ms',
  '--ui-ease': 'cubic-bezier(.2,.8,.2,1)',
  /* 机械缓动：开关位移 / 旋转 / 闪烁一律 steps() 硬切到位 */
  '--ui-ease-mech': 'steps(8,end)',
}

/**
 * Endfield 按钮层：工业风实底按钮体系（登记簿，原 shell.css 补丁区回迁于此）。
 * 四变体统一 4px 圆角（shape 层 --ui-r-btn）；hover 一律向基色混白 14% 提亮。
 * 黑（次要钮）为固定黑，不随明暗档反转；黄 / 红引用色彩令牌以获得分档值。
 */
const endfieldBtn: Vars = {
  /* 主行动：黄底黑字；实底钮不吃浅档硬偏移影，压为 none */
  '--ui-btn-primary-ring': 'var(--c-accent)',
  '--ui-btn-primary-ring-hover': 'color-mix(in srgb, var(--c-accent) 86%, white)',
  '--ui-btn-primary-fill': 'var(--c-accent)',
  '--ui-btn-primary-fill-hover': 'color-mix(in srgb, var(--c-accent) 86%, white)',
  '--ui-btn-primary-fill-active': 'color-mix(in srgb, var(--c-accent) 86%, white)',
  '--ui-btn-primary-fg': 'var(--c-accent-fg)',
  '--ui-btn-primary-shadow': 'none',
  /* 描边钮（第二主行动）：青蓝实底黑字 */
  '--ui-btn-outline-ring': 'var(--c-cyan)',
  '--ui-btn-outline-ring-hover': 'color-mix(in srgb, var(--c-cyan) 86%, white)',
  '--ui-btn-outline-fill': 'var(--c-cyan)',
  '--ui-btn-outline-fill-hover': 'color-mix(in srgb, var(--c-cyan) 86%, white)',
  '--ui-btn-outline-fill-active': 'color-mix(in srgb, var(--c-cyan) 86%, white)',
  '--ui-btn-outline-fg': 'var(--c-accent-fg)',
  '--ui-btn-outline-fg-hover': 'var(--c-accent-fg)',
  /* 次要钮：黑底白字，hover 混白提亮一档、字色钉白 */
  '--ui-btn-quiet-ring': '#0a0b0c',
  '--ui-btn-quiet-ring-hover': 'color-mix(in srgb, #0a0b0c 86%, white)',
  '--ui-btn-quiet-fill': '#0a0b0c',
  '--ui-btn-quiet-fill-hover': 'color-mix(in srgb, #0a0b0c 86%, white)',
  '--ui-btn-quiet-fill-active': 'color-mix(in srgb, #0a0b0c 86%, white)',
  '--ui-btn-quiet-fg': '#fff',
  '--ui-btn-quiet-fg-hover': '#fff',
  /* 危险钮：红底白字 */
  '--ui-btn-danger-ring': 'var(--c-danger)',
  '--ui-btn-danger-ring-hover': 'color-mix(in srgb, var(--c-danger) 86%, white)',
  '--ui-btn-danger-fill': 'var(--c-danger)',
  '--ui-btn-danger-fill-hover': 'color-mix(in srgb, var(--c-danger) 86%, white)',
  '--ui-btn-danger-fill-active': 'color-mix(in srgb, var(--c-danger) 86%, white)',
  '--ui-btn-danger-fg': '#fff',
}

/** BlueArchive（蔚蓝档案）——全胶囊、蓝调柔影、粗斜体、回弹缓动 */
const baShape: Vars = {
  '--ui-r-xs': '12px',
  '--ui-r-sm': '14px',
  '--ui-r-md': '18px',
  '--ui-r-lg': '22px',
  '--ui-r-xl': '26px',
  '--ui-r-card': '26px',
  '--ui-r-pill': '999px',
  '--ui-clip': 'none',
  '--ui-clip-sm': 'none',
}
const baDepth: Vars = {
  '--ui-e0': 'none',
  '--ui-e1': '0 2px 6px color-mix(in srgb, var(--c-accent) 12%, transparent)',
  '--ui-e2': '0 4px 14px color-mix(in srgb, var(--c-accent) 16%, transparent)',
  '--ui-e3': '0 10px 26px color-mix(in srgb, var(--c-accent) 20%, transparent)',
  '--ui-e4': '0 18px 44px color-mix(in srgb, var(--c-accent) 22%, transparent)',
  '--ui-e5': '0 30px 70px color-mix(in srgb, var(--c-accent) 26%, transparent)',
}
const baSurface: Vars = { '--ui-panel-alpha': '100%', '--ui-blur': '0px', '--ui-lift': '3px' }
const baTypo: Vars = {
  '--ui-font-heading': FONT_STACKS.sans,
  '--ui-heading-weight': '900',
  '--ui-heading-style': 'italic',
  '--ui-heading-tracking': '-0.02em',
  '--ui-body-tracking': '0em',
  '--ui-label-tracking': '0.04em',
  '--ui-label-transform': 'none',
  '--ui-btn-fs': 'var(--ui-fs-sm)',
  '--ui-btn-weight': '900',
  '--ui-btn-style': 'italic',
  '--ui-btn-tracking': '0em',
  '--ui-btn-transform': 'none',
  '--ui-btn-py': '9px',
  '--ui-btn-px': '18px',
}
const baMotion: Vars = {
  '--ui-dur-fast': '120ms',
  '--ui-dur-base': '140ms',
  '--ui-dur-slow': '300ms',
  '--ui-ease': 'cubic-bezier(.34,1.56,.64,1)',
  '--ui-ease-mech': 'steps(6,end)',
}

/* --------------------------------------------------------------- 汇总 */

export type LayerBundle = {
  shape: Vars
  depth: Vars
  surface: Vars
  typo: Vars
  motion: Vars
  /** 按钮层（组件变体登记簿）：--ui-btn-{variant}-* 令牌族，主题可选登记 */
  btn?: Vars
  overrides?: Partial<Record<'light' | 'dark', Vars>>
}

export const LAYERS: Record<string, LayerBundle> = {
  old: { shape: oldShape, depth: oldDepth, surface: oldSurface, typo: oldTypo, motion: oldMotion },

  clean: {
    shape: cleanShape,
    depth: cleanDepth,
    surface: cleanSurface,
    typo: cleanTypo,
    motion: cleanMotion,
    // 深色下黑影要更重才看得见
    overrides: {
      dark: {
        '--ui-e1': '0 1px 2px rgb(0 0 0 / .30)',
        '--ui-e2': '0 2px 6px rgb(0 0 0 / .38)',
        '--ui-e3': '0 8px 20px rgb(0 0 0 / .46)',
        '--ui-e4': '0 16px 36px rgb(0 0 0 / .54)',
        '--ui-e5': '0 26px 56px rgb(0 0 0 / .62)',
      },
    },
  },

  md3: {
    shape: md3Shape,
    depth: md3Depth,
    surface: md3Surface,
    typo: md3Typo,
    motion: md3Motion,
    // MD3 暗色 elevation 按规范提高不透明度
    overrides: {
      dark: {
        '--ui-e1': '0 1px 2px rgb(0 0 0 / .50), 0 1px 3px 1px rgb(0 0 0 / .30)',
        '--ui-e2': '0 1px 2px rgb(0 0 0 / .50), 0 2px 6px 2px rgb(0 0 0 / .30)',
        '--ui-e3': '0 1px 3px rgb(0 0 0 / .50), 0 4px 8px 3px rgb(0 0 0 / .30)',
        '--ui-e4': '0 2px 3px rgb(0 0 0 / .50), 0 6px 10px 4px rgb(0 0 0 / .30)',
        '--ui-e5': '0 4px 4px rgb(0 0 0 / .50), 0 8px 12px 6px rgb(0 0 0 / .30)',
      },
    },
  },

  endfield: {
    shape: endfieldShape,
    depth: endfieldDepth,
    surface: endfieldSurface,
    typo: endfieldTypo,
    motion: endfieldMotion,
    btn: endfieldBtn,
    overrides: {
      /* 深档色彩补丁：白 alpha 线 / 数据青 / 半透明黄晕染（demo 登记簿 A） */
      dark: {
        '--c-line': 'rgb(255 255 255 / .13)',
        '--c-line-strong': 'rgb(255 255 255 / .28)',
        '--c-hint': '#586269',
        '--c-info': '#5fd8ff',
        '--c-mark': 'rgb(255 212 0 / .16)',
        '--c-accent-soft': 'rgb(255 212 0 / .13)',
        '--c-accent-hover': '#ffe463',
        '--c-accent-fg': '#0a0b0c',
      },
      /* 浅档登记簿 B · 工业印刷体：实底、暖灰实线、硬偏移阴影（demo body.light） */
      light: {
        '--ui-panel-alpha': '100%',
        '--ui-blur': '0px',
        '--ui-card-fill': '#ffffff',
        '--ui-e1': '4px 4px 0 rgb(11 11 11 / .09)',
        '--ui-e2': '5px 5px 0 rgb(11 11 11 / .12)',
        '--ui-e3': '6px 6px 0 rgb(11 11 11 / .16)',
        '--ui-e4': '6px 6px 0 rgb(11 11 11 / .2)',
        '--ui-e5': '5px 5px 0 rgb(11 11 11 / .22)',
        '--c-line': '#d8d8d1',
        '--c-line-strong': '#bfbfb6',
        '--c-hint': '#9c9c95',
        '--c-info': '#0a7ea4',
        '--c-mark': 'rgb(255 212 0 / .3)',
        '--c-accent-soft': 'rgb(255 212 0 / .22)',
        '--c-accent-hover': '#d9b400',
        '--c-accent-fg': '#0b0b0b',
        /* 印刷体档导航指示条用黑（深档为青蓝） */
        '--ui-nav-edge': 'var(--c-accent-fg)',
      },
    },
  },

  bluearchive: {
    shape: baShape,
    depth: baDepth,
    surface: baSurface,
    typo: baTypo,
    motion: baMotion,
  },
}

/* -------------------------------------------- Endfield 罗德岛色风格变体 */

/**
 * 罗德岛档（主题配置窗口）：点缀色工业黄 #FFD400 → 罗德岛蓝 #38C2FF。
 * 与描边钮的青蓝（endfieldSurface --c-cyan）同值：罗德岛档下主行动与
 * 第二主行动并轨为同一支蓝色系。种子注入由 derive.ts 完成（accent 家族
 * 其余令牌自动现混派生）；此处只登记各层里黄色硬编码位的蓝档等价物，
 * 合并序在 overrides[scheme] 之后。e2/e3 光晕不分档（浅档 e2/e3 已被
 * 印刷体硬偏移影覆写）。
 */
export const ENDFIELD_RHODES: Vars = {
  /* 深档唯一的"浮起"语汇：黄光晕 → 蓝光晕 */
  '--ui-e2': '0 0 14px rgb(56 194 255 / .42)',
  '--ui-e3': '0 0 20px rgb(56 194 255 / .5)',
}

export const ENDFIELD_RHODES_SCHEME: Partial<Record<'light' | 'dark', Vars>> = {
  dark: {
    '--c-mark': 'rgb(56 194 255 / .16)',
    '--c-accent-soft': 'rgb(56 194 255 / .13)',
    /* hover 提亮（对应黄档 #ffe463） */
    '--c-accent-hover': '#5ac9ff',
  },
  light: {
    '--c-mark': 'rgb(56 194 255 / .3)',
    '--c-accent-soft': 'rgb(56 194 255 / .22)',
    /* hover 压暗（对应黄档 #d9b400） */
    '--c-accent-hover': '#30a3d6',
  },
}

/**
 * 终末地工作台窗口样式（主题配置窗口档位）：全局 FloatWindow 换装。
 * 深炭标题栏 #1C1B1D + 纯白内容区 —— 工程软件式双色窗口，深浅档统一。
 *   --ui-win-title-* / --ui-win-bg  由 FloatWindow 直接消费（令牌族存在
 *   即换装，其他主题无此族 → 维持现状）；
 *   --ui-win-c-*  是窗口内浅色印刷环境的 --c-* 源（纯白底上深字 / 暖灰实线，
 *   由 index.css 的 .ui-win-workbench 作用域映射回 --c-*）。
 */
export const ENDFIELD_WINDOW_WORKBENCH: Vars = {
  '--ui-win-title-bg': '#1C1B1D',
  '--ui-win-title-fg': '#f4f4f2',
  '--ui-win-bg': '#ffffff',
  /* 窗口内印刷环境：灰阶与实线（取工业印刷体浅档语言） */
  '--ui-win-c-bg': '#ffffff',
  '--ui-win-c-fg': '#26262b',
  '--ui-win-c-fg-2': '#3f3f45',
  '--ui-win-c-muted': '#5d5d64',
  '--ui-win-c-faint': '#84848b',
  '--ui-win-c-dim': '#9d9da4',
  '--ui-win-c-line': '#e3e3df',
  '--ui-win-c-line-strong': '#c8c8c2',
}

/* --------------------------------------------------- 主题元信息（面板用） */

export const THEME_META: Record<string, { label: string; labelEn: string; desc: string; backdrop: 'none' | 'grid' | 'holo' | 'pop' }> = {
  old: {
    label: 'Mono',
    labelEn: 'OLD / DEFAULT',
    desc: '现有默认。近纯黑墨、中性灰线、无彩点缀，极简克制的排版优先。',
    backdrop: 'none',
  },
  clean: {
    label: 'Clean',
    labelEn: 'TAILWIND CLEAN',
    desc: '现代 SaaS。石板灰墨 + 单一品牌蓝，多层克制柔影，信息密度友好。',
    backdrop: 'none',
  },
  md3: {
    label: 'Material 3',
    labelEn: 'GOOGLE MD3',
    desc: 'Material You。紫罗兰色调、大圆角、官方五级 elevation、medium 字重。',
    backdrop: 'none',
  },
  endfield: {
    label: '终末地',
    labelEn: 'ENDFIELD',
    desc: '后启示录工业 HUD。深档＝碳黑战术玻璃 + 工业黄光晕；浅档＝工业印刷体（暖纸 + 硬偏移阴影）。',
    backdrop: 'grid',
  },
  bluearchive: {
    label: '蔚蓝档案',
    labelEn: 'BLUE ARCHIVE',
    desc: '数字街头 Y2K。高饱和档案青、全胶囊、粗斜体标题、蓝调柔影。',
    backdrop: 'pop',
  },
}
