package com.linkroa.deepdataagent.rag.domain.service;

import org.apache.commons.lang3.StringUtils;

/**
 * Token 计数端口。
 * <p>主路径（唯一口径）为真实 encode；实现需遵循：所有分块窗口边界、
 * {@code MAX_SECTION_CONTEXT_TOKENS}(256) 预算、多模态模板化 chunk 计数均走本接口，
 * 不得使用估算替代真实 encode。</p>
 */
public interface TokenCounter {

    /**
     * 统计文本的 token 数量（真实 encode，无启发式估算）。
     *
     * @param text 待计数文本（可为空，空文本返回 0）
     * @return token 数量，非负
     */
    int count(String text);

    /**
     * 将文本精确截断到不超过 {@code maxTokens} 个 token（向量内容截断口径）。
     * <p>默认实现为「二分最长前缀 + 重编码验证」：对字符前缀二分，以 {@link #count}
     * 真实编码判定可行性，产出结果再经一次重编码验证（超出即返回空串，理论不可达）。
     * 能直接按 token 序列切片的实现（如 jtokkit）应覆写为本 token 边界精确截断，
     * 单次编码即可完成、无近似回弹风险。</p>
     *
     * @param text      待截断文本（可为空）
     * @param maxTokens token 上限（非正时返回空串）
     * @return 截断后的文本前缀，其真实 token 数不超过 {@code maxTokens}
     */
    default String truncate(String text, int maxTokens) {
        if (StringUtils.isBlank(text) || maxTokens <= 0) {
            return StringUtils.EMPTY;
        }
        if (count(text) <= maxTokens) {
            return text;
        }
        int low = 0;
        int high = text.length();
        while (low < high) {
            // 上取整中点，保证 low 单调推进直至收敛于最大可行前缀长度
            int mid = (low + high + 1) >>> 1;
            if (count(text.substring(0, mid)) <= maxTokens) {
                low = mid;
            } else {
                high = mid - 1;
            }
        }
        String truncated = text.substring(0, low);
        // 重编码验证：极端 tokenizer（合并/归一化跨界）下前缀编码数可能回弹，超限则退为空
        if (count(truncated) > maxTokens) {
            return StringUtils.EMPTY;
        }
        return truncated;
    }
}