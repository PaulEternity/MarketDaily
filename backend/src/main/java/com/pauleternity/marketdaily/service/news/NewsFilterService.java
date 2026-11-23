package com.pauleternity.marketdaily.service.news;

import com.pauleternity.marketdaily.client.news.NewsFeed;
import com.pauleternity.marketdaily.model.news.NewsMarket;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.text.Normalizer;
import java.time.Instant;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** 规范化外部新闻并按可核对的标题关键词分类。 */
@Component
class NewsFilterService {

    // 关键词按市场分组，宏观主题可同时归入黄金和纳指。
    private static final Map<NewsMarket, Set<String>> KEYWORDS = Map.of(
            NewsMarket.GOLD, Set.of("gold", "bullion", "xauusd", "federal reserve", "fed", "fomc", "inflation",
                    "interest rate", "interest rates", "rate cuts", "rate cut", "us dollar", "geopolitical risk",
                    "cpi", "nonfarm payrolls", "黄金", "金价", "美联储", "通胀", "降息", "非农",
                    "crude oil", "brent", "wti", "opec", "petroleum status report", "joint ministerial monitoring committee",
                    "monetary policy", "money market operations", "policy rate", "real yield", "real yields",
                    "原油", "油价", "布伦特", "利率决议", "加息", "实际收益率", "实际利率", "日本央行", "欧洲央行"),
            NewsMarket.NASDAQ, Set.of("nasdaq", "tech stocks", "technology stocks", "nvidia", "ai industry",
                    "federal reserve", "fed", "fomc", "inflation", "interest rate", "interest rates", "us economy",
                    "cpi", "nonfarm payrolls", "纳斯达克", "科技股", "英伟达", "美联储", "通胀", "降息", "非农"),
            NewsMarket.CRYPTO, Set.of("bitcoin", "ethereum", "crypto", "cryptocurrency", "cryptocurrencies",
                    "btc", "eth", "比特币", "以太坊", "加密货币", "币圈"));

    // 标题中的广告和导流样式，不用广义的市场用词判断广告。
    private static final Set<String> PROMOTIONS = Set.of("sponsored content", "affiliate link", "coupon code",
            "promo code", "advertorial", "paid promotion", "buy now", "广告推广", "优惠码");
    private static final Set<String> TRACKERS = Set.of("fbclid", "gclid", "msclkid", "mc_cid", "mc_eid");
    private static final Set<String> SGE_MARKET_NOTICES = Set.of(
            "贵金属", "风险控制", "保证金", "涨跌停板", "休市", "交易时间", "挂牌", "交割");
    private static final Set<String> DOMESTIC_MACRO = Set.of(
            "国民经济运行", "国内生产总值", "居民消费价格", "工业生产者出厂价格", "采购经理指数", "规模以上工业增加值");
    private static final Pattern NON_WORD = Pattern.compile("[^\\p{L}\\p{N}]+");
    private static final Pattern HTML = Pattern.compile("<[^>]*>");

    /** 返回通过窗口、链接和主题校验的文章，无法确认时间或相关性时丢弃。 */
    Candidate filter(NewsFeed.Item item, Instant from, Instant through, boolean monetaryFeed) {
        // GDELT 只有收录时间时仍可列入窗口，同时保留发布时间为空。
        Instant newsAt = item.publishedAt() == null ? item.seenAt() : item.publishedAt();
        if (newsAt == null || newsAt.isBefore(from) || newsAt.isAfter(through)) {
            return null;
        }
        String title = plainText(item.title(), 512);
        if (title == null) {
            return null;
        }
        String normalized = truncate(normalize(title), 512);
        if (normalized.codePointCount(0, normalized.length()) < 8 || matchesAny(normalized, PROMOTIONS)) {
            return null;
        }
        URI url = canonicalUrl(item.url());
        if (url == null) {
            return null;
        }

        // 政策专用 RSS 本身是宏观主题证据，即使标题未重复写明 Federal Reserve。
        Set<NewsMarket> markets = EnumSet.noneOf(NewsMarket.class);
        int matches = 0;
        for (var entry : KEYWORDS.entrySet()) {
            int count = (int) entry.getValue().stream().filter(term -> contains(normalized, term)).count();
            if (count > 0) {
                markets.add(entry.getKey());
                matches += count;
            }
        }
        if (monetaryFeed) {
            markets.add(NewsMarket.GOLD);
            markets.add(NewsMarket.NASDAQ);
        }
        if (("www.sge.com.cn".equals(url.getHost()) && url.getPath().startsWith("/jjsnotice/")
                && matchesAny(normalized, SGE_MARKET_NOTICES))
                || ("www.stats.gov.cn".equals(url.getHost()) && url.getPath().startsWith("/sj/zxfb/")
                && matchesAny(normalized, DOMESTIC_MACRO))) {
            markets.add(NewsMarket.GOLD);
            matches++;
        }
        if (markets.isEmpty()) {
            return null;
        }
        return new Candidate(title, normalized, url.toASCIIString(), url.getHost().toLowerCase(Locale.ROOT),
                plainText(item.language(), 35), plainText(item.summary(), 500),
                item.publishedAt(), item.seenAt(), newsAt, matches, Set.copyOf(markets));
    }

    /** 统一 Unicode、大小写和标点供事件比较。 */
    static String normalize(String value) {
        return NON_WORD.matcher(Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT))
                .replaceAll(" ").strip();
    }

    /** 对英文按完整单词或词组匹配，中文按连续短语匹配。 */
    static boolean matchesAny(String normalized, Set<String> terms) {
        return terms.stream().anyMatch(term -> contains(normalized, term));
    }

    /** 英文边界可避免把 ETH 误判在其他单词中。 */
    private static boolean contains(String normalized, String term) {
        return term.codePoints().allMatch(cp -> cp < 128)
                ? (" " + normalized + " ").contains(" " + term + " ")
                : normalized.contains(term);
    }

    /** 清除 RSS 摘要的标签，限制保留长度并保留真实文本来源。 */
    private String plainText(String value, int limit) {
        if (value == null) {
            return null;
        }
        String text = HTML.matcher(value).replaceAll(" ").replaceAll("\\s+", " ").strip();
        if (text.isEmpty()) {
            return null;
        }
        return truncate(text, limit);
    }

    /** 按 Unicode 字符限制长度，避免在表情或扩展汉字的中间截断。 */
    private String truncate(String text, int limit) {
        return text.codePointCount(0, text.length()) > limit
                ? text.substring(0, text.offsetByCodePoints(0, limit)) : text;
    }

    /** 仅接受 HTTP(S) 新闻链接，移除片段、常见跟踪参数与默认端口。 */
    private URI canonicalUrl(String value) {
        if (value == null || value.length() > 4096) {
            return null;
        }
        try {
            URI input = URI.create(value.strip()).normalize();
            String scheme = input.getScheme();
            String host = input.getHost();
            if (scheme == null || host == null || host.length() > 255 || input.getRawUserInfo() != null) {
                return null;
            }
            scheme = scheme.toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https")) {
                return null;
            }
            int port = input.getPort();
            if (port > 65535) {
                return null;
            }
            if (port == (scheme.equals("https") ? 443 : 80)) {
                port = -1;
            }
            String query = input.getRawQuery();
            // 保留业务参数和值的原始编码，避免改写原文链接。
            if (query != null) {
                query = Arrays.stream(query.split("&")).filter(part -> {
                    String key = part.split("=", 2)[0].toLowerCase(Locale.ROOT);
                    return !key.startsWith("utm_") && !TRACKERS.contains(key);
                }).collect(Collectors.joining("&"));
            }
            String path = input.getRawPath().isEmpty() ? "/" : input.getRawPath();
            String url = scheme + "://" + host.toLowerCase(Locale.ROOT) + (port == -1 ? "" : ":" + port)
                    + path + (query == null || query.isEmpty() ? "" : "?" + query);
            return url.length() <= 4096 ? URI.create(url) : null;
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    /** 已校验的新闻和评分需要的匹配次数，发布时间和收录时间可分别为空。 */
    record Candidate(String title, String normalized, String url, String host, String language, String summary,
                     Instant publishedAt, Instant seenAt, Instant newsAt, int matches, Set<NewsMarket> markets) {
    }
}
