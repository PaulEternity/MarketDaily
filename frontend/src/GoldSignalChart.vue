<script setup lang="ts">
import { init, use, type EChartsType } from 'echarts/core'
import { BarChart, CandlestickChart } from 'echarts/charts'
import { GridComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import type { EChartsOption } from 'echarts'
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import type { GoldBar, GoldSignals } from './api'

/** 按相同 UTC 小时对齐现货价格与分时成交量，在界面显示北京时间。 */
const props = defineProps<{
  spot: GoldSignals
  volume: GoldBar[]
  volumeLabel: string
  daily?: boolean
}>()
use([BarChart, CandlestickChart, GridComponent, TooltipComponent, CanvasRenderer])

// 图表实例跟随组件生命周期，尺寸变化时适配窄屏。
const host = ref<HTMLDivElement>()
let chart: EChartsType | undefined
let observer: ResizeObserver | undefined
const beijing = new Intl.DateTimeFormat('sv-SE', {
  timeZone: 'Asia/Shanghai', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hourCycle: 'h23',
})

/** 保留缺少报价或成交量的时段，分开绘制价格和成交量坐标。 */
function paint() {
  // UTC 毫秒用作行情对齐键；日成交只按交易日排序，不用时间戳猜归属。
  const prices = new Map(props.spot.hourly.map(bar => [Date.parse(bar.startTime), bar]))
  const volume = new Map(props.volume.map(bar => [Date.parse(bar.startTime), bar.volume]))
  const hours = Array.from({ length: 72 }, (_, i) => Date.parse(props.spot.from) + i * 3_600_000)
  const pricesVisible = !props.daily && prices.size > 0
  const volumeVisible = props.volume.some(bar => bar.volume != null)
  const split = pricesVisible && volumeVisible
  const labels = props.daily ? props.volume.map(bar => bar.tradingDate) : hours.map(hour => beijing.format(hour))
  const grid = { left: 8, right: 12, outerBoundsMode: 'same' as const }
  const axis = { type: 'category' as const, data: labels, axisLabel: { color: '#596879', hideOverlap: true } }
  const option: EChartsOption = {
    animation: false,
    grid: split ? [{ ...grid, top: 14, height: '57%' }, { ...grid, top: '68%', bottom: 8 }]
      : [{ ...grid, top: 12, bottom: 8 }],
    tooltip: { trigger: 'axis', renderMode: 'richText' },
    xAxis: split ? [{ ...axis, gridIndex: 0, axisLabel: { show: false } }, { ...axis, gridIndex: 1 }] : [axis],
    yAxis: split ? [{ type: 'value', scale: true, gridIndex: 0 }, { type: 'value', gridIndex: 1 }]
      : [{ type: 'value', scale: pricesVisible }],
    series: [
      ...(pricesVisible ? [{
        type: 'candlestick' as const, name: '现货价格 ' + props.spot.unit,
        // ECharts 蜡烛图顺序为开、收、低、高。
        data: hours.map(hour => {
          const bar = prices.get(hour)
          return bar ? [bar.openPrice, bar.closePrice, bar.lowPrice, bar.highPrice] : ['-', '-', '-', '-']
        }),
      }] : []),
      ...(volumeVisible ? [{
        type: 'bar' as const, name: props.volumeLabel,
        xAxisIndex: split ? 1 : 0, yAxisIndex: split ? 1 : 0, itemStyle: { color: '#8b692e' },
        data: props.daily ? props.volume.map(bar => bar.volume) : hours.map(hour => volume.get(hour) ?? null),
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
       :style="{ height: daily ? '165px' : '270px' }"
       role="img" :aria-label="daily ? volumeLabel : '近 72 小时黄金 K 线与已取得的成交量'"></div>
</template>
