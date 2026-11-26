<script setup lang="ts">
import { init, use, type ComposeOption, type EChartsType } from 'echarts/core'
import { LineChart, type LineSeriesOption } from 'echarts/charts'
import { GridComponent, MarkLineComponent, TooltipComponent, type GridComponentOption,
  type TooltipComponentOption } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import type { History, HourlyHistory } from './api'
import { priceGapMarkLine } from './priceGapMarkLine'

/** 行情趋势图保留真实时间轴，用虚线连接缺口两侧的报价。 */
const props = defineProps<{ history: History | HourlyHistory; range: '24h' | 7 | 30; color: string }>()
use([LineChart, GridComponent, MarkLineComponent, TooltipComponent, CanvasRenderer])

// 生成带日期的北京时间整点标签，跨午夜仍能从提示中确认日期。
const beijingHour = new Intl.DateTimeFormat('sv-SE', {
  timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit',
  hour: '2-digit', minute: '2-digit', hourCycle: 'h23',
})

// 图表实例与容器尺寸监听跟随当前组件生命周期。
const host = ref<HTMLDivElement>()
let chart: EChartsType | undefined
let resize: ResizeObserver | undefined

/** 用 UTC 推进小时或日期桶，避免浏览器所在时区改变采样间隔。 */
function paint() {
  const { from, through, points, unit } = props.history
  const hourly = props.range === '24h'
  // 横轴步长以毫秒计，键统一使用 UTC 时间戳以关联服务端的报价。
  const step = hourly ? 3_600_000 : 86_400_000
  const limit = hourly ? 24 : props.range
  const prices = new Map(points.map(point => [Date.parse('hour' in point ? point.hour : point.marketDate ?? ''), point.price]))
  // 将服务端标注的休市整点对齐到横轴标签，虚线提示可以注明已知休市。
  const closedLabels = hourly && 'closedSamplingHours' in props.history
    ? new Set(props.history.closedSamplingHours?.map(Date.parse).filter(Number.isFinite).map(hour => beijingHour.format(hour)))
    : undefined
  const labels: string[] = []
  // 保留每个采样桶的报价或空值，确保缺失时段没有实线和数据点。
  const values: (number | null)[] = []
  const start = Date.parse(from)
  const last = Date.parse(through)
  for (let cursor = start; cursor <= last && labels.length < limit; cursor += step) {
    const label = hourly ? beijingHour.format(cursor) : new Date(cursor).toISOString().slice(0, 10)
    labels.push(label)
    values.push(prices.get(cursor) ?? null)
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
      type: 'category', data: labels, boundaryGap: true,
      axisLabel: { color: '#596879', formatter: (value: string) => value.slice(hourly ? 11 : 5) },
      axisTick: { show: false }, axisLine: { lineStyle: { color: '#dbe2ea' } },
    },
    yAxis: {
      type: 'value', scale: true, splitNumber: 4,
      axisLabel: { color: '#596879' },
      splitLine: { lineStyle: { color: '#e6ecf1', type: 'dashed' } },
    },
    series: [{
      name: '报价', type: 'line', data: values, connectNulls: false,
      symbolSize: 5, showSymbol: true, lineStyle: { width: 2.5, color: props.color },
      itemStyle: { color: props.color }, areaStyle: { color: props.color, opacity: 0.09 },
      markLine: priceGapMarkLine(labels, values, props.color, unit, closedLabels),
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
watch(() => [props.history, props.range, props.color], paint)
onBeforeUnmount(() => {
  resize?.disconnect()
  chart?.dispose()
})
</script>

<template>
  <div ref="host" class="chart" role="img"
       :aria-label="`最近 ${range === '24h' ? '24 小时' : range + ' 日'}行情图，虚线连接缺口两侧的报价`"></div>
</template>
