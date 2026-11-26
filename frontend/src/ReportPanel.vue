<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRequest, type Market } from './api'

/** 日报区展示已存内容、原文引用和最近的自动任务执行情况。 */
const props = defineProps<{ refresh: number }>()

/** 前端只读取展示需要的日报字段。 */
interface Report {
  date: string
  model: string
  generatedAt: string
  inputTokens: number | null
  outputTokens: number | null
  content: {
    analysis: {
      summary: string
      sections: { market: Market; analysis: string; outlook?: string | null; watch: string; newsIds: number[] }[]
    }
    input: { news: { id: number; title: string; source: string; url: string }[] }
  }
}

/** 列表使用轻量摘要，点击某一条再拉取详情。 */
interface History {
  total: number
  items: { date: string; summary: string }[]
}

/** 最近一次自动执行的摘要，运行中的任务没有完成时间。 */
interface ScheduledTask {
  businessDate: string
  attempt: number
  status: string
  startedAt: string | null
  finishedAt: string | null
  reason: string | null
}

/** 采集和生成的执行记录各自独立，尚无记录时对应字段为空。 */
interface ScheduleStatus {
  enabled: boolean
  collection: ScheduledTask | null
  generation: ScheduledTask | null
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
// 调度状态随整页刷新，切换历史日报和分页时仍展示全局最近一次执行。
const { data: schedule, pending: scheduleLoading, error: scheduleError } = useRequest<ScheduleStatus>(
  '/api/reports/schedule-status', () => props.refresh,
)
const taskRows = computed(() => [
  { label: '最近采集', task: schedule.value?.collection },
  { label: '最近生成', task: schedule.value?.generation },
])
// 区分任务运行、部分成功、失败和中断，复用看板已有的状态颜色。
const taskStates: Partial<Record<string, { text: string; tone: string }>> = {
  RUNNING: { text: '执行中', tone: 'muted' },
  SUCCESS: { text: '成功', tone: 'positive' },
  PARTIAL: { text: '部分成功', tone: 'warning' },
  FAILED: { text: '失败', tone: 'warning' },
  INTERRUPTED: { text: '已中断', tone: 'warning' },
}
const report = computed(() => lookup.value?.report)
const pageCount = computed(() => Math.max(1, Math.ceil((history.value?.total ?? 0) / historySize)))
const labels: Record<Market, string> = { GOLD: '贵金属', NASDAQ: '纳指', CRYPTO: '比特币' }
// 预判时段与日报提示词一致，用于展示各市场的预测范围。
const outlookPeriods: Record<Market, string> = { GOLD: '下一交易时段', NASDAQ: '下一交易日', CRYPTO: '未来 24 小时' }
const formatter = new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short', timeZone: 'Asia/Shanghai' })

/** 未执行与旧记录中的未知状态都使用可读的占位提示。 */
function taskState(task?: ScheduledTask | null) {
  return task ? taskStates[task.status] ?? { text: '状态未知', tone: 'muted' }
    : { text: '暂无记录', tone: 'muted' }
}

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

    <section class="report-schedule" aria-labelledby="report-schedule-title" :aria-busy="scheduleLoading">
      <div class="report-schedule-heading">
        <h3 id="report-schedule-title">自动日报</h3>
        <span v-if="schedule && !schedule.enabled" class="state muted">调度未启用</span>
      </div>
      <p v-if="scheduleLoading || scheduleError" class="report-meta" role="status">
        {{ scheduleLoading ? '正在读取执行状态' : '执行状态读取失败，可以刷新数据重试。' }}
      </p>
      <dl v-else-if="schedule" class="report-task-list">
        <div v-for="row in taskRows" :key="row.label" class="report-task">
          <dt>{{ row.label }} <span class="state" :class="taskState(row.task).tone">{{ taskState(row.task).text }}</span></dt>
          <dd class="report-meta">
            <template v-if="row.task">
              {{ row.task.businessDate }} · 第 {{ row.task.attempt }} 次
              <template v-if="row.task.finishedAt">
                · 结束于 <time :datetime="row.task.finishedAt">{{ formatter.format(new Date(row.task.finishedAt)) }}</time>
              </template>
              <template v-else-if="row.task.startedAt">
                · 开始于 <time :datetime="row.task.startedAt">{{ formatter.format(new Date(row.task.startedAt)) }}</time>
              </template>
              <p v-if="row.task.reason" class="report-task-reason">{{ row.task.reason }}</p>
            </template>
          </dd>
        </div>
      </dl>
    </section>

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
            <p v-if="section.outlook" class="report-outlook">
              <strong>走势预判 · {{ outlookPeriods[section.market] }}</strong> {{ section.outlook }}
            </p>
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
.report-schedule { margin-top: 12px; padding: 9px 0; border-block: 1px solid var(--border); }
.report-schedule-heading { display: flex; align-items: center; gap: 8px; }
.report-schedule-heading h3 { margin: 0; font-size: 14px; }
.report-task-list { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 8px 14px; margin: 6px 0 0; }
.report-task dt { display: flex; align-items: center; gap: 8px; font-size: 12px; }
.report-task dd { margin-inline-start: 0; overflow-wrap: anywhere; }
.report-task-reason { margin: 3px 0 0; color: #885213; }
.report-layout { display: grid; grid-template-columns: minmax(0, 2fr) minmax(0, 1fr); gap: 16px; margin-top: 12px; }
.report-summary { background: #edf4fa; border-left: 3px solid #527c9f; border-radius: 4px 8px 8px 4px; padding: 12px 14px; }
.report-section { padding: 11px 1px; border-bottom: 1px solid var(--border); }
.report-main h3 { margin: 4px 0; font-size: 15px; }
.report-main p, .report-links { overflow-wrap: anywhere; }
.report-outlook, .report-watch, .report-meta { color: var(--muted); font-size: 12px; margin-top: 4px; }
.report-outlook strong, .report-watch strong { color: var(--ink); margin-right: 4px; }
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
  .report-layout, .report-task-list { grid-template-columns: minmax(0, 1fr); }
  .report-history { border-left: 0; border-top: 1px solid var(--border); padding: 10px 0 0; }
}
</style>
