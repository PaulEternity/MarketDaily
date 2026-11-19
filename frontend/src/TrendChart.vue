<script setup lang="ts">
import { init, use, type ComposeOption, type EChartsType } from 'echarts/core'
import { LineChart, type LineSeriesOption } from 'echarts/charts'
import { GridComponent, TooltipComponent, type GridComponentOption, type TooltipComponentOption } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import type { History } from './api'

/** 行情趋势图，按自然日补齐空位并保留非交易日的数据缺口。 */
const props = defineProps<{ history: History; days: number; color: string }>()
use([LineChart, GridComponent, TooltipComponent, CanvasRenderer])

// 图表实例与容器尺寸监听跟随当前组件生命周期。
const host = ref<HTMLDivElement>()
let chart: EChartsType | undefined
let resize: ResizeObserver | undefined

/** 以 UTC 推进日期字符串，避免浏览器本地时区或夏令时改变横轴。 */
function paint() {
  const { from, through, points, unit } = props.history
  const prices = new Map(points.map(point => [point.marketDate, point.price]))
  const days: string[] = []
  const start = Date.parse(from)
  const last = Date.parse(through)
  for (let cursor = start; cursor <= last && days.length < props.days; cursor += 86_400_000) {
    days.push(new Date(cursor).toISOString().slice(0, 10))
  }

  const option: ComposeOption<LineSeriesOption | TooltipComponentOption | GridComponentOption> = {
    animation: false,
    grid: { left: 8, right: 16, bottom: 10, top: 18, outerBoundsMode: 'same' },
    tooltip: {
      trigger: 'axis',
      renderMode: 'richText',
      valueFormatter: value => value == null || value === '-' ? '暂无报价' : Number(value).toLocaleString('zh-CN', { maximumFractionDigits: 4 }) + ' ' + unit,
    },
    xAxis: {
      type: 'category', data: days, boundaryGap: true,
      axisLabel: { color: '#596879', formatter: (value: string) => value.slice(5) },
      axisTick: { show: false }, axisLine: { lineStyle: { color: '#dbe2ea' } },
    },
    yAxis: {
      type: 'value', scale: true, splitNumber: 4,
      axisLabel: { color: '#596879' },
      splitLine: { lineStyle: { color: '#e6ecf1', type: 'dashed' } },
    },
    series: [{
      name: '报价', type: 'line', data: days.map(day => prices.get(day) ?? null), connectNulls: false,
      symbolSize: 5, showSymbol: true, lineStyle: { width: 2.5, color: props.color },
      itemStyle: { color: props.color }, areaStyle: { color: props.color, opacity: 0.09 },
    }],
  }
  chart?.setOption(option)
}

onMounted(() => {
  if (!host.value) return
  chart = init(host.value)
  resize = new ResizeObserver(() => chart?.resize())
  resize.observe(host.value)
  paint()
})
watch(() => [props.history, props.days, props.color], paint)
onBeforeUnmount(() => {
  resize?.disconnect()
  chart?.dispose()
})
</script>

<template>
  <div ref="host" class="chart" role="img" :aria-label="`最近 ${days} 日行情图，无报价日期保留空白`"></div>
</template>
