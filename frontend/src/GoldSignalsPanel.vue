<script setup lang="ts">
import { computed, defineAsyncComponent, ref } from 'vue'
import { useRequest, type GoldSignals } from './api'

/** 黄金走势面板，保留现货与期货的来源、时间和成交量口径。 */
const props = defineProps<{ refresh: number }>()
const GoldSignalChart = defineAsyncComponent(() => import('./GoldSignalChart.vue'))
// 面板选择只影响展示，刷新时一次读取三种品种。
const selected = ref<'XAUUSD' | 'AU9999'>('XAUUSD')
const { data, pending, error } = useRequest<GoldSignals[]>('/api/markets/gold-signals', () => props.refresh)
const spot = computed(() => data.value?.find(item => item.symbol === selected.value))
const hourlyVolume = computed(() => selected.value === 'AU9999' ? spot.value
  : data.value?.find(item => item.symbol === 'GC'))
const hourlyLabel = computed(() => selected.value === 'AU9999'
  ? 'Au99.99 小时成交量（千克，单向）' : 'COMEX GC 期货小时成交量（手，单向）')
const dailyLabel = 'Au99.99 日成交量（千克，双向）'
// 最新一根有小时成交量的记录用于展示买卖方向，避免用更旧的方向掩盖当前缺失。
const latestVolume = computed(() => hourlyVolume.value?.hourly.filter(bar => bar.volume != null).at(-1))
const amount = new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 4 })
const beijing = new Intl.DateTimeFormat('zh-CN', {
  timeZone: 'Asia/Shanghai', month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit', hourCycle: 'h23',
})
</script>

<template>
  <section id="gold-signals" class="panel gold-signals-panel" aria-labelledby="gold-signals-title" :aria-busy="pending">
    <div class="panel-heading">
      <div><h2 id="gold-signals-title">黄金走势信号</h2><p>{{ selected === 'AU9999' ? '按交易日对照 · 北京时间' : '近 72 小时 · 北京时间' }}</p></div>
      <div class="segmented" role="group" aria-label="黄金品种">
        <button type="button" :aria-pressed="selected === 'XAUUSD'" @click="selected = 'XAUUSD'">国际现货</button>
        <button type="button" :aria-pressed="selected === 'AU9999'" @click="selected = 'AU9999'">国内现货</button>
      </div>
    </div>
    <p v-if="pending || error" class="chart-note" role="status">{{ pending ? '正在读取黄金 K 线' : '黄金走势读取失败，请刷新重试。' }}</p>
    <template v-else-if="spot">
      <template v-if="selected === 'AU9999'">
        <h3 class="gold-chart-title">Au99.99 日价格与成交量</h3>
        <template v-if="spot.daily.length">
          <p class="chart-note">上方蓝线是收盘价（元/克），棕柱是同日成交量（千克，交易所双向口径）。沿同一个日期上下看，就能对照价格和成交量。</p>
          <GoldSignalChart :spot="spot" :volume="spot.daily" :volume-label="dailyLabel" daily />
        </template>
        <p v-else class="chart-note">暂无 Au99.99 日行情。</p>
        <h3 class="gold-chart-title">Au99.99 小时走势（近 72 小时）</h3>
      </template>
      <template v-if="selected !== 'AU9999' || spot.hourly.length || latestVolume">
        <dl v-if="spot.breakouts.comparedHours > 0" class="gold-breakouts">
          <div><dt>突破前高</dt><dd>{{ spot.breakouts.highBreaks }} 小时 <small>其中 {{ spot.breakouts.highCloses }} 小时收在上方</small></dd></div>
          <div><dt>跌破前低</dt><dd>{{ spot.breakouts.lowBreaks }} 小时 <small>其中 {{ spot.breakouts.lowCloses }} 小时收在下方</small></dd></div>
        </dl>
        <p class="chart-note">
          {{ spot.hourly.length ? '现货小时线：' + spot.hourly[0]?.source : '暂无现货小时 K 线' }}。
          {{ spot.breakouts.comparedHours > 0
            ? '共 ' + spot.hourly.length + ' 根小时线，其中 ' + spot.breakouts.comparedHours + ' 根有可用的上一交易日日线。'
            : spot.hourly.length ? '缺少上一交易日日线，暂无法统计突破。' : '' }}
        </p>
        <GoldSignalChart v-if="spot.hourly.length || latestVolume" :spot="spot"
                         :volume="hourlyVolume?.hourly ?? []" :volume-label="hourlyLabel" />
        <p class="chart-note" v-if="latestVolume">{{ hourlyLabel }} · {{ latestVolume.source }} · 最新 {{ beijing.format(new Date(latestVolume.startTime)) }} {{ amount.format(latestVolume.volume!) }}</p>
        <p class="chart-note" v-else>暂无{{ selected === 'XAUUSD' ? ' COMEX GC 期货' : ' Au99.99' }}分时成交量。</p>
        <dl v-if="latestVolume?.buyVolume != null && latestVolume.sellVolume != null" class="gold-breakouts">
          <div><dt>主动买入 / 卖出</dt><dd>{{ amount.format(latestVolume.buyVolume) }} / {{ amount.format(latestVolume.sellVolume) }}</dd></div>
          <div><dt>主动买卖差 / 方向未知</dt><dd>{{ amount.format(latestVolume.netVolume!) }} / {{ amount.format(latestVolume.unknownVolume!) }}</dd></div>
        </dl>
        <p class="chart-note" v-else-if="latestVolume">该小时暂无主动成交方向。</p>
      </template>
      <p v-else class="chart-note">暂无 Au99.99 的小时价格和成交量。</p>
    </template>
  </section>
</template>

<style scoped>
.gold-signals-panel { margin-top: 16px; }
.gold-chart-title { margin: 14px 0 4px; font-size: 15px; }
.gold-breakouts {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(min(100%, 210px), 1fr));
  margin: 12px 0 0;
  gap: 10px 22px;
  padding: 9px 0;
  border-block: 1px solid var(--border);
}
.gold-breakouts dt { font-size: 12px; color: var(--muted); }
.gold-breakouts dd { margin: 3px 0 0; color: var(--ink); font-weight: 700; font-variant-numeric: tabular-nums; }
.gold-breakouts small { font-weight: 400; color: var(--muted); margin-inline-start: 6px; }
</style>
