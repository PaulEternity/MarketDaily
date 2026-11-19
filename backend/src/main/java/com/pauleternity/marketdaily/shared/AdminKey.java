package com.pauleternity.marketdaily.shared;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** 手动采集入口共用的管理密钥校验。 */
@Component
public class AdminKey {

    // 后端进程持有的密钥字节，空值表示未启用管理入口。
    private final byte[] expected;

    /** 从既有配置项读取密钥，使行情和新闻入口使用相同的凭证。 */
    public AdminKey(@Value("${MARKET_ADMIN_KEY:}") String key) {
        this.expected = key.isBlank() ? new byte[0] : key.getBytes(StandardCharsets.UTF_8);
    }

    /** 拒绝缺少或不匹配的密钥。 */
    public void require(String supplied) {
        if (expected.length == 0 || supplied == null
                || !MessageDigest.isEqual(expected, supplied.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "手动采集未授权");
        }
    }
}
