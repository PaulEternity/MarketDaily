package com.pauleternity.marketdaily;

import com.pauleternity.marketdaily.knowledge.KnowledgeTermMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

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
}