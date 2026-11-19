package com.pauleternity.marketdaily.market;

import java.time.ZoneId;
import java.util.List;

/** 行情资产标识及固定的展示和比较口径。 */
public enum MarketAsset {
    XAUUSD("国际现货黄金", "USD", "USD/oz", "Gold API", "PREVIOUS_DAILY_SAMPLE"),
    AU9999("国内黄金 Au99.99", "CNY", "CNY/g", "上海黄金交易所", "PREVIOUS_TRADING_CLOSE"),
    XAGUSD("国际现货白银", "USD", "USD/oz", "Gold API", "PREVIOUS_DAILY_SAMPLE"),
    AGTD("国内白银 Ag(T+D)", "CNY", "CNY/kg", "上海黄金交易所", "PREVIOUS_TRADING_CLOSE"),
    NASDAQCOM("纳斯达克综合指数", "POINTS", "点", "FRED", "PREVIOUS_TRADING_CLOSE"),
    BTCUSD("比特币", "USD", "USD", "CoinGecko", "ROLLING_24_HOURS"),
    ETHUSD("以太坊", "USD", "USD", "CoinGecko", "ROLLING_24_HOURS"); // 仅保留历史查询兼容

    // 最新行情固定展示顺序。ETH 停止采集，已有历史仍可查询。
    static final List<MarketAsset> ACTIVE = List.of(XAUUSD, AU9999, XAGUSD, AGTD, NASDAQCOM, BTCUSD);

    // 日报、黄金和加密货币的日度快照按北京时间划分。
    static final ZoneId REPORT_ZONE = ZoneId.of("Asia/Shanghai");

    final String displayName;
    final String currency;
    final String unit;
    final String source;
    final String comparisonType;

    /** 为资产绑定固定的数据源、单位和涨跌幅定义。 */
    MarketAsset(String displayName, String currency, String unit, String source, String comparisonType) {
        this.displayName = displayName;
        this.currency = currency;
        this.unit = unit;
        this.source = source;
        this.comparisonType = comparisonType;
    }
}
