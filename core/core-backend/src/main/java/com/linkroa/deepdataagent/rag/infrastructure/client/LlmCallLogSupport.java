package com.linkroa.deepdataagent.rag.infrastructure.client;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.springframework.util.DigestUtils;

import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;

/**
 * 模型调用日志字段组装工具（观测专用，最小化口径）。
 * <p>集中提供耗时、字符数与提示词摘要标识，并统一包裹日志输出：
 * <b>字段组装或日志输出发生任何运行时异常都必须被吞掉</b>，观测不得改变摄入结果。</p>
 * <p>摘要标识只取 MD5 十六进制前缀——日志 MUST NOT 输出端点主机、提示词正文与温度等
 * 敏感或超长字段（回放原文经 promptMd5 关联 llm_cache）。</p>
 *
 * @author DeepDataAgent
 */
final class LlmCallLogSupport {

    /** 摘要标识保留的十六进制字符数 */
    private static final int DIGEST_LENGTH = 8;

    /** 字段缺失占位符 */
    private static final String ABSENT = "-";

    /**
     * 工具类私有构造器。
     */
    private LlmCallLogSupport() {
    }

    /**
     * 计算耗时毫秒（纳秒起点 → 当前）。
     *
     * @param startNanos 起始纳秒时间戳
     * @return 非负耗时毫秒
     */
    static long elapsedMillis(long startNanos) {
        long elapsedNanos = System.nanoTime() - startNanos;
        return Math.max(elapsedNanos / 1_000_000L, 0L);
    }

    /**
     * 文本字符数（空白按 0 计）。
     *
     * @param text 文本（可空）
     * @return 字符数
     */
    static int chars(String text) {
        return text == null ? 0 : text.length();
    }

    /**
     * 文本摘要标识：MD5 十六进制前 8 位；空白返回 {@code "-"}。
     *
     * @param text 文本（可空）
     * @return 摘要标识或占位符
     */
    static String digest(String text) {
        if (StringUtils.isEmpty(text)) {
            return ABSENT;
        }
        String hex = DigestUtils.md5DigestAsHex(text.getBytes(StandardCharsets.UTF_8));
        return hex.substring(0, DIGEST_LENGTH);
    }

    /**
     * 安全输出 INFO 日志：字段组装或输出异常一律吞掉。
     *
     * @param log             日志器
     * @param messageSupplier 消息提供器（延迟组装）
     */
    static void info(Logger log, Supplier<String> messageSupplier) {
        try {
            log.info(messageSupplier.get());
        } catch (RuntimeException ignored) {
            // 观测不得影响摄入：组装或输出失败直接静默降级
        }
    }

    /**
     * 安全输出 WARN 日志（附异常）：字段组装或输出异常一律吞掉。
     *
     * @param log             日志器
     * @param messageSupplier 消息提供器（延迟组装）
     * @param cause           关联异常（可空）
     */
    static void warn(Logger log, Supplier<String> messageSupplier, Throwable cause) {
        try {
            log.warn(messageSupplier.get(), cause);
        } catch (RuntimeException ignored) {
            // 观测不得影响摄入：组装或输出失败直接静默降级
        }
    }
}
