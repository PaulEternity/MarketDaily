<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRequest, type Market, type NewsItem, type NewsPage } from './api'

/** 市场新闻包含固定 24 小时要点和按时间分页的全部报道。 */
const props = defineProps<{ anchor: Date; refresh: number }>()
const emit = defineEmits<{ loading: [pending: boolean] }>()

// 时间线和报道共用市场筛选，报道的时间范围和页码单独维护。
const selectedMarket = ref<Market | ''>('')
const reportHours = ref<24 | 72>(24)
const page = ref(1)
const pageSize = 6
const marketLabels: Record<Market, string> = { GOLD: '黄金 / 白银', NASDAQ: '纳斯达克', CRYPTO: '加密货币' }
const markets: { code: Market | ''; label: string }[] = [
  { code: '', label: '全部市场' },
  { code: 'GOLD', label: marketLabels.GOLD },
  { code: 'NASDAQ', label: marketLabels.NASDAQ },
  { code: 'CRYPTO', label: marketLabels.CRYPTO },
]

/** 两块数据使用同一个结束时刻，翻页期间不会改变查询窗口。 */
function newsParams() {
  const params = new URLSearchParams({ to: props.anchor.toISOString() })
  if (selectedMarket.value) params.set('market', selectedMarket.value)
  return params
}

const { data: highlights, pending: loadingHighlights, error: highlightsError } = useRequest<NewsItem[]>(
  () => `/api/news/highlights?${newsParams()}`, () => props.refresh,
)
const { data: reports, pending: loadingReports, error: reportsError } = useRequest<NewsPage>(() => {
  const params = newsParams()
  // 列表以毫秒倒推 24 或 72 小时，时间线始终只选最近 24 小时。
  const from = new Date(props.anchor.getTime() - reportHours.value * 3_600_000)
  params.set('from', from.toISOString())
  params.set('page', String(page.value))
  params.set('size', String(pageSize))
  params.set('sort', 'recent')
  return `/api/news?${params}`
}, () => props.refresh)
const pageCount = computed(() => Math.max(1, Math.ceil((reports.value?.total ?? 0) / pageSize)))

watch(() => props.refresh, () => { page.value = 1 })
watch(() => loadingHighlights.value || loadingReports.value, loading => emit('loading', loading), { immediate: true })

const dayFormat = new Intl.DateTimeFormat('zh-CN', { timeZone: 'Asia/Shanghai', month: 'long', day: 'numeric' })
const timeFormat = new Intl.DateTimeFormat('zh-CN', {
  timeZone: 'Asia/Shanghai', hour: '2-digit', minute: '2-digit', hourCycle: 'h23',
})

// 后端已按时间倒序。按北京时间分组时保留事件原有顺序。
const timeline = computed(() => {
  const days = new Map<string, NewsItem[]>()
  for (const item of highlights.value ?? []) {
    const timestamp = newsTime(item)
    if (!timestamp || !Number.isFinite(Date.parse(timestamp))) continue
    const day = dayFormat.format(new Date(timestamp))
    const entries = days.get(day) ?? []
    entries.push(item)
    days.set(day, entries)
  }
  return [...days].map(([day, items]) => ({ day, items }))
})

/** 优先使用原文发布时间，上游只给收录时间时如实展示。 */
function newsTime(item: NewsItem) {
  return item.publishedAt ?? item.seenAt
}

/** 显示北京时间，可选择显示日期以区分报道的跨日时间。 */
function displayTime(item: NewsItem, includeDay = false) {
  const timestamp = newsTime(item)
  if (!timestamp || !Number.isFinite(Date.parse(timestamp))) return ''
  const date = new Date(timestamp)
  return includeDay ? `${dayFormat.format(date)} ${timeFormat.format(date)}` : timeFormat.format(date)
}

/** 切换市场后从全部报道的第一页开始。 */
function selectMarket(market: Market | '') {
  selectedMarket.value = market
  page.value = 1
}

/** 报道时段独立切换，不改变要点的 24 小时窗口。 */
function selectHours(hours: 24 | 72) {
  reportHours.value = hours
  page.value = 1
}

/** 仅允许通过 HTTP(S) 打开上游报道。 */
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
  <section id="news" class="panel news-panel" aria-labelledby="news-title">
    <div class="panel-heading">
      <h2 id="news-title">市场新闻</h2>
      <a href="#news-reports" class="news-jump">跳到全部报道 <span aria-hidden="true">↓</span></a>
    </div>
    <div class="categories" role="group" aria-label="市场分类">
      <button v-for="market in markets" :key="market.code" type="button"
              :aria-pressed="selectedMarket === market.code" @click="selectMarket(market.code)">{{ market.label }}</button>
    </div>

    <section class="news-highlights" aria-labelledby="news-highlights-title" :aria-busy="loadingHighlights">
      <h3 id="news-highlights-title">24 小时要点</h3>
      <p v-if="loadingHighlights || highlightsError || !timeline.length" class="news-empty" role="status">
        {{ loadingHighlights ? '正在读取要点' : highlightsError ? '要点读取失败，可刷新重试。' : '最近 24 小时暂无要点。' }}
      </p>
      <div v-for="group in timeline" v-else :key="group.day" class="timeline-day">
        <p class="timeline-date">{{ group.day }}</p>
        <ol class="news-timeline">
          <li v-for="item in group.items" :key="item.id" class="timeline-entry">
            <div class="timeline-clock">
              <time :datetime="newsTime(item) ?? undefined">{{ displayTime(item) }}</time>
              <small v-if="!item.publishedAt">收录</small>
            </div>
            <article class="timeline-story">
              <h4>
                <a v-if="safeUrl(item.url)" :href="safeUrl(item.url)" target="_blank" rel="noopener noreferrer">{{ item.title }}</a>
                <template v-else>{{ item.title }}</template>
              </h4>
              <p v-if="item.summary && item.summary !== item.title" class="timeline-summary">{{ item.summary }}</p>
              <div class="news-meta">
                <span>{{ item.source }}</span>
                <span v-for="market in item.markets" :key="market" class="market-tag">{{ marketLabels[market] }}</span>
              </div>
            </article>
          </li>
        </ol>
      </div>
    </section>

    <section id="news-reports" aria-labelledby="news-reports-title" :aria-busy="loadingReports">
      <div class="news-reports-heading">
        <div class="news-reports-title">
          <h3 id="news-reports-title">全部报道</h3>
          <span v-if="reports">{{ reports.total }} 条</span>
        </div>
        <div class="segmented" role="group" aria-label="报道时间范围">
          <button type="button" :aria-pressed="reportHours === 24" @click="selectHours(24)">24 小时</button>
          <button type="button" :aria-pressed="reportHours === 72" @click="selectHours(72)">72 小时</button>
        </div>
      </div>
      <p v-if="loadingReports || reportsError || !reports?.items.length" class="news-empty" role="status">
        {{ loadingReports ? '正在读取报道' : reportsError ? '报道读取失败，可刷新重试。' : '当前时间范围内暂无报道。' }}
      </p>
      <ol v-else class="news-list">
        <li v-for="item in reports.items" :key="item.id">
          <article>
            <div>
              <div class="news-meta">
                <span>{{ item.source }}</span>
                <span v-if="newsTime(item)">
                  {{ item.publishedAt ? '发布' : '收录' }} <time :datetime="newsTime(item) ?? undefined">{{ displayTime(item, true) }}</time>
                </span>
                <span v-if="item.selected">已被日报引用</span>
              </div>
              <h4>{{ item.title }}</h4>
              <p v-if="item.summary && item.summary !== item.title">{{ item.summary }}</p>
              <div v-if="item.markets.length" class="news-meta">
                <span v-for="market in item.markets" :key="market" class="market-tag">{{ marketLabels[market] }}</span>
              </div>
            </div>
            <div class="article-actions">
              <a v-if="safeUrl(item.url)" :href="safeUrl(item.url)" target="_blank" rel="noopener noreferrer"
                 :aria-label="'查看原文：' + item.title">查看原文 <span aria-hidden="true">↗</span></a>
            </div>
          </article>
        </li>
      </ol>
      <nav v-if="reports && !loadingReports && !reportsError && reports.total > 0" class="pagination" aria-label="报道分页">
        <button type="button" :disabled="page <= 1" @click="page--">上一页</button>
        <span>第 {{ page }} / {{ pageCount }} 页</span>
        <button type="button" :disabled="page >= pageCount" @click="page++">下一页</button>
      </nav>
    </section>
  </section>
</template>
