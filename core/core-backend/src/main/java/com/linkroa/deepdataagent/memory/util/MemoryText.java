package com.linkroa.deepdataagent.memory.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * memory 模块的文本工具类。
 *
 * <p>集中提供 SHA-256、关键词切分、简单文本相关性评分和文件名 slug 生成等能力，
 * 避免索引、提取和文件层各自维护一套不一致的字符串处理逻辑。</p>
 */
public final class MemoryText {

    private static final Pattern TOKEN_PATTERN = Pattern.compile("[\\p{IsHan}]|[a-zA-Z0-9_\\-]+");

    private MemoryText() {
    }

    public static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    public static Set<String> tokens(String text) {
        Set<String> tokens = new LinkedHashSet<>();
        if (text == null || text.isBlank()) {
            return tokens;
        }
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        Matcher matcher = TOKEN_PATTERN.matcher(normalized);
        while (matcher.find()) {
            String token = matcher.group();
            if (!token.isBlank()) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    public static double lexicalScore(String query, String content) {
        Set<String> queryTokens = tokens(query);
        Set<String> contentTokens = tokens(content);
        if (queryTokens.isEmpty() || contentTokens.isEmpty()) {
            return 0.0;
        }

        int hits = 0;
        for (String token : queryTokens) {
            if (contentTokens.contains(token)) {
                hits++;
            }
        }

        double recall = (double) hits / queryTokens.size();
        double precision = (double) hits / contentTokens.size();
        double phraseBoost = content.toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT).strip()) ? 0.25 : 0.0;
        return Math.min(1.0, (recall * 0.75) + (precision * 0.25) + phraseBoost);
    }

    public static String slug(String text, String fallback) {
        if (text == null || text.isBlank()) {
            return fallback;
        }
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFKD)
                .replaceAll("[^a-zA-Z0-9\\u4e00-\\u9fa5]+", "-")
                .replaceAll("^-+|-+$", "")
                .toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) {
            return fallback;
        }
        return normalized.length() > 48 ? normalized.substring(0, 48) : normalized;
    }

    public static String firstNonBlank(String first, String second, String fallback) {
        if (first != null && !first.isBlank()) {
            return first.strip();
        }
        if (second != null && !second.isBlank()) {
            return second.strip();
        }
        return fallback;
    }
}
