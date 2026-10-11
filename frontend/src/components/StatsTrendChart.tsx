import { useEffect, useMemo, useRef } from 'react'
import * as echarts from 'echarts/core'
import type { ComposeOption } from 'echarts/core'
import { LineChart, type LineSeriesOption } from 'echarts/charts'
import {
  GridComponent,
  LegendComponent,
  TooltipComponent,
  type GridComponentOption,
  type LegendComponentOption,
  type TooltipComponentOption,
} from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import { useThemeState } from '../theme/store'

/**
 * 统计页的按日趋势折线图（ECharts line 系列）：输入 / 输出 token 两条曲线。
 * 按需注册以减少体积；颜色取主题令牌的实际 hex（canvas 不认 CSS 变量），
 * 主题切换时 vars 变化 → option 重建 → 实时跟随。
 */

echarts.use([LineChart, GridComponent, TooltipComponent, LegendComponent, CanvasRenderer])

type ECOption = ComposeOption<
  LineSeriesOption | GridComponentOption | TooltipComponentOption | LegendComponentOption
>

type Props = {
  /** X 轴刻度（已补零，如 09-17） */
  labels: string[]
  prompt: number[]
  completion: number[]
}

/** 纵轴刻度：过万折算为「万」，避免长数字挤占绘图区 */
function axisLabel(v: number): string {
  return v >= 10000 ? `${(v / 10000).toFixed(v >= 100000 ? 0 : 1)}万` : String(v)
}

export default function StatsTrendChart({ labels, prompt, completion }: Props) {
  const boxRef = useRef<HTMLDivElement>(null)
  const chartRef = useRef<echarts.ECharts | null>(null)
  const vars = useThemeState().vars

  const option = useMemo<ECOption>(() => {
    const c = {
      bg: vars['--c-bg'] ?? '#ffffff',
      fg: vars['--c-fg'] ?? '#000000',
      muted: vars['--c-muted'] ?? '#666666',
      faint: vars['--c-faint'] ?? '#999999',
      line: vars['--c-line'] ?? '#cccccc',
      accent: vars['--c-accent'] ?? '#4b74ff',
      hint: vars['--c-hint'] ?? '#8aa0ff',
    }
    /** 一条序列：细线 + 极淡填充，颜色由调用方给 */
    const series = (name: string, color: string, data: number[]): LineSeriesOption => ({
      name,
      type: 'line',
      smooth: true,
      symbol: 'none',
      lineStyle: { color, width: 2 },
      itemStyle: { color },
      areaStyle: { color, opacity: 0.08 },
      data,
    })
    return {
      backgroundColor: 'transparent',
      grid: { left: 8, right: 12, top: 30, bottom: 4, containLabel: true },
      tooltip: {
        trigger: 'axis',
        backgroundColor: c.bg,
        borderColor: c.line,
        textStyle: { color: c.fg, fontSize: 13 },
        valueFormatter: (v) => Number(v).toLocaleString('zh-CN'),
      },
      legend: {
        top: 0,
        right: 0,
        icon: 'roundRect',
        itemWidth: 10,
        itemHeight: 3,
        textStyle: { color: c.muted, fontSize: 12 },
      },
      xAxis: {
        type: 'category',
        boundaryGap: false,
        data: labels,
        axisLine: { lineStyle: { color: c.line } },
        axisTick: { show: false },
        axisLabel: { color: c.faint, fontSize: 12, hideOverlap: true },
      },
      yAxis: {
        type: 'value',
        axisLine: { show: false },
        axisTick: { show: false },
        splitLine: { lineStyle: { color: c.line, opacity: 0.6 } },
        axisLabel: { color: c.faint, fontSize: 12, formatter: axisLabel },
      },
      series: [series('输入', c.hint, prompt), series('输出', c.accent, completion)],
    }
  }, [labels, prompt, completion, vars])

  useEffect(() => {
    const box = boxRef.current
    if (!box) return
    const chart = echarts.init(box)
    chartRef.current = chart
    const ro = new ResizeObserver(() => chart.resize())
    ro.observe(box)
    return () => {
      ro.disconnect()
      chart.dispose()
      chartRef.current = null
    }
  }, [])

  useEffect(() => {
    chartRef.current?.setOption(option)
  }, [option])

  return <div ref={boxRef} className="h-full w-full" />
}
