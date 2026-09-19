package com.linkroa.deepdataagent.shared.security;

import org.apache.commons.lang3.StringUtils;

import java.util.Collection;
import java.util.regex.Pattern;

/**
 * 跨 BC 通用脱敏与截断工具（{@code shared.security}，{@code LogMasker} 同包先例）。
 * <p>与 datasource BC 的 {@code LogMasker} 语义一致（API Key / Bearer Token 脱敏），
 * 另附大结果截断策略；供应用层静态调用，消除 {@code application → infrastructure.util}
 * 技术依赖（fix-runtime-layering 3.1，平移自 runtime {@code PayloadSanitizer}）。</p>
 */
public final class SecretMasker {

    /** 单值落库最大长度（超出截断并追加标记） */
    private static final int MAX_LENGTH = 2000;

    /** 工具输出 head+tail 截断上限（默认 16KB） */
    private static final int TOOL_OUTPUT_LIMIT = 16 * 1024;

    /** head+tail 截断分隔标记 */
    private static final String TRUNCATE_MARKER = "\n...[已省略中间输出]...\n";

    private static final String MASK = "***";
    /** 已知明文精确掩码的替换标记（与形态脱敏的 {@code ***} 区分，便于审计溯源掩码来源）。 */
    private static final String EXACT_VALUE_MASK = "****";
    private static final Pattern API_KEY_PATTERN = Pattern.compile("(sk-)[\\w-]+");
    private static final Pattern BEARER_PATTERN = Pattern.compile("(Bearer\\s+)\\S+");

    private SecretMasker() {
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

    /**
     * 按已知明文精确掩码：把 {@code text} 中出现的 {@code knownSecrets} 每个非空白值子串
     * 替换为固定标记 {@code ****}（同一值多次出现全部替换）。
     * <p>形态正则（{@link #maskSecrets}）只覆盖 {@code sk-*} 与 {@code Bearer <x>} 两类形态，
     * 无法命中 GitHub 风格 token、裸 JWT 等任意字节的明文；本方法以「装配期已知本轮挂载凭据
     * 明文」为输入做精确兜底，供工具结果落库与 SSE 广播前一次性消除已知秘密的回显。</p>
     * <p>与 {@link #maskSecrets} 互补而非互斥：调用方可先形态脱敏再精确掩码（掩码标记不同，
     * 便于审计区分来源）。{@code text} 为 null / 空或 {@code knownSecrets} 为 null / 空集合时
     * 原样返回；空白凭据值直接跳过（防止空串把整篇文本打碎）。</p>
     *
     * @param text         待掩码文本（可空）
     * @param knownSecrets 本轮已知明文凭据集合（可空/空；逐元素判空白后跳过）
     * @return 掩码后的文本（无需掩码时为原文）
     */
    public static String maskExactValues(String text, Collection<String> knownSecrets) {
        if (StringUtils.isEmpty(text) || knownSecrets == null || knownSecrets.isEmpty()) {
            return text;
        }
        String result = text;
        for (String secret : knownSecrets) {
            if (StringUtils.isBlank(secret)) {
                continue;
            }
            result = result.replace(secret, EXACT_VALUE_MASK);
        }
        return result;
    }
}