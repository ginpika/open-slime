# 鼠标拖拽重排（pointer events）

**结论：应用内的列表拖拽排序，一律用 pointer 事件自己实现，不要用 HTML5 拖放**
（`draggable` / `dragstart` / `dragover` / `drop` / `dragend`）。

例外只有一个：接收**操作系统拖进来的文件**（上传区）——那只能用 HTML5 拖放，本文不涉及。

> 来源：`潜意识 · 提示词 & 上下文工程`页的「长条重排」踩坑记录。HTML5 拖放版本连续两轮都出现"抓起后放开没反应、页面被按住、点击全部失效"，改用 pointer 事件后一次通过。

## 为什么 HTML5 DnD 不可靠

| 症状 | 真实原因 |
|---|---|
| 抓起后放开没反应，整个页面像被按住，点击全部失效，只能按 Esc | 某次 `dragover` 没调 `preventDefault()`，浏览器不承认落点 → `drop` 永不触发，拖放会话一直挂着 |
| 拖到一半页面卡死 / 视觉停在拖拽态 | 拖拽过程中 `dragover` 里 `setState` → 重渲染拖拽源 → 浏览器中止这次拖放，而 `dragend` 打在被重建的节点上收不到 → 状态永远清不掉 |
| 在 Firefox 里根本拖不动 | `dragstart` 里没调 `dataTransfer.setData()` |
| 一按就把文字选起来了 | 没做 `select-none`，也没在 `pointerdown` 里 `preventDefault()` |
| 触屏上手势被页面滚动抢走 | 没加 `touch-action: none` |

核心问题：**拖放会话的状态机在浏览器手里**，你无法感知它何时被中止，也无法回收。pointer 事件没有这个问题——按下、移动、松开全在你自己手里。

---

## 可直接粘贴的实现（React + TS）

### 1) hook：`useDragReorder.ts`

```tsx
import { useCallback, useEffect, useRef, useState } from 'react'

type Grabbed = { key: string; keys: string[] }

/**
 * pointer 事件版拖拽重排。
 * @param onCommit 松手且位置确实变了时回调（完整顺序数组），拖拽过程中不回调。
 */
export function useDragReorder(onCommit: (order: string[]) => void) {
  const grabbedRef = useRef<Grabbed | null>(null)
  /* 高频变化的判定一律放 ref，避免拖拽中反复重渲染 */
  const overRef = useRef<string | null>(null)
  /* state 只驱动视觉 */
  const [dragKey, setDragKey] = useState<string | null>(null)
  const [overKey, setOverKey] = useState<string | null>(null)

  const clear = useCallback(() => {
    grabbedRef.current = null
    overRef.current = null
    setDragKey(null)
    setOverKey(null)
  }, [])

  /* 兜底：指针在任何地方抬起 / 被取消都清干净，绝不残留拖拽态 */
  useEffect(() => {
    window.addEventListener('pointerup', clear)
    window.addEventListener('pointercancel', clear)
    return () => {
      window.removeEventListener('pointerup', clear)
      window.removeEventListener('pointercancel', clear)
    }
  }, [clear])

  const grab = (keys: string[], key: string) => {
    grabbedRef.current = { key, keys }
    overRef.current = key
    setDragKey(key)
    setOverKey(key)
  }

  const move = (x: number, y: number) => {
    const g = grabbedRef.current
    if (!g) return
    /* 命中测试：指针下面到底压着哪一项 */
    const el = document.elementFromPoint(x, y)
    const item = el instanceof HTMLElement ? el.closest('[data-drag-key]') : null
    const key = item instanceof HTMLElement ? (item.dataset.dragKey ?? null) : null
    /* 跳过自己，且只认同一次拖拽范围内的合法落点 */
    const next = key && key !== g.key && g.keys.includes(key) ? key : null
    if (overRef.current === next) return
    overRef.current = next
    setOverKey(next)
  }

  const release = () => {
    const g = grabbedRef.current
    const target = overRef.current
    clear()
    if (!g || !target || target === g.key) return
    const order = [...g.keys]
    const from = order.indexOf(g.key)
    const to = order.indexOf(target)
    if (from < 0 || to < 0) return
    order.splice(to, 0, ...order.splice(from, 1))
    onCommit(order)
  }

  return { dragKey, overKey, grab, move, release }
}
```

### 2) 列表项：`DragItem.tsx`

```tsx
import type { ReactNode } from 'react'

export function DragItem({
  itemKey,
  grabbing,
  dropTarget,
  onGrab,
  onMove,
  onRelease,
  children,
}: {
  itemKey: string
  grabbing: boolean
  dropTarget: boolean
  onGrab: () => void
  onMove: (x: number, y: number) => void
  onRelease: () => void
  children: ReactNode
}) {
  return (
    <div
      data-drag-key={itemKey}
      onPointerDown={(e) => {
        /* 按钮/链接/输入框上按下时不进入拖拽，保证它们照常可点 */
        if ((e.target as HTMLElement).closest('button, a, input, textarea')) return
        /* 只认左键 */
        if (e.button !== 0) return
        /* 阻止「按下变选文字」 */
        e.preventDefault()
        /* 捕获指针：鼠标移出这一条也能继续收到 move / up */
        e.currentTarget.setPointerCapture(e.pointerId)
        onGrab()
      }}
      onPointerMove={(e) => {
        if (grabbing) onMove(e.clientX, e.clientY)
      }}
      onPointerUp={() => {
        if (grabbing) onRelease()
      }}
      onPointerCancel={() => {
        if (grabbing) onRelease()
      }}
      className={`select-none touch-none ${
        grabbing ? 'cursor-grabbing opacity-60' : 'cursor-grab'
      } ${dropTarget ? 'ring-2 ring-accent' : ''}`}
    >
      {children}
    </div>
  )
}
```

### 3) 接线

```tsx
const { dragKey, overKey, grab, move, release } = useDragReorder((order) => {
  void saveOrder(order) // 只在松手时发请求，拖拽中一次都不发
})

return (
  <div>
    {items.map((it) => (
      <DragItem
        key={it.key}
        itemKey={it.key}
        grabbing={dragKey === it.key}
        dropTarget={overKey === it.key}
        onGrab={() => grab(items.map((i) => i.key), it.key)}
        onMove={move}
        onRelease={release}
      >
        {it.name}
      </DragItem>
    ))}
  </div>
)
```

---

## 原生 DOM 版本（同一条思路）

```html
<div id="list">
  <div class="item" data-drag-key="a">A</div>
  <div class="item" data-drag-key="b">B</div>
  <div class="item" data-drag-key="c">C</div>
</div>

<style>
  .item { touch-action: none; user-select: none; cursor: grab; }
  .item.dragging { cursor: grabbing; opacity: 0.6; }
  .item.over { outline: 2px solid currentColor; }
</style>

<script>
  const list = document.getElementById('list')
  let grabbed = null

  list.addEventListener('pointerdown', (e) => {
    const item = e.target.closest('[data-drag-key]')
    if (!item || e.target.closest('button, a, input, textarea')) return
    if (e.button !== 0) return
    e.preventDefault()
    item.setPointerCapture(e.pointerId)
    grabbed = {
      key: item.dataset.dragKey,
      keys: [...list.querySelectorAll('[data-drag-key]')].map((n) => n.dataset.dragKey),
    }
    item.classList.add('dragging')
  })

  list.addEventListener('pointermove', (e) => {
    if (!grabbed) return
    const hit = document.elementFromPoint(e.clientX, e.clientY)?.closest('[data-drag-key]')
    list.querySelectorAll('.over').forEach((n) => n.classList.remove('over'))
    if (hit && hit.dataset.dragKey !== grabbed.key) hit.classList.add('over')
  })

  const finish = () => {
    if (!grabbed) return
    const target = list.querySelector('.over')?.dataset.dragKey
    const { key, keys } = grabbed
    list.querySelectorAll('.dragging, .over').forEach((n) => n.classList.remove('dragging', 'over'))
    grabbed = null
    if (!target || target === key) return
    const order = [...keys]
    order.splice(order.indexOf(target), 0, ...order.splice(order.indexOf(key), 1))
    saveOrder(order) // 你的落库
  }
  list.addEventListener('pointerup', finish)
  list.addEventListener('pointercancel', finish)
</script>
```

---

## 可选：让被拖的项跟随光标

不必须。要加就注意 **副本必须 `pointer-events: none`**，否则它自己会挡住 `elementFromPoint` 的命中测试。

```tsx
/* 按下时记下指针在元素内的偏移 */
const rect = el.getBoundingClientRect()
const offset = { x: e.clientX - rect.left, y: e.clientY - rect.top }

/* 造一个 fixed 副本跟着手走 */
const ghost = el.cloneNode(true) as HTMLElement
Object.assign(ghost.style, {
  position: 'fixed',
  left: `${rect.left}px`,
  top: `${rect.top}px`,
  width: `${rect.width}px`,
  pointerEvents: 'none', // 关键：别挡住命中测试
  zIndex: '50',
  opacity: '0.95',
})
document.body.appendChild(ghost)

/* pointermove：用 left/top 跟随；松手时 remove() 掉该副本 */
ghost.style.left = `${x - offset.x}px`
ghost.style.top = `${y - offset.y}px`
```

---

## 反例：别这么写

```tsx
/* ❌ 1. 用 HTML5 拖放，并在拖拽中重渲染拖拽源
   浏览器可能中止这次拖放，dragend 又收不到 → 页面永久卡在拖拽态 */
<div
  draggable
  onDragStart={() => setDragKey(key)}
  onDragOver={(e) => { e.preventDefault(); setOverKey(other) }}  // 每次 dragover 都 setState
  onDrop={() => reorder()}
/>

/* ❌ 2. 用 state 判断「是否正在拖」再决定要不要 preventDefault
   dragstart 里的 setState 还没提交，dragover 读到的还是旧值 → 漏掉 preventDefault
   → 浏览器不承认落点 → drop 永远不会发生 */
onDragOver={(e) => {
  if (!dragActive) return   // 第一次经过时读到的仍是 false
  e.preventDefault()
}}

/* ❌ 3. dragstart 里不设 dataTransfer */
onDragStart={() => setDragKey(key)}                             // Firefox 下根本开始不了拖放
onDragStart={(e) => e.dataTransfer.setData('text/plain', key)}   // ✅

/* ❌ 4. 在 dragover / pointermove 里发请求 */
onDragOver={() => void saveOrder(...)}   // 每秒几十次请求，且顺序还没定

/* ❌ 5. 没排除按钮区域：整条都是热区时，按在「编辑 / 删除」上会变成拖拽 */
if (!(e.target as HTMLElement).closest('button, a, input, textarea')) { /* 才开始拖 */ }  // ✅

/* ❌ 6. 靠某个元素的 onDragEnd 清理状态
   拖拽中它可能被重渲染，回调收不到；必须挂 window 级 pointerup / pointercancel 兜底 */
```

---

## 自测清单

- [ ] 抓起一项 → 移到目标 → 松开：顺序变化，且只发了一次落库请求
- [ ] 抓起一项 → 原地松开：什么都没发生，视觉干净
- [ ] 抓起一项 → 拖到列表外 / 空白处松开：取消，无残留
- [ ] 松开后立刻点击页面其它按钮：**可以正常点**（没有"页面被按住"）
- [ ] 拖到一半按 Esc、或切走窗口再回来：不残留拖拽态
- [ ] 项内的按钮 / 链接 / 输入框：能正常点击与输入，不会误触发拖拽
- [ ] 触屏（或 DevTools 触摸模拟）：能拖，且不会把页面滚动带走
- [ ] Firefox：能拖（pointer 事件方案天然满足；HTML5 DnD 方案需 `setData`）
- [ ] 列表只有 1 项时不出现拖拽热区（无意义）

## 顺序落库的两条约定

- **只在松手时提交**，拖拽中一律不发请求。
- 提交**完整顺序数组**（如 `PUT /api/xxx/order` body `{ order: ["b","a","c"] }`），而不是「把 A 移到 B 之前」这类相对操作——完整顺序幂等、易重放，也不会因前后端状态不一致而错位。
