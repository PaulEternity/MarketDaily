package com.pauleternity.marketdaily.mapper;

import com.pauleternity.marketdaily.model.market.GoldSignals.Bar;
import com.pauleternity.marketdaily.model.market.GoldSignals.Period;
import com.pauleternity.marketdaily.model.market.GoldSignals.Symbol;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.Instant;
import java.util.List;

/** 保存可信来源提供的黄金 K 线，按品种、周期、时间和来源幂等更新。 */
@Mapper
public interface GoldSignalMapper {

    /** 更新同一来源的 K 线；缺成交量的重放保留已导入的成交数据。 */
    @Insert("""
            INSERT INTO gold_signal_bar (symbol, period, source, start_time, trading_date,
                open_price, high_price, low_price, close_price, volume, buy_volume, sell_volume)
            VALUES (#{symbol}, #{period}, #{source}, #{startTime}, #{tradingDate},
                #{openPrice}, #{highPrice}, #{lowPrice}, #{closePrice}, #{volume}, #{buyVolume}, #{sellVolume}) AS incoming
            ON DUPLICATE KEY UPDATE trading_date = incoming.trading_date,
                open_price = incoming.open_price, high_price = incoming.high_price,
                low_price = incoming.low_price, close_price = incoming.close_price,
                buy_volume = IF(incoming.volume IS NULL, gold_signal_bar.buy_volume, incoming.buy_volume),
                sell_volume = IF(incoming.volume IS NULL, gold_signal_bar.sell_volume, incoming.sell_volume),
                volume = COALESCE(incoming.volume, gold_signal_bar.volume)
            """)
    void save(Bar bar);

    /** 按时间顺序读半开窗口，列顺序与记录构造参数一致。 */
    @Select("""
            SELECT symbol, period, source, start_time, trading_date,
                   open_price, high_price, low_price, close_price, volume, buy_volume, sell_volume
            FROM gold_signal_bar WHERE symbol = #{symbol} AND period = #{period}
              AND start_time >= #{from} AND start_time < #{to}
            ORDER BY start_time, source
            """)
    List<Bar> range(@Param("symbol") Symbol symbol, @Param("period") Period period,
                    @Param("from") Instant from, @Param("to") Instant to);
}
