package com.pauleternity.marketdaily;

import com.pauleternity.marketdaily.mapper.KnowledgeTermMapper;
import com.pauleternity.marketdaily.mapper.MarketPriceMapper;
import com.pauleternity.marketdaily.mapper.NewsMapper;
import com.pauleternity.marketdaily.model.market.MarketSnapshot;
import com.pauleternity.marketdaily.model.news.NewsArticle;
import com.pauleternity.marketdaily.model.news.NewsItem;
import com.pauleternity.marketdaily.model.news.NewsMarket;
import com.pauleternity.marketdaily.service.news.NewsService;
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
import java.util.Set;

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

    @Autowired
    private NewsMapper news;

    @Autowired
    private NewsService newsService;

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

    /** 要点先选高分事件，再按时间排序，全部报道仍保留同事件的多个来源。 */
    @Test
    @Transactional
    void listsDistinctHighlightsAndChronologicalReports() {
        // 使用固定历史窗口，避免与每日采集数据相互影响。
        Instant through = Instant.parse("2000-02-02T12:00:00Z");
        saveNews(500, 900, through.minusSeconds(7200), 95, null);
        saveNews(501, 900, through.minusSeconds(1800), 80, NewsMarket.GOLD);
        saveNews(502, 901, through.minusSeconds(3600), 90, NewsMarket.GOLD);
        saveNews(503, 902, through.minusSeconds(1000), 99, NewsMarket.CRYPTO);
        saveNews(504, 903, through.minusSeconds(90_000), 99, NewsMarket.GOLD);

        assertThat(newsService.highlights(through, NewsMarket.GOLD)).extracting(NewsItem::title)
                .containsExactly("报道 502", "报道 500");
        assertThat(newsService.highlights(through, NewsMarket.GOLD).getLast().markets()).containsExactly(NewsMarket.GOLD);

        Instant from = through.minusSeconds(86_400);
        assertThat(newsService.page(from, through, NewsMarket.GOLD, 1, 10, true).items()).extracting(NewsItem::title)
                .containsExactly("报道 501", "报道 502", "报道 500");
        assertThat(newsService.page(from, through, NewsMarket.GOLD, 1, 10, false).items()).extracting(NewsItem::title)
                .containsExactly("报道 500", "报道 502", "报道 501");
    }

    /** 写入指定事件和可选市场关系，新闻时间同时用于生成查询列。 */
    private void saveNews(int id, int event, Instant at, int score, NewsMarket market) {
        // 测试编号填满哈希列，便于让两篇独立报道共享同一个事件。
        String urlHash = String.format("%064x", id);
        String eventHash = String.format("%064x", event);
        Set<NewsMarket> markets = market == null ? Set.of() : Set.of(market);
        news.save(new NewsArticle("报道 " + id, "报道 " + id, "https://example.test/" + id,
                urlHash, "example.test", "zh", null, at, null, at, at, eventHash,
                BigDecimal.valueOf(score), markets));
        if (market != null) {
            news.saveMarket(news.byUrl(urlHash).id(), market);
        }
    }
}
