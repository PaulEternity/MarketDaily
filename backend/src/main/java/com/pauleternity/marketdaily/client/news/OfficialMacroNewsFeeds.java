package com.pauleternity.marketdaily.client.news;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.regex.Pattern;

/** 黄金日报可引用的央行声明和石油官方公告，按来源隔离失败。 */
@Configuration(proxyBeanMethods = false)
class OfficialMacroNewsFeeds {
    private static final String BOJ = "https://www.boj.or.jp";
    private static final String OPEC = "https://www.opec.org/press-releases.html";
    private static final String EIA = "https://www.eia.gov/petroleum/supply/weekly/";
    private static final Pattern BOJ_DATE = Pattern.compile("/en/mopo/mpmdeci/mpr_[0-9]{4}/k([0-9]{6})[a-z]\\.pdf");
    private static final Pattern OPEC_DATE = Pattern.compile("/pr-detail/[0-9]+-([0-9]{1,2}-[a-z]+-[0-9]{4})\\.html", Pattern.CASE_INSENSITIVE);
    private static final DateTimeFormatter OPEC_DAY = new DateTimeFormatterBuilder().parseCaseInsensitive()
            .appendPattern("d-MMMM-uuuu").toFormatter(Locale.ENGLISH);
    private static final Pattern EIA_DATE = Pattern.compile("/petroleum/supply/weekly/archive/[0-9]{4}/[0-9_]+/wpsr_([0-9]{4}_[0-9]{2}_[0-9]{2})\\.php");

    /** 欧洲央行新闻 RSS 中只取货币政策相关公告。 */
    @Bean
    NewsFeed ecbPolicyFeed(WebClient.Builder builder) {
        return new OfficialFeed(builder.build(), "ECB_POLICY", "https://www.ecb.europa.eu/rss/press.html",
                xml -> FedNewsFeed.parse(xml).stream().filter(item -> {
                    String title = item.title().toLowerCase(Locale.ROOT);
                    return title.contains("monetary policy") || title.contains("key ecb interest rates");
                }).toList());
    }

    /** 日本央行年度列表提供声明原文及交易日。 */
    @Bean
    NewsFeed bojPolicyFeed(WebClient.Builder builder) {
        return new OfficialFeed(builder.build(), "BOJ_POLICY", BOJ + "/en/mopo/mpmdeci/state_%d/index.htm",
                OfficialMacroNewsFeeds::bojItems);
    }

    /** 读取 OPEC 公告列表。 */
    @Bean
    NewsFeed opecPressFeed(WebClient.Builder builder) {
        return new OfficialFeed(builder.build(), "OPEC_PRESS", OPEC, OfficialMacroNewsFeeds::opecItems);
    }

    /** 读取 EIA 周报归档链接，以发布日期区分各期。 */
    @Bean
    NewsFeed eiaWeeklyFeed(WebClient.Builder builder) {
        return new OfficialFeed(builder.build(), "EIA_WEEKLY", EIA, OfficialMacroNewsFeeds::eiaItems);
    }

    /** 日本央行 PDF 文件名包含发布日期，剔除参考附件。 */
    static List<NewsFeed.Item> bojItems(String html) {
        List<NewsFeed.Item> items = new ArrayList<>();
        for (var link : Jsoup.parse(html, BOJ).select("a[href*=/en/mopo/mpmdeci/mpr_]")) {
            var date = BOJ_DATE.matcher(link.attr("href"));
            if (date.matches() && !link.text().startsWith("(Reference)")) {
                String title = link.text().replaceFirst("\\s*\\[PDF[^]]*]$", "");
                items.add(dated("BOJ: " + title, link.absUrl("href"),
                        LocalDate.parse("20" + date.group(1), DateTimeFormatter.BASIC_ISO_DATE), "Asia/Tokyo"));
            }
        }
        return items;
    }

    /** 公告日期取自 OPEC 原文链接，标题取同一条公告的标题元素。 */
    static List<NewsFeed.Item> opecItems(String html) {
        List<NewsFeed.Item> items = new ArrayList<>();
        for (var link : Jsoup.parse(html, OPEC).select("a[href*=pr-detail/]")) {
            // 同时兼容站内相对链接和指向同一主机的绝对链接。
            var uri = java.net.URI.create(link.absUrl("href"));
            if (!"www.opec.org".equals(uri.getHost())) continue;
            var date = OPEC_DATE.matcher(uri.getPath());
            if (!date.matches()) continue;
            Element card = link.parent();
            while (card != null && card.select("a[href*=pr-detail/]").size() == 1) {
                var title = card.selectFirst("h1,h2,h3,h4,h5");
                if (title != null) {
                    items.add(dated("OPEC: " + title.text(), uri.toString(), LocalDate.parse(date.group(1), OPEC_DAY), "Europe/Vienna"));
                    break;
                }
                card = card.parent();
            }
        }
        return items;
    }

    /** 报告归档路径含真实日期，用独立链接保存每一期。 */
    static List<NewsFeed.Item> eiaItems(String html) {
        List<NewsFeed.Item> items = new ArrayList<>();
        for (var link : Jsoup.parse(html, EIA).select("a[href*=/weekly/archive/]")) {
            var date = EIA_DATE.matcher(link.attr("href"));
            if (date.matches()) {
                LocalDate day = LocalDate.parse(date.group(1).replace('_', '-'));
                items.add(dated("EIA Weekly Petroleum Status Report " + day, link.absUrl("href"), day, "America/New_York"));
            }
        }
        return items;
    }

    /** 官网页面只有日期时，用来源时区的日首作为窗口归档时间。 */
    private static NewsFeed.Item dated(String title, String link, LocalDate date, String zone) {
        return new NewsFeed.Item(title, link, "en", null, date.atStartOfDay(ZoneId.of(zone)).toInstant(), null);
    }

    /** 各来源沿用新闻入库窗口，单次网络等待限制为 4 秒。 */
    private record OfficialFeed(WebClient client, String source, String endpoint,
                                Function<String, List<Item>> parse) implements NewsFeed {
        @Override
        public List<Item> fetch(int hours) {
            var url = endpoint.contains("%d") ? endpoint.formatted(LocalDate.now(ZoneId.of("Asia/Tokyo")).getYear()) : endpoint;
            String body = client.get().uri(url).retrieve().bodyToMono(String.class).timeout(Duration.ofSeconds(4)).block();
            if (body == null || body.isBlank()) throw new IllegalStateException("官方公告响应为空");
            return parse.apply(body);
        }
    }
}
