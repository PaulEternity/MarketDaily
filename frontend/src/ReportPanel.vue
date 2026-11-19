<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRequest, type Market } from './api'

/** 日报区只读取 MySQL 已存结果，通过快照 ID 展开原文来源。 */
const props = defineProps<{ refresh: number }>()

/** 前端只读取展示需要的日报字段。 */
interface Report {
  date: string
  model: string
  generatedAt: string
  inputTokens: number | null
  outputTokens: number | null
  content: {
    analysis: { summary: string; sections: { market: Market; analysis: string; watch: string; newsIds: number[] }[] }
    input: { news: { id: number; title: string; source: string; url: string }[] }
  }
}

/** 列表使用轻量摘要，点击某一条再拉取详情。 */
interface History {
  total: number
  items: { date: string; summary: string }[]
}

// 空日期表示查看北京时间今日日报；页码仅控制历史列表。
const selectedDate = ref('')
const historyPage = ref(1)
const historySize = 6
const { data: lookup, pending: loading, error: detailError } = useRequest<{ report: Report | null }>(
  () => '/api/reports/' + (selectedDate.value ? encodeURIComponent(selectedDate.value) : 'today'),
  () => props.refresh,
)
const { data: history, pending: historyLoading, error: historyError } = useRequest<History>(
  () => `/api/reports?page=${historyPage.value}&size=${historySize}`, () => props.refresh,
)
const report = computed(() => lookup.value?.report)
const pageCount = computed(() => Math.max(1, Math.ceil((history.value?.total ?? 0) / historySize)))
const labels: Record<Market, string> = { GOLD: '贵金属', NASDAQ: '纳指', CRYPTO: '比特币' }
const formatter = new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short', timeZone: 'Asia/Shanghai' })

/** 只有快照中真实存在且可安全打开的新闻才显示为链接。 */
function references(ids: number[]) {
  const articles = report.value?.content.input.news ?? []
  return ids.flatMap(id => {
    const article = articles.find(item => item.id === id)
    if (!article) return []
    try {
      const url = new URL(article.url)
      return ['https:', 'http:'].includes(url.protocol) ? [{ ...article, url: url.href }] : []
    } catch {
      return []
    }
  })
}
</script>

<template>
  <section id="reports" class="panel report-panel" aria-labelledby="report-title">
    <div class="panel-heading">
      <div><h2 id="report-title">AI 日报</h2><p>基于已采集的行情和新闻整理。</p></div>
      <div class="segmented" role="group" aria-label="日报详情">
        <button type="button" :aria-pressed="!selectedDate" @click="selectedDate = ''">今日日报</button>
      </div>
    </div>

    <div class="report-layout">
      <div class="report-main" :aria-busy="loading">
        <p v-if="loading || detailError || !report" class="report-empty" role="status">
          {{ loading ? '正在读取日报' : detailError ? '日报读取失败，可以刷新数据重试。' : '这一天还没有日报。' }}
        </p>
        <article v-else :aria-label="report.date + ' 日报'">
          <div class="report-summary">
            <div class="report-meta"><time :datetime="report.date">{{ report.date }}</time></div>
            <h3>市场总览</h3>
            <p>{{ report.content.analysis.summary }}</p>
          </div>
          <section v-for="section in report.content.analysis.sections" :key="section.market" class="report-section">
            <h3>{{ labels[section.market] }}</h3>
            <p>{{ section.analysis }}</p>
            <p class="report-watch"><strong>观察点</strong> {{ section.watch }}</p>
            <ul v-if="references(section.newsIds).length" class="report-links" aria-label="原文引用">
              <li v-for="article in references(section.newsIds)" :key="article.id">
                <a :href="article.url" target="_blank" rel="noopener noreferrer">{{ article.title }}</a>
                <small>{{ article.source }}</small>
              </li>
            </ul>
          </section>
          <p class="report-meta report-signature">
            {{ report.model }} · {{ formatter.format(new Date(report.generatedAt)) }} ·
            Token {{ report.inputTokens ?? '未返回' }} 输入 / {{ report.outputTokens ?? '未返回' }} 输出
          </p>
        </article>
      </div>

      <aside aria-labelledby="report-history-title" class="report-history">
        <h3 id="report-history-title">历史日报</h3>
        <p v-if="historyLoading || historyError || !history?.items.length" class="report-meta" role="status">
          {{ historyLoading ? '正在读取历史' : historyError ? '历史日报读取失败' : '暂无历史日报' }}
        </p>
        <ul v-else>
          <li v-for="item in history.items" :key="item.date">
            <button type="button" :aria-pressed="selectedDate === item.date" @click="selectedDate = item.date">
              <span>{{ item.date }}</span><small>{{ item.summary }}</small>
            </button>
          </li>
        </ul>
        <nav v-if="(history?.total ?? 0) > historySize" class="pagination" aria-label="日报分页">
          <button type="button" :disabled="historyPage <= 1" @click="historyPage--">上一页</button>
          <span>{{ historyPage }} / {{ pageCount }}</span>
          <button type="button" :disabled="historyPage >= pageCount" @click="historyPage++">下一页</button>
        </nav>
      </aside>
    </div>
  </section>
</template>

<style scoped>
.report-panel { margin-top: 16px; }
.report-layout { display: grid; grid-template-columns: minmax(0, 2fr) minmax(0, 1fr); gap: 16px; margin-top: 12px; }
.report-summary { background: #edf4fa; border-left: 3px solid #527c9f; border-radius: 4px 8px 8px 4px; padding: 12px 14px; }
.report-section { padding: 11px 1px; border-bottom: 1px solid var(--border); }
.report-main h3 { margin: 4px 0; font-size: 15px; }
.report-main p, .report-links { overflow-wrap: anywhere; }
.report-watch, .report-meta { color: var(--muted); font-size: 12px; margin-top: 4px; }
.report-watch strong { color: var(--ink); margin-right: 4px; }
.report-signature { margin-top: 9px; }
.report-links, .report-history ul { list-style: none; padding: 0; margin: 5px 0 0; }
.report-links a { color: var(--accent); text-decoration: underline; font-size: 12px; }
.report-links small { color: var(--muted); margin-left: 6px; }
.report-history { border-left: 1px solid var(--border); padding-left: 13px; min-width: 0; }
.report-history h3 { font-size: 14px; }
.report-history li button { width: 100%; text-align: left; border: 0; border-bottom: 1px solid #edf2f5; background: white; padding: 8px 6px; }
.report-history li button:hover, .report-history li button[aria-pressed="true"] { background: #edf4fa; }
.report-history li button span { display: block; font-size: 12px; font-weight: 700; }
.report-history li button small { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: var(--muted); }
.report-empty { padding: 16px 10px; background: #f4f7fa; border-radius: 6px; color: var(--muted); }
@media (max-width: 700px) {
  .report-layout { grid-template-columns: minmax(0, 1fr); }
  .report-history { border-left: 0; border-top: 1px solid var(--border); padding: 10px 0 0; }
}
</style>
