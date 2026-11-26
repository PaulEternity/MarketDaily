<script setup lang="ts">
import { init, use, type EChartsType } from 'echarts/core'
import { BarChart, CandlestickChart, LineChart } from 'echarts/charts'
import { AxisPointerComponent, GridComponent, MarkLineComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import type { EChartsOption } from 'echarts'
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import type { GoldBar, GoldSignals } from './api'
import { priceGapMarkLine } from './priceGapMarkLine'

/** 分时按同一 UTC 小时对齐，日图按同一交易日对照价格和成交量。 */
const props = defineProps<{
  spot: GoldSignals
  volume: GoldBar[]
  volumeLabel: string
  daily?: boolean
}>()
use([BarChart, CandlestickChart, LineChart, AxisPointerComponent, GridComponent, MarkLineComponent, TooltipComponent, CanvasRenderer])

// 收盘价的实线、数据点和缺口虚线共用同一颜色。
const closeColor = '#315b80'

// 图表实例跟随组件生命周期，尺寸变化时适配窄屏。
const host = ref<HTMLDivElement>()
let chart: EChartsType | undefined
let observer: ResizeObserver | undefined
const beijing = new Intl.DateTimeFormat('sv-SE', {
  timeZone: 'Asia/Shanghai', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hourCycle: 'h23',
})

/** 价格和成交量共享时间轴，收盘价缺口用虚线连接。 */
function paint() {
  // UTC 毫秒用作行情对齐键；日成交只按交易日排序，不用时间戳猜归属。
  const prices = new Map(props.spot.hourly.map(bar => [Date.parse(bar.startTime), bar]))
  const volume = new Map(props.volume.map(bar => [Date.parse(bar.startTime), bar.volume]))
  const hours = Array.from({ length: 72 }, (_, i) => Date.parse(props.spot.from) + i * 3_600_000)
  // 日图只展开首末交易日之间的自然日，期间缺少的日期由价格和成交量共同留空。
  const dailyByDate = new Map(props.volume.map(bar => [bar.tradingDate, bar]))
  const labels = props.daily ? [] : hours.map(hour => beijing.format(hour))
  if (props.daily) {
    const firstDay = Date.parse(props.volume[0]?.tradingDate ?? '')
    const lastDay = Date.parse(props.volume.at(-1)?.tradingDate ?? '')
    for (let day = firstDay; day <= lastDay; day += 86_400_000) {
      labels.push(new Date(day).toISOString().slice(0, 10))
    }
  }
  // 收盘价与横轴逐项对齐，空值供虚线定位两侧的真实报价。
  const closes = props.daily ? labels.map(date => dailyByDate.get(date)?.closePrice ?? null)
    : hours.map(hour => prices.get(hour)?.closePrice ?? null)
  const pricesVisible = closes.some(price => price != null)
  const volumeVisible = props.volume.some(bar => bar.volume != null)
  const split = pricesVisible && volumeVisible
  // 双图固定相同的左右边距，避免两个纵轴的刻度宽度造成小时位置错开。
  const grid = split ? { left: 62, right: 16, outerBoundsMode: 'none' as const }
    : { left: 8, right: 12, outerBoundsMode: 'same' as const }
  const axis = { type: 'category' as const, data: labels, axisLabel: { color: '#596879', hideOverlap: true } }
  const option: EChartsOption = {
    animation: false,
    grid: split ? [{ ...grid, top: 14, height: '57%' }, { ...grid, top: '68%', bottom: 28 }]
      : [{ ...grid, top: 12, bottom: 8 }],
    tooltip: { trigger: 'axis', renderMode: 'richText', axisPointer: { type: 'line', snap: false } },
    // 鼠标位于任一面板时，都按同一日期或小时标记另一面板。
    axisPointer: { link: split ? [{ xAxisIndex: [0, 1] }] : [] },
    xAxis: split ? [{ ...axis, gridIndex: 0, axisLabel: { show: false } }, { ...axis, gridIndex: 1 }] : [axis],
    yAxis: split ? [{ type: 'value', scale: true, gridIndex: 0 }, { type: 'value', gridIndex: 1 }]
      : [{ type: 'value', scale: pricesVisible }],
    series: [
      ...(!props.daily && pricesVisible ? [{
        type: 'candlestick' as const, name: '现货价格 ' + props.spot.unit,
        // ECharts 蜡烛图顺序为开、收、低、高。
        data: hours.map(hour => {
          const bar = prices.get(hour)
          return bar ? [bar.openPrice, bar.closePrice, bar.lowPrice, bar.highPrice] : ['-', '-', '-', '-']
        }),
      }] : []),
      ...(pricesVisible ? [{
        type: 'line' as const, name: props.daily ? '日收盘价（元/克）' : '小时收盘价 ' + props.spot.unit,
        data: closes,
        connectNulls: false, showSymbol: !!props.daily, symbolSize: 6, z: 4,
        lineStyle: { color: closeColor, width: 1.8 }, itemStyle: { color: closeColor },
        markLine: priceGapMarkLine(labels, closes, closeColor, props.daily ? '元/克' : props.spot.unit),
      }] : []),
      ...(volumeVisible ? [{
        type: 'bar' as const, name: props.volumeLabel,
        xAxisIndex: split ? 1 : 0, yAxisIndex: split ? 1 : 0, itemStyle: { color: '#8b692e' },
        data: props.daily ? labels.map(date => dailyByDate.get(date)?.volume ?? null) : hours.map(hour => volume.get(hour) ?? null),
      }] : []),
    ],
  }
  chart?.setOption(option, { notMerge: true })
}

onMounted(() => {
  if (!host.value) return
  chart = init(host.value)
  observer = new ResizeObserver(() => chart?.resize())
  observer.observe(host.value)
  paint()
})
watch(() => [props.spot, props.volume, props.daily], paint)
onBeforeUnmount(() => {
  observer?.disconnect()
  chart?.dispose()
})
</script>

<template>
  <div ref="host" class="chart"
       style="height: 270px"
       role="img" :aria-label="`${daily ? 'Au99.99 日收盘价和同日成交量对照' : '近 72 小时黄金 K 线、小时收盘价与成交量对照'}，虚线连接缺口两侧的收盘价`"></div>
</template>
