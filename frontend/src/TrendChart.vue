<script setup lang="ts">
import { init, use, type ComposeOption, type EChartsType } from 'echarts/core'
import { LineChart, type LineSeriesOption } from 'echarts/charts'
import { GridComponent, MarkLineComponent, TooltipComponent, type GridComponentOption, type MarkLineComponentOption,
  type TooltipComponentOption } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import type { History, HourlyHistory } from './api'

/** 行情趋势图保留真实时间轴，只用虚线跨过有盘时依据的休市缺口。 */
const props = defineProps<{ history: History | HourlyHistory; range: '24h' | 7 | 30; color: string }>()
use([LineChart, GridComponent, MarkLineComponent, TooltipComponent, CanvasRenderer])

// 生成带日期的北京时间整点标签，跨午夜仍能从提示中确认日期。
const beijingHour = new Intl.DateTimeFormat('sv-SE', {
  timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit',
  hour: '2-digit', minute: '2-digit', hourCycle: 'h23',
})
// 休市连线提示中的价差保留报价精度，并显示涨跌方向。
const priceChange = new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 4, signDisplay: 'exceptZero' })

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
  // 只接受服务端提供的休市采样整点，日线、旧接口和其他缺报按正常断线处理。
  const closedHours = new Set(hourly && 'closedSamplingHours' in props.history
    ? props.history.closedSamplingHours?.map(hour => Date.parse(hour)) : [])
  const labels: string[] = []
  const values: (number | null)[] = []
  // 每条虚线只使用两条真实报价作为端点，线中间的小时仍为空值。
  const bridges: NonNullable<MarkLineComponentOption['data']> = []
  // 当前缺口的左侧报价，以及中间所有小时是否都可确认为休市。
  let previous: { index: number; label: string; price: number } | undefined
  let onlyClosedHours = true
  const start = Date.parse(from)
  const last = Date.parse(through)
  for (let cursor = start; cursor <= last && labels.length < limit; cursor += step) {
    const index = labels.length
    const label = hourly ? beijingHour.format(cursor) : new Date(cursor).toISOString().slice(0, 10)
    const price = prices.get(cursor) ?? null
    labels.push(label)
    values.push(price)
    if (price === null) {
      onlyClosedHours = onlyClosedHours && closedHours.has(cursor)
      continue
    }
    // 缺口里有任一小时无法确认为休市时，整段保留断点。
    if (previous && index > previous.index + 1 && onlyClosedHours) {
      const change = priceChange.format(price - previous.price)
      bridges.push([
        { coord: [previous.label, previous.price], name: `休市采样时段无报价\n${previous.label} → ${label}\n报价差 ${change} ${unit}` },
        { coord: [label, price] },
      ])
    }
    previous = { index, label, price }
    onlyClosedHours = true
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
      markLine: {
        data: bridges, symbol: 'none', label: { show: false },
        lineStyle: { type: 'dashed', color: props.color, width: 2, opacity: 0.8 },
        tooltip: { trigger: 'item', formatter: '{b}' },
      },
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
       :aria-label="`最近 ${range === '24h' ? '24 小时' : range + ' 日'}行情图，${'closedSamplingHours' in history && history.closedSamplingHours?.length ? '常规休市缺口可用虚线连接' : '无报价时段保留空白'}`"></div>
</template>
