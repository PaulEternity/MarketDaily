<script setup lang="ts">
import { computed, defineAsyncComponent, onBeforeUnmount, onMounted, ref } from 'vue'
import GoldSignalsPanel from './GoldSignalsPanel.vue'
import NewsPanel from './NewsPanel.vue'
import ReportPanel from './ReportPanel.vue'
import { useRequest, type CnyReferenceRate, type History, type HourlyHistory, type Latest, type SymbolCode } from './api'

/** 市场工作台。只读接口独立加载，部分来源异常仍可浏览其余模块。 */

// 有历史点时才加载图表，空数据和请求失败状态无需下载 ECharts。
const TrendChart = defineAsyncComponent(() => import('./TrendChart.vue'))

// 资产顺序固定，缺少报价时继续显示对应卡片和数据来源。
const assets = [
  { code: 'XAUUSD', label: '国际现货黄金', symbol: 'XAU', glyph: 'Au', color: '#9a681c', tint: '#f9efd8', source: 'Gold API', unit: 'USD/oz', compare: '较上次日度采样' },
  { code: 'AU9999', label: '国内黄金', symbol: 'Au99.99', glyph: 'Au', color: '#956824', tint: '#f9efd8', source: '上海黄金交易所', unit: 'CNY/g', compare: '较上个交易日收盘' },
  { code: 'XAGUSD', label: '国际现货白银', symbol: 'XAG', glyph: 'Ag', color: '#4d657e', tint: '#e8eef5', source: 'Gold API', unit: 'USD/oz', compare: '较上次日度采样' },
  { code: 'AGTD', label: '国内白银', symbol: 'Ag(T+D)', glyph: 'Ag', color: '#4d657e', tint: '#e8eef5', source: '上海黄金交易所', unit: 'CNY/kg', compare: '较上个交易日收盘' },
  { code: 'NASDAQCOM', label: '纳斯达克综合指数', symbol: 'IXIC', glyph: 'N', color: '#3956b4', tint: '#e9edfd', source: 'FRED', unit: '点', compare: '较上个交易日收盘' },
  { code: 'BTCUSDT', label: '比特币', symbol: 'BTC/USDT', glyph: '₿', color: '#a14d15', tint: '#fbebdc', source: '币安现货', unit: 'USDT', compare: '24 小时涨跌' },
] as const

const selectedAsset = ref<SymbolCode>('XAUUSD')
// 保留用户选择的趋势窗口。仅有日行情的品种自动回到 7 日视图。
const trendRange = ref<'24h' | 7 | 30>(7)
// 这些标的包含独立的小时报价序列，用于决定是否显示 24 小时选项。
const intradayAssets = new Set<SymbolCode>(['XAUUSD', 'AU9999', 'XAGUSD', 'AGTD', 'BTCUSDT'])
// 国内金银的延时源只提供最新价格，时间轴按采集时刻归档。
const isDelayedMetal = computed(() => selectedAsset.value === 'AU9999' || selectedAsset.value === 'AGTD')
const canShowHourly = computed(() => intradayAssets.has(selectedAsset.value))
const activeRange = computed(() => trendRange.value === '24h' && !canShowHourly.value ? 7 : trendRange.value)
// 记录新闻面板的加载状态，用于整页刷新按钮。
const loadingNews = ref(true)
const anchor = ref(new Date())
const refreshVersion = ref(0)

// 滑动边界由容器真实宽度和滚动位置计算，窗口变化时同步更新。
const quoteRail = ref<HTMLDivElement>()
const canScrollLeft = ref(false)
const canScrollRight = ref(false)
let quoteResize: ResizeObserver | undefined

/** 判断左右滑动按钮是否还有可滚动内容。 */
function updateQuoteRail() {
  const rail = quoteRail.value
  if (!rail) return
  canScrollLeft.value = rail.scrollLeft > 1
  canScrollRight.value = rail.scrollLeft + rail.clientWidth < rail.scrollWidth - 1
}

/** 每次滑过一张行情卡片，保留触屏原生滑动。 */
function scrollQuotes(direction: -1 | 1) {
  const rail = quoteRail.value
  const card = rail?.firstElementChild?.getBoundingClientRect()
  if (!rail || !card) return
  // 卡片间距参与单次滚动距离，单位是 CSS 像素。
  const gap = parseFloat(getComputedStyle(rail).columnGap) || 0
  const reducedMotion = matchMedia('(prefers-reduced-motion: reduce)').matches
  rail.scrollBy({ left: direction * (card.width + gap), behavior: reducedMotion ? 'instant' : 'smooth' })
}

onMounted(() => {
  if (!quoteRail.value) return
  quoteResize = new ResizeObserver(updateQuoteRail)
  quoteResize.observe(quoteRail.value)
  updateQuoteRail()
})
onBeforeUnmount(() => quoteResize?.disconnect())

const asset = computed(() => assets.find(item => item.code === selectedAsset.value) ?? assets[0])
const { data: latest, pending: loadingQuotes, error: quoteError } = useRequest<Latest[]>('/api/markets/latest', refreshVersion)
// 汇率单独读取，失败只影响人民币参考价，原币报价仍按行情接口渲染。
const { data: referenceRate, error: rateError } = useRequest<CnyReferenceRate>('/api/markets/exchange-rate', refreshVersion)
const { data: history, pending: loadingHistory, error: historyError } = useRequest<History | HourlyHistory>(
  () => `/api/markets/${selectedAsset.value}/${activeRange.value === '24h' ? 'hourly' : 'history?days=' + activeRange.value}`, refreshVersion)

const quotes = computed(() => new Map(latest.value?.map(item => [item.symbol, item])))
const cards = computed(() => assets.map(item => {
  const data = quotes.value.get(item.code)
  return { ...item, data, cny: cnyEquivalent(item.code, data?.quote?.price) }
}))
const availableCount = computed(() => cards.value.filter(item => item.data?.quote?.price != null).length)
const newestQuote = computed(() => latest.value?.reduce<string | null>((last, item) => {
  const candidate = item.quote?.collectedAt
  return candidate && (!last || Date.parse(candidate) > Date.parse(last)) ? candidate : last
}, null))
const today = new Intl.DateTimeFormat('zh-CN', { dateStyle: 'full', timeZone: 'Asia/Shanghai' })
const timestamp = new Intl.DateTimeFormat('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false, timeZone: 'Asia/Shanghai' })
const price = new Intl.NumberFormat('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
const percent = new Intl.NumberFormat('zh-CN', { signDisplay: 'exceptZero', minimumFractionDigits: 2, maximumFractionDigits: 2 })
const refreshing = computed(() => loadingQuotes.value || loadingHistory.value || loadingNews.value)

/** 用当前参考汇率计算国际金银及比特币的人民币展示价。 */
function cnyEquivalent(symbol: SymbolCode, originalPrice?: number | null) {
  const cnyPerUsd = referenceRate.value?.cnyPerUsd
  if (originalPrice == null || !cnyPerUsd || cnyPerUsd <= 0) return null
  // 贵金属原价以每金衡盎司计，使用精确克重转换；USDT 以 1:1 美元近似折算。
  const gramsPerTroyOunce = 31.1034768
  const cny = originalPrice * cnyPerUsd
  switch (symbol) {
    case 'XAUUSD': return { amount: cny / gramsPerTroyOunce, unit: '元/克' }
    case 'XAGUSD': return { amount: cny * 1000 / gramsPerTroyOunce, unit: '元/千克' }
    case 'BTCUSDT': return { amount: cny, unit: '元' }
    default: return null
  }
}

/** 重新读取看板数据，参考汇率由后端按缓存周期更新。 */
function refresh() {
  anchor.value = new Date()
  refreshVersion.value++
}

/** 时间按北京时间显示，异常或缺失字段保持为空。 */
function dateTime(value?: string | null) {
  if (!value) return null
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? null : timestamp.format(date)
}

/** 显示有符号涨跌幅，缺失值用横线表示。 */
function change(value?: number | null) {
  return value == null ? '—' : percent.format(value) + '%'
}

/** 优先提示本轮采集异常，旧值仍留在相应卡片上。 */
function sourceState(item?: Latest): { text: string; tone: string } {
  if (loadingQuotes.value) return { text: '读取中', tone: 'muted' }
  if (quoteError.value) return { text: '连接异常', tone: 'warning' }
  if (!item) return { text: '待采集', tone: 'muted' }
  if (item.collectionStatus === 'FAILED') return { text: '采集失败', tone: 'warning' }
  if (item.freshness === 'STALE') return { text: '历史报价', tone: 'warning' }
  if (item.freshness === 'CURRENT' && item.quote?.price != null) return { text: '已采集', tone: 'positive' }
  return { text: '待采集', tone: 'muted' }
}
</script>

<template>
  <div class="shell">
    <aside class="sidebar">
      <a class="brand" href="#overview" aria-label="MarketDaily 市场概览">
        <span class="brand-mark" aria-hidden="true"><i></i><i></i><i></i></span>
        <span>MarketDaily <small>市场工作台</small></span>
      </a>
      <nav aria-label="页面导航">
        <a href="#overview"><span class="nav-icon" aria-hidden="true">▦</span>市场概览</a>
        <a href="#reports"><span class="nav-icon" aria-hidden="true">▧</span>AI 日报</a>
        <a href="#trend"><span class="nav-icon" aria-hidden="true">⌁</span>行情趋势</a>
        <a href="#gold-signals"><span class="nav-icon" aria-hidden="true">◈</span>黄金走势信号</a>
        <a href="#news"><span class="nav-icon" aria-hidden="true">▤</span>市场新闻</a>
      </nav>
      <p class="sidebar-foot">报价和新闻时间均为北京时间</p>
    </aside>

    <main id="overview" class="workspace">
      <section class="masthead" aria-labelledby="page-title">
        <div class="heading-copy">
          <p class="today">{{ today.format(anchor) }} <span>北京时间</span></p>
          <h1 id="page-title">市场概览</h1>
          <p class="masthead-note">追踪黄金、白银、美股指数和比特币的价格变化与相关消息。</p>
        </div>
        <button type="button" class="refresh" :disabled="refreshing" @click="refresh">
          <span aria-hidden="true">↻</span>{{ refreshing ? '读取中' : '刷新数据' }}
        </button>
        <div class="orbit" aria-hidden="true"></div>
      </section>

      <section aria-labelledby="quotes-title" class="quotes-section" :aria-busy="loadingQuotes">
        <div class="section-heading">
          <h2 id="quotes-title">市场行情</h2>
          <div class="quote-tools">
            <span>{{ newestQuote ? '最近行情采集 ' + dateTime(newestQuote) : '暂无行情采集时间' }}</span>
            <div class="quote-arrows" role="group" aria-label="滑动行情卡片">
              <button type="button" aria-label="向左滑动行情" aria-controls="quote-rail" :disabled="!canScrollLeft"
                      @click="scrollQuotes(-1)">‹</button>
              <button type="button" aria-label="向右滑动行情" aria-controls="quote-rail" :disabled="!canScrollRight"
                      @click="scrollQuotes(1)">›</button>
            </div>
          </div>
        </div>
        <p v-if="quoteError" class="inline-alert" role="status">行情读取失败，可以点击“刷新数据”重试。</p>
        <div id="quote-rail" ref="quoteRail" class="quotes-grid" aria-describedby="quote-units" @scroll.passive="updateQuoteRail">
          <button v-for="card in cards" :key="card.code" type="button" class="quote-card"
                  :aria-pressed="selectedAsset === card.code"
                  :style="{ '--asset-color': card.color, '--asset-tint': card.tint }"
                  @click="selectedAsset = card.code">
            <span class="quote-head">
              <span class="asset-icon" aria-hidden="true">{{ card.glyph }}</span>
              <span class="asset-text"><strong>{{ card.label }}</strong><small>{{ card.symbol }}</small></span>
            </span>
            <span class="quote-value">
              {{ card.data?.quote?.price == null ? '—' : price.format(card.data.quote.price) }}
              <small>{{ card.data?.unit ?? card.unit }}</small>
            </span>
            <span v-if="card.cny" class="quote-reference">约 {{ price.format(card.cny.amount) }} {{ card.cny.unit }}</span>
            <span class="quote-change">
              <strong :class="(card.data?.quote?.changePercent ?? 0) > 0 ? 'up' : (card.data?.quote?.changePercent ?? 0) < 0 ? 'down' : ''">
                {{ change(card.data?.quote?.changePercent) }}
              </strong>
              <small>{{ card.compare }}</small>
            </span>
            <span class="quote-foot">
              {{ card.source }}<span>{{ dateTime(card.data?.quote?.observedAt) ?? card.data?.quote?.marketDate ?? '暂无报价' }}</span>
            </span>
          </button>
        </div>
        <p id="quote-units" class="quote-unit-note">USD/oz＝美元／金衡盎司，1 金衡盎司约 31.1035 克。CNY/g＝人民币元／克，CNY/kg＝人民币元／千克。</p>
        <p v-if="referenceRate" class="quote-unit-note">
          换算汇率 {{ referenceRate.source }} · {{ referenceRate.date }} · 1 USD = {{ referenceRate.cnyPerUsd }} CNY。BTC 按 1 USDT ≈ 1 USD 估算。
        </p>
        <p v-else-if="rateError" class="quote-unit-note">人民币参考汇率暂不可用。</p>
      </section>

      <div class="market-detail">
        <section id="trend" class="panel trend-panel" aria-labelledby="trend-title" :aria-busy="loadingHistory">
          <div class="panel-heading">
            <div><h2 id="trend-title">行情趋势</h2><p>{{ asset.label }} <span> / </span> {{ history?.unit ?? asset.unit }}</p></div>
            <div class="segmented" role="group" aria-label="行情日期范围">
              <button v-if="canShowHourly" type="button" :aria-pressed="activeRange === '24h'" @click="trendRange = '24h'">24 小时</button>
              <button type="button" :aria-pressed="activeRange === 7" @click="trendRange = 7">7 日</button>
              <button type="button" :aria-pressed="activeRange === 30" @click="trendRange = 30">30 日</button>
            </div>
          </div>
          <div v-if="loadingHistory || historyError || !history?.points.some(point => point.price != null)" class="chart-empty" role="status">
            <span aria-hidden="true" class="chart-empty-icon">⌁</span>
            <strong>{{ loadingHistory ? '正在读取历史行情' : historyError ? '历史行情读取失败' : '暂无历史行情' }}</strong>
            <span v-if="!loadingHistory">{{ historyError ? '可刷新数据后重试。' : '采集到有效报价后，刷新数据即可查看。' }}</span>
          </div>
          <TrendChart v-else :history="history" :range="activeRange" :color="asset.color" />
          <p class="chart-note">
            <template v-if="activeRange !== '24h'">仅展示已采集报价。</template>
            <template v-else-if="isDelayedMetal">上金所延时报价，按北京时间的采集小时归档。</template>
            <template v-else>北京时间，展示每小时最新的已采集报价。</template>
            虚线连接缺口两侧的报价。
          </p>
        </section>

        <section class="panel sources-panel" aria-labelledby="sources-title">
          <div class="panel-heading"><div><h2 id="sources-title">数据源状态</h2><p>当前可查看 {{ availableCount }} / {{ assets.length }} 项报价</p></div></div>
          <ul class="sources">
            <li v-for="card in cards" :key="card.code">
              <span><strong>{{ card.symbol }}</strong><small>{{ card.source }}</small></span>
              <span class="state" :class="sourceState(card.data).tone">{{ sourceState(card.data).text }}</span>
            </li>
          </ul>
        </section>
      </div>

      <GoldSignalsPanel :refresh="refreshVersion" />

      <ReportPanel :refresh="refreshVersion" />

      <NewsPanel :anchor="anchor" :refresh="refreshVersion" @loading="loadingNews = $event" />
      <footer>MarketDaily <span>市场信息仅供参考</span></footer>
    </main>
  </div>
</template>
