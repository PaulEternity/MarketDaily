import type { MarkLineComponentOption } from 'echarts/components'

// 缺口提示中的价差以原报价单位显示，保留报价精度与涨跌方向。
const priceChange = new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 4, signDisplay: 'exceptZero' })

/** 为报价折线生成缺口虚线，缺失时段的原始值仍保留为空。 */
export function priceGapMarkLine(labels: string[], values: (number | null)[], color: string, unit: string,
  closedLabels?: ReadonlySet<string>): MarkLineComponentOption {
  // 虚线两端是缺口左、右侧的真实报价，连续缺值共用一条虚线。
  const bridges: NonNullable<MarkLineComponentOption['data']> = []
  // 保存最近的有效报价及其横轴位置，用于判断下一个有效报价前是否缺数。
  let previous: { index: number; price: number } | undefined
  values.forEach((price, index) => {
    if (price == null) return
    if (previous && index > previous.index + 1) {
      const from = labels[previous.index]!
      const through = labels[index]!
      // 只有缺口内所有时段都明确标为休市时，悬停提示才注明休市。
      const reason = closedLabels && labels.slice(previous.index + 1, index).every(label => closedLabels.has(label))
        ? '休市采样时段无报价' : '缺失时段无报价'
      const change = priceChange.format(price - previous.price)
      bridges.push([
        { coord: [from, previous.price], name: `${reason}\n${from} → ${through}\n报价差 ${change} ${unit}` },
        { coord: [through, price] },
      ])
    }
    previous = { index, price }
  })

  return {
    data: bridges, symbol: 'none', label: { show: false },
    lineStyle: { type: 'dashed', color, width: 2, opacity: 0.8 },
    tooltip: { trigger: 'item', formatter: '{b}' },
  }
}
