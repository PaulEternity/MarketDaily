package com.pauleternity.marketdaily;

import com.fasterxml.jackson.databind.JsonNode;
import com.pauleternity.marketdaily.market.MarketPriceMapper;
import com.pauleternity.marketdaily.news.NewsMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration"
)
class HealthEndpointTest {

    @Autowired
    private TestRestTemplate client;

    // 此冒烟测试不启动数据库，为行情服务提供 Mapper 占位。
    @MockitoBean
    private MarketPriceMapper marketPrices;

    // 数据库被禁用时，替换新闻模块依赖的 Mapper。
    @MockitoBean
    private NewsMapper news;

    @Test
    void reportsUp() {
        var response = client.getForEntity("/actuator/health", JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().path("status").asText()).isEqualTo("UP");
    }
}
