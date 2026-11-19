<script setup lang="ts">
import { computed, defineAsyncComponent, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRequest, type History, type Latest, type Market, type NewsPage, type SymbolCode } from './api'

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
  { code: 'BTCUSD', label: '比特币', symbol: 'BTC', glyph: '₿', color: '#a14d15', tint: '#fbebdc', source: 'CoinGecko', unit: 'USD', compare: '24 小时涨跌' },
] as const
const markets = [
  { code: '', label: '全部市场' },
  { code: 'GOLD', label: '黄金' },
  { code: 'NASDAQ', label: '纳斯达克' },
  { code: 'CRYPTO', label: '加密货币' },
] as const

// 趋势区与新闻区各自保留筛选条件。刷新时更新新闻分页锚点。
const selectedAsset = ref<SymbolCode>('XAUUSD')
const days = ref<7 | 30>(7)
const selectedMarket = ref<Market | ''>('')
const newsPage = ref(1)
const newsSize = 6
const newsHours = ref<24 | 72>(24)
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
const { data: history, pending: loadingHistory, error: historyError } = useRequest<History>(
  () => `/api/markets/${selectedAsset.value}/history?days=${days.value}`, refreshVersion)
const { data: news, pending: loadingNews, error: newsError } = useRequest<NewsPage>(() => {
  // 分页期间共用同一个起止时刻，防止结果随时钟移动。
  const to = anchor.value
  const from = new Date(to.getTime() - newsHours.value * 3_600_000)
  const params = new URLSearchParams({ page: String(newsPage.value), size: String(newsSize), from: from.toISOString(), to: to.toISOString() })
  if (selectedMarket.value) params.set('market', selectedMarket.value)
  return `/api/news?${params}`
}, refreshVersion)

const quotes = computed(() => new Map(latest.value?.map(item => [item.symbol, item])))
const cards = computed(() => assets.map(item => ({ ...item, data: quotes.value.get(item.code) })))
const availableCount = computed(() => cards.value.filter(item => item.data?.quote?.price != null).length)
const newestQuote = computed(() => latest.value?.reduce<string | null>((last, item) => {
  const candidate = item.quote?.collectedAt
  return candidate && (!last || Date.parse(candidate) > Date.parse(last)) ? candidate : last
}, null))
const today = new Intl.DateTimeFormat('zh-CN', { dateStyle: 'full', timeZone: 'Asia/Shanghai' })
const timestamp = new Intl.DateTimeFormat('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false, timeZone: 'Asia/Shanghai' })
const price = new Intl.NumberFormat('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
const percent = new Intl.NumberFormat('zh-CN', { signDisplay: 'exceptZero', minimumFractionDigits: 2, maximumFractionDigits: 2 })
const pageCount = computed(() => Math.max(1, Math.ceil((news.value?.total ?? 0) / newsSize)))
const refreshing = computed(() => loadingQuotes.value || loadingHistory.value || loadingNews.value)

/** 切换新闻筛选时回到第一页。 */
function filterNews(market: Market | '', hours: 24 | 72) {
  selectedMarket.value = market
  newsHours.value = hours
  newsPage.value = 1
}

/** 重新读取后端已存数据，不触发外部采集。 */
function refresh() {
  anchor.value = new Date()
  newsPage.value = 1
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

/** 只为合法的原文 HTTP(S) 链接生成可点击入口。 */
function safeUrl(value: string) {
  try {
    const url = new URL(value)
    return ['https:', 'http:'].includes(url.protocol) ? url.href : undefined
  } catch {
    return undefined
  }
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
        <a href="#trend"><span class="nav-icon" aria-hidden="true">⌁</span>行情趋势</a>
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
      </section>

      <div class="market-detail">
        <section id="trend" class="panel trend-panel" aria-labelledby="trend-title" :aria-busy="loadingHistory">
          <div class="panel-heading">
            <div><h2 id="trend-title">行情趋势</h2><p>{{ asset.label }} <span> / </span> {{ history?.unit ?? asset.unit }}</p></div>
            <div class="segmented" role="group" aria-label="行情日期范围">
              <button type="button" :aria-pressed="days === 7" @click="days = 7">7 日</button>
              <button type="button" :aria-pressed="days === 30" @click="days = 30">30 日</button>
            </div>
          </div>
          <div v-if="loadingHistory || historyError || !history?.points.some(point => point.price != null)" class="chart-empty" role="status">
            <span aria-hidden="true" class="chart-empty-icon">⌁</span>
            <strong>{{ loadingHistory ? '正在读取历史行情' : historyError ? '历史行情读取失败' : '暂无历史行情' }}</strong>
            <span v-if="!loadingHistory">{{ historyError ? '可刷新数据后重试。' : '采集到有效报价后，会在对应日期显示价格。' }}</span>
          </div>
          <TrendChart v-else :history="history" :days="days" :color="asset.color" />
          <p class="chart-note">仅展示已采集报价，缺失日期留空。</p>
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

      <section id="news" class="panel news-panel" aria-labelledby="news-title" :aria-busy="loadingNews">
        <div class="panel-heading">
          <div><h2 id="news-title">市场新闻</h2><p>按重要性排序，可查看原文与新闻时间。</p></div>
          <div class="segmented" role="group" aria-label="新闻时间范围">
            <button type="button" :aria-pressed="newsHours === 24" @click="filterNews(selectedMarket, 24)">24 小时</button>
            <button type="button" :aria-pressed="newsHours === 72" @click="filterNews(selectedMarket, 72)">72 小时</button>
          </div>
        </div>
        <div class="categories" role="group" aria-label="市场分类">
          <button v-for="market in markets" :key="market.code" type="button"
                  :aria-pressed="selectedMarket === market.code" @click="filterNews(market.code, newsHours)">{{ market.label }}</button>
        </div>
        <p v-if="loadingNews || newsError || !news?.items.length" class="news-empty" role="status">
          {{ loadingNews ? '正在读取新闻' : newsError ? '新闻读取失败，可以刷新数据重试。' : '当前时间范围内暂无新闻。' }}
        </p>
        <ol v-else class="news-list">
          <li v-for="item in news.items" :key="item.id">
            <article>
              <div>
                <div class="news-meta">
                  <span>{{ item.source }}</span>
                  <span v-if="dateTime(item.publishedAt)">发布 {{ dateTime(item.publishedAt) }}</span>
                  <span v-else-if="dateTime(item.seenAt)">收录 {{ dateTime(item.seenAt) }}</span>
                  <span v-if="item.selected">已被日报引用</span>
                </div>
                <h3>{{ item.title }}</h3>
                <p v-if="item.summary">{{ item.summary }}</p>
                <div class="news-meta">
                  <span v-for="market in item.markets" :key="market" class="market-tag">{{ markets.find(entry => entry.code === market)?.label }}</span>
                </div>
              </div>
              <div class="article-actions">
                <span>重要度 <strong>{{ item.score }}</strong></span>
                <a v-if="safeUrl(item.url)" :href="safeUrl(item.url)" target="_blank" rel="noopener noreferrer"
                   :aria-label="'查看原文：' + item.title">查看原文 <span aria-hidden="true">↗</span></a>
              </div>
            </article>
          </li>
        </ol>
        <nav v-if="news && !loadingNews && !newsError && news.total > 0" class="pagination" aria-label="新闻分页">
          <button type="button" :disabled="newsPage <= 1" @click="newsPage--">上一页</button>
          <span>第 {{ newsPage }} / {{ pageCount }} 页</span>
          <button type="button" :disabled="newsPage >= pageCount" @click="newsPage++">下一页</button>
        </nav>
      </section>
      <footer>MarketDaily <span>市场信息仅供参考</span></footer>
    </main>
  </div>
</template>
