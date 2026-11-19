package com.pauleternity.marketdaily;

import com.pauleternity.marketdaily.knowledge.KnowledgeTermMapper;
import com.pauleternity.marketdaily.market.MarketPriceMapper;
import com.pauleternity.marketdaily.market.MarketSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@EnabledIfEnvironmentVariable(named = "MARKETDAILY_DB_TEST", matches = "true")
class MySqlIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private KnowledgeTermMapper terms;

    @Autowired
    private HealthEndpoint health;

    @Autowired
    private MarketPriceMapper marketPrices;

    @Test
    void migratesMySqlAndReadsSeededKnowledge() {
        assertThat(jdbc.queryForObject("SELECT VERSION()", String.class)).matches("8\\.(0|4)\\..*");
        assertThat(jdbc.queryForObject("SELECT @@session.time_zone", String.class)).isEqualTo("+00:00");
        assertThat(jdbc.queryForList("SHOW TABLES", String.class)).contains(
                "market_price", "market_news", "news_market_relation", "daily_report",
                "task_execution", "knowledge_term", "flyway_schema_history");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_term", Long.class)).isEqualTo(10);

        var cpi = terms.findByTerm("CPI");
        assertThat(cpi).isNotNull();
        assertThat(cpi.explanation()).startsWith("消费者价格指数");
        assertThat(terms.findByTerm("不存在的术语")).isNull();
        assertThat(health.health().getStatus()).isEqualTo(Status.UP);
    }

    /** 应用真实迁移后的表保留同日有效快照，并用新状态报告后续失败。 */
    @Test
    @Transactional
    void upsertsDailyPriceAndPreservesItAfterFailure() {
        // 用固定业务日期和毫秒时间验证 MyBatis 与 MySQL 的 UTC 往返。
        var day = LocalDate.of(2000, 1, 4);
        var observed = Instant.parse("2000-01-04T00:30:00.123Z");
        var gold = new MarketSnapshot("XAUUSD", "黄金", new BigDecimal("2001.23456789"), null,
                null, null, "USD", "PREVIOUS_DAILY_SAMPLE", day, observed, observed,
                day, "Gold API", "AVAILABLE");
        marketPrices.save(gold);
        marketPrices.save(gold);
        marketPrices.save(new MarketSnapshot("XAUUSD", "黄金", null, null, null, null,
                "USD", "PREVIOUS_DAILY_SAMPLE", null, null, observed.plusSeconds(60),
                day, "Gold API", "FAILED"));

        var stored = marketPrices.onDate("XAUUSD", "Gold API", day);
        assertThat(stored.price()).isEqualByComparingTo(gold.price());
        assertThat(stored.observedAt()).isEqualTo(observed);
        assertThat(stored.collectedAt()).isEqualTo(observed);
        assertThat(stored.status()).isEqualTo("FAILED");
        assertThat(marketPrices.history("XAUUSD", "Gold API", day.minusDays(6), day)).containsExactly(stored);
    }
}
