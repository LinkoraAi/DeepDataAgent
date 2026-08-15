package com.linkroa.deepdataagent.runtime.infrastructure.util;

import org.apache.commons.lang3.StringUtils;

import java.util.regex.Pattern;

/**
 * 工具入参/出参脱敏与截断工具。
 * <p>与 datasource BC 的 {@code LogMasker} 语义一致（API Key / Bearer Token 脱敏），
 * 另附大结果截断策略，runtime 上下文不跨 BC 直接依赖 datasource 基建。</p>
 */
public final class PayloadSanitizer {

    /** 单值落库最大长度（超出截断并追加标记） */
    private static final int MAX_LENGTH = 2000;

    /** 工具输出 head+tail 截断上限（默认 16KB） */
    private static final int TOOL_OUTPUT_LIMIT = 16 * 1024;

    /** head+tail 截断分隔标记 */
    private static final String TRUNCATE_MARKER = "\n...[已省略中间输出]...\n";

    private static final String MASK = "***";
    private static final Pattern API_KEY_PATTERN = Pattern.compile("(sk-)[\\w-]+");
    private static final Pattern BEARER_PATTERN = Pattern.compile("(Bearer\\s+)\\S+");

    private PayloadSanitizer() {
    }

    /**
     * 脱敏 + 截断。
     */
    public static String sanitize(String text) {
        String masked = maskSecrets(text);
        if (masked == null || masked.length() <= MAX_LENGTH) {
            return masked;
        }
        return masked.substring(0, MAX_LENGTH) + "...(truncated)";
    }

    /**
     * head+tail 截断结果。
     *
     * @param value     截断后的文本
     * @param truncated 是否发生了截断
     */
    public record Truncated(String value, boolean truncated) {
    }

    /**
     * 大工具输出 head+tail 截断：保留头部与尾部，中间以标记省略
     * （工具结果 16KB 上限语义，与应用层 AgentRunState 的截断窗口同源）。执行器订阅端据此一次性输出。
     */
    public static Truncated truncateHeadTail(String text) {
        return truncateHeadTail(text, TOOL_OUTPUT_LIMIT);
    }

    /**
     * 指定上限的 head+tail 截断。
     */
    public static Truncated truncateHeadTail(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return new Truncated(text, false);
        }
        int markerLength = TRUNCATE_MARKER.length();
        int headKeep = Math.max(1, (maxLength - markerLength) * 3 / 4);
        int tailKeep = maxLength - markerLength - headKeep;
        if (tailKeep < 1) {
            headKeep = maxLength - markerLength;
            tailKeep = 0;
        }
        String head = maskSecrets(text.substring(0, headKeep));
        String tail = tailKeep > 0 ? maskSecrets(text.substring(text.length() - tailKeep)) : "";
        return new Truncated(head + TRUNCATE_MARKER + tail, true);
    }

    /**
     * 仅脱敏（不截断）。
     */
    public static String maskSecrets(String text) {
        if (StringUtils.isEmpty(text)) {
            return text;
        }
        String result = API_KEY_PATTERN.matcher(text).replaceAll("$1" + MASK);
        return BEARER_PATTERN.matcher(result).replaceAll("$1" + MASK);
    }
}