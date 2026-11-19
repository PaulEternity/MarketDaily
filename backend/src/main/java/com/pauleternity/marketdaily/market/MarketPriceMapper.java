package com.pauleternity.marketdaily.market;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;

/** 读写现有行情表，复用资产、业务日期和来源的唯一约束。 */
@Mapper
public interface MarketPriceMapper {

    // 查询列顺序与 MarketSnapshot 唯一构造器一致，供 MyBatis 构造器映射。
    String COLUMNS = """
            asset_symbol, asset_name, price, previous_price, change_amount, change_percent, currency,
            comparison_type, market_date, observed_at, collected_at, report_date, source, status
            """;

    /** 新报价更新同日快照。失败记录只改状态，保留已有的价格和原始时间。 */
    @Insert("""
            INSERT INTO market_price
            (asset_symbol, asset_name, price, previous_price, change_amount, change_percent, currency,
             comparison_type, market_date, observed_at, collected_at, report_date, source, status)
            VALUES (#{symbol}, #{name}, #{price}, #{previousPrice}, #{changeAmount}, #{changePercent},
                    #{currency}, #{comparisonType}, #{marketDate}, #{observedAt}, #{collectedAt},
                    #{reportDate}, #{source}, #{status}) AS incoming
            ON DUPLICATE KEY UPDATE
                previous_price = IF(incoming.price IS NULL, market_price.previous_price, incoming.previous_price),
                change_amount = IF(incoming.price IS NULL, market_price.change_amount, incoming.change_amount),
                change_percent = IF(incoming.price IS NULL, market_price.change_percent, incoming.change_percent),
                market_date = IF(incoming.price IS NULL, market_price.market_date, incoming.market_date),
                observed_at = IF(incoming.price IS NULL, market_price.observed_at, incoming.observed_at),
                collected_at = IF(incoming.price IS NULL AND market_price.price IS NOT NULL,
                                  market_price.collected_at, incoming.collected_at),
                price = COALESCE(incoming.price, market_price.price),
                status = incoming.status
            """)
    void save(MarketSnapshot snapshot);

    /** 读取当天最后一次采集的状态和已存快照。 */
    @Select("SELECT " + COLUMNS + """
            FROM market_price
            WHERE asset_symbol = #{symbol} AND source = #{source} AND report_date = #{day}
            """)
    MarketSnapshot onDate(@Param("symbol") String symbol, @Param("source") String source,
                          @Param("day") LocalDate day);

    /** 当天无效时，从之前的有效快照回退。 */
    @Select("SELECT " + COLUMNS + """
            FROM market_price
            WHERE asset_symbol = #{symbol} AND source = #{source} AND report_date < #{day}
              AND price IS NOT NULL
            ORDER BY report_date DESC LIMIT 1
            """)
    MarketSnapshot latestBefore(@Param("symbol") String symbol, @Param("source") String source,
                                @Param("day") LocalDate day);

    /** 国际金银仅使用同一资产、同一来源此前实际发生在采样日的报价。 */
    @Select("SELECT " + COLUMNS + """
            FROM market_price
            WHERE asset_symbol = #{symbol} AND source = #{source} AND report_date < #{day}
              AND market_date = report_date AND price IS NOT NULL
            ORDER BY report_date DESC LIMIT 1
            """)
    MarketSnapshot previousDailySample(@Param("symbol") String symbol, @Param("source") String source,
                                       @Param("day") LocalDate day);

    /** 读取日期窗口内的有效快照，重复交易日优先采用最近采集的结果。 */
    @Select("SELECT " + COLUMNS + """
            FROM market_price
            WHERE asset_symbol = #{symbol} AND source = #{source}
              AND report_date BETWEEN #{from} AND #{through}
              AND market_date BETWEEN #{from} AND #{through}
              AND price IS NOT NULL
            ORDER BY market_date, report_date DESC
            """)
    List<MarketSnapshot> history(@Param("symbol") String symbol, @Param("source") String source,
                                 @Param("from") LocalDate from, @Param("through") LocalDate through);
}
