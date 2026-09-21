package com.linkroa.deepdataagent.rag.domain.port;

import org.apache.commons.lang3.StringUtils;

/**
 * LLM 对话调用结果值对象。
 *
 * @param text        模型回复正文（缓存命中时为回放内容）
 * @param totalTokens 本次消耗 token 总数（缓存命中回放时为缓存记录值）
 * @param cacheHit    本次结果是否来自 llm_cache 回放（
 *                    供摄入/检索收尾日志统计缓存命中次数）
 * @author DeepDataAgent
 */
public record LlmChatResult(String text, int totalTokens, boolean cacheHit) {

    /**
     * 紧凑构造器：回复正文空值归一为空串，避免下游判空分支。
     */
    public LlmChatResult {
        text = StringUtils.defaultString(text);
    }

    /**
     * 便捷构造器：不声明缓存命中标志（默认视为未命中）。
     *
     * @param text        模型回复正文
     * @param totalTokens 本次消耗 token 总数
     */
    public LlmChatResult(String text, int totalTokens) {
        this(text, totalTokens, false);
    }
}
