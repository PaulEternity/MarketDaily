package com.pauleternity.marketdaily.client.news;

import com.pauleternity.marketdaily.shared.SourceHttp;
import org.jsoup.Jsoup;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** 两家官方来源都在列表公开标题、链接和北京时间日期。 */
@Configuration(proxyBeanMethods = false)
class DomesticNewsFeeds {
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    @Bean
    NewsFeed sgeNewsFeed(SourceHttp http) {
        return new OfficialList(http, "SGE_NOTICE", URI.create("https://www.sge.com.cn/jjsnotice"),
                "a[href^='/jjsnotice/']:has(span.txt)", "span.txt", "span.fr",
                Pattern.compile("/jjsnotice/[0-9]+"));
    }

    @Bean
    NewsFeed nbsNewsFeed(SourceHttp http) {
        return new OfficialList(http, "NBS_RELEASE", URI.create("https://www.stats.gov.cn/sj/zxfb/"),
                ".list-content > ul > li:has(> a.pc_1600)", "a.pc_1600", "span",
                Pattern.compile("/sj/zxfb/[0-9]{6}/t[0-9]{8}_[0-9]+\\.html"));
    }

    record OfficialList(SourceHttp http, String source, URI page, String rows,
                        String titleSelector, String dateSelector, Pattern articlePath) implements NewsFeed {
        @Override
        public List<Item> fetch(int hours) {
            return parse(http.readBody(http.get().uri(page)));
        }

        List<Item> parse(String html) {
            var document = Jsoup.parse(html, page.toString());
            var result = new ArrayList<Item>();
            for (var row : document.select(rows)) {
                var title = row.selectFirst(titleSelector);
                var date = row.selectFirst(dateSelector);
                var anchor = row.is("a") ? row : title;
                if (title == null || date == null || title.text().isBlank()) {
                    continue;
                }
                try {
                    URI article = page.resolve(anchor.attr("href"));
                    if (!"https".equals(article.getScheme()) || !page.getHost().equals(article.getHost())
                            || article.getRawUserInfo() != null || article.getPort() != -1
                            || !articlePath.matcher(article.getPath()).matches()) {
                        continue;
                    }
                    String dateText = date.text();
                    if (dateText.matches("[0-9]{2}-[0-9]{2}-[0-9]{2}")) {
                        dateText = "20" + dateText;
                    }
                    LocalDate day = LocalDate.parse(dateText);
                    // 用北京时间日首参与现有窗口查询。前端对这两个来源只展示日期。
                    result.add(new Item(title.text(), article.toString(), "zh", null,
                            day.atStartOfDay(SHANGHAI).toInstant(), null));
                } catch (IllegalArgumentException | DateTimeParseException ignored) {
                    // 单条链接或日期异常不影响同页其他记录。
                }
            }
            if (result.isEmpty()) {
                throw new IllegalStateException("官方新闻列表缺少有效条目：" + source);
            }
            return List.copyOf(result);
        }
    }
}
