package com.pauleternity.marketdaily.service.news;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;
import java.util.stream.Collectors;

/** 对每条链接生成稳定主键，并在相近时间内归并同一事件。 */
@Component
class NewsDedupService {

    // 时间窗口过大容易把数天后同标题的市场例行更新归到同一个事件。
    static final Duration EVENT_WINDOW = Duration.ofHours(36);

    // 通用话题和虚词不能单独作为同一事件的证据。
    private static final Set<String> COMMON = Set.of("the", "and", "for", "with", "from", "over", "into",
            "after", "before", "says", "this", "that", "have", "will", "fed", "federal", "reserve",
            "nasdaq", "gold", "bitcoin", "ethereum", "crypto", "update", "markets", "market");

    /** HTTP 或 HTTPS 的同一规范链接视为同一来源文章。 */
    String urlHash(String url) {
        return hash(url.substring(url.indexOf("://") + 3));
    }

    /** 新事件以归一化标题、时间和首条链接生成稳定 ID。 */
    String eventHash(String title, Instant newsAt, String urlHash) {
        return hash(title + "|" + newsAt + "|" + urlHash);
    }

    /** 精确标题或足够多的具体词重合才算同一事件，避免将同一主题的不同报道错误合并。 */
    boolean sameEvent(String title, Instant at, String otherTitle, Instant otherAt) {
        if (Duration.between(at, otherAt).abs().compareTo(EVENT_WINDOW) > 0) {
            return false;
        }
        if (title.equals(otherTitle)) {
            return true;
        }
        Set<String> left = significantWords(title);
        Set<String> right = significantWords(otherTitle);
        if (!numbers(title).equals(numbers(otherTitle))) {
            return false;
        }
        var shared = new HashSet<>(left);
        shared.retainAll(right);
        int union = left.size() + right.size() - shared.size();
        return shared.size() >= 4 && shared.size() * 100 >= union * 72;
    }

    /** 仅比较有区分度的词，短标题仍可通过精确规则去重。 */
    private Set<String> significantWords(String title) {
        return Arrays.stream(title.split(" ")).filter(word -> word.length() >= 3 && !COMMON.contains(word))
                .collect(Collectors.toSet());
    }

    /** 数字不同的标题可能是不同的统计发布或价格快讯。 */
    private Set<String> numbers(String title) {
        return Arrays.stream(title.split(" ")).filter(word -> !word.isEmpty()
                        && word.codePoints().allMatch(Character::isDigit))
                .collect(Collectors.toSet());
    }

    /** 数据库以 64 位十六进制字符存储 SHA-256。 */
    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("运行环境缺少 SHA-256", ex);
        }
    }
}
