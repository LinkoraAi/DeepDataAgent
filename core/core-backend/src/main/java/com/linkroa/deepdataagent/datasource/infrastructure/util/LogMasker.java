package com.linkroa.deepdataagent.datasource.infrastructure.util;

import org.apache.commons.lang3.StringUtils;

import java.util.regex.Pattern;

/**
 * 日志脱敏工具类
 * <p>对可能包含敏感信息（API Key、Bearer Token）的文本进行脱敏处理。</p>
 */
public final class LogMasker {

    /** 脱敏标记 */
    private static final String MASK_REPLACEMENT = "***";

    /** API Key 匹配模式：匹配 sk- 开头的密钥（预期格式：sk-[a-zA-Z0-9-]+） */
    private static final Pattern API_KEY_PATTERN = Pattern.compile("(sk-)[\\w-]+");

    /** Bearer Token 匹配模式：匹配 Bearer 后面的令牌 */
    private static final Pattern BEARER_PATTERN = Pattern.compile("(Bearer\\s+)\\S+");

    /** 私有构造器防止实例化 */
    private LogMasker() {
    }

    /**
     * 对文本进行脱敏处理
     *
     * @param text 原始文本
     * @return 脱敏后的文本
     */
    public static String mask(String text) {
        if (StringUtils.isEmpty(text)) {
            return text;
        }
        String result = API_KEY_PATTERN.matcher(text).replaceAll("$1" + MASK_REPLACEMENT);
        result = BEARER_PATTERN.matcher(result).replaceAll("$1" + MASK_REPLACEMENT);
        return result;
    }
}
