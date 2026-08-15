package com.linkroa.deepdataagent.runtime.application.query;

import org.apache.commons.lang3.StringUtils;

/**
 * 会话事件回放查询。
 *
 * @param sessionId        会话 ID
 * @param afterSequenceNum 回放起点（序列号大于该值的事件，0 表示全量回放）
 */
public record ReplayQuery(String sessionId, long afterSequenceNum) {

    public ReplayQuery {
        if (StringUtils.isBlank(sessionId)) {
            throw new IllegalArgumentException("会话ID不能为空");
        }
        if (afterSequenceNum < 0) {
            throw new IllegalArgumentException("回放起点序列号不能为负数");
        }
    }
}