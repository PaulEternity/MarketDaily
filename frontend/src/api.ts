import { shallowRef, toValue, watch, type MaybeRefOrGetter } from 'vue'

/** 服务端支持的行情资产和新闻分类。 */
export type SymbolCode = 'XAUUSD' | 'AU9999' | 'XAGUSD' | 'AGTD' | 'NASDAQCOM' | 'BTCUSD'
export type Market = 'GOLD' | 'NASDAQ' | 'CRYPTO'

/** UTC 时间戳与市场日期分开，休市日的报价保留原始日期。 */
interface Quote {
  price: number | null
  changePercent: number | null
  observedAt: string | null
  collectedAt: string
  marketDate: string | null
}

/** 最新行情同时区分报价新旧与采集结果。 */
export interface Latest {
  symbol: SymbolCode
  unit: string
  freshness: 'CURRENT' | 'STALE' | 'MISSING'
  collectionStatus: 'AVAILABLE' | 'FAILED' | 'NOT_COLLECTED'
  quote: Quote | null
}

/** 历史窗口首尾均包含，points 只含服务端已有记录。 */
export interface History {
  unit: string
  from: string
  through: string
  points: Quote[]
}

/** 小时窗口以 UTC 整点返回，前端统一转换为北京时间。 */
export interface HourlyHistory {
  unit: string
  from: string
  through: string
  points: { hour: string; price: number }[]
}

/** 新闻优先显示真实发布时间，其次明确标记收录时间。 */
export interface NewsPage {
  total: number
  items: {
    id: number
    title: string
    url: string
    source: string
    summary: string | null
    publishedAt: string | null
    seenAt: string | null
    score: number
    selected: boolean
    markets: Market[]
  }[]
}

/** 对任意只读接口维护独立状态。切换筛选时取消旧请求，避免晚到的响应覆盖结果。 */
export function useRequest<T>(url: MaybeRefOrGetter<string>, refresh: MaybeRefOrGetter<number>) {
  const data = shallowRef<T>()
  const pending = shallowRef(true)
  const error = shallowRef(false)

  watch([() => toValue(url), () => toValue(refresh)], async ([path], _, onCleanup) => {
    // 每次请求拥有自己的取消信号和超时，组件卸载时同步清理。
    const request = new AbortController()
    const timeout = AbortSignal.timeout(12_000)
    onCleanup(() => request.abort())
    data.value = undefined
    pending.value = true
    error.value = false
    try {
      const response = await fetch(path, { signal: AbortSignal.any([request.signal, timeout]) })
      if (!response.ok) throw new Error('http')
      const envelope = await response.json()
      if (envelope?.code !== 0 || envelope.data == null) throw new Error('api')
      if (!request.signal.aborted) data.value = envelope.data as T
    } catch {
      if (!request.signal.aborted) error.value = true
    } finally {
      if (!request.signal.aborted) pending.value = false
    }
  }, { immediate: true })

  return { data, pending, error }
}
