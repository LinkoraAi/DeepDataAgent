package com.linkroa.deepdataagent.rag.domain.port;

import org.apache.commons.lang3.StringUtils;

/**
 * 流式对话的单个增量值对象（领域内类型，隔离框架响应类型）。
 * <p>仅承载「本次新增文本」与可空的累计 token 用量：文本增量按到达顺序拼接即为完整回复；
 * token 用量在提供方回传 usage 的分片出现时携带，其余分片为空。
 * 本类型不进入任何持久化与跨 BC 契约。</p>
 *
 * @param text        本次增量文本（可为空串，表示该分片不含文本内容）
 * @param totalTokens 迄今为止的累计 token 总数（提供方未回传时为 {@code null}）
 * @author DeepDataAgent
 */
public record LlmStreamDelta(String text, Integer totalTokens) {

    /**
     * 紧凑构造器：文本空值归一为空串，避免下游逐处判空。
     */
    public LlmStreamDelta {
        text = StringUtils.defaultString(text);
    }

    /**
     * 便捷构造器：仅承载文本增量、不携带 token 用量。
     *
     * @param text 本次增量文本
     */
    public LlmStreamDelta(String text) {
        this(text, null);
    }
}