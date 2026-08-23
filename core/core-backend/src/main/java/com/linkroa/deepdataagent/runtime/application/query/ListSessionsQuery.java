package com.linkroa.deepdataagent.runtime.application.query;

import com.linkroa.deepdataagent.runtime.domain.model.SessionCursor;
import com.linkroa.deepdataagent.runtime.domain.model.enums.AgentSessionStatus;
import org.apache.commons.lang3.StringUtils;

import java.util.List;

/**
 * 会话列表查询（游标分页 + 过滤）。
 *
 * @param userId  用户 ID（本期占位）
 * @param agentId Agent ID 过滤（可空，不过滤）
 * @param statuses 会话状态集合过滤（为空表示不过滤）
 * @param cursor  游标（null 表示第一页）
 * @param size    每页大小
 */
public record ListSessionsQuery(
        String userId,
        String agentId,
        List<AgentSessionStatus> statuses,
        SessionCursor cursor,
        int size
) {

    public ListSessionsQuery {
        if (StringUtils.isBlank(userId)) {
            throw new IllegalArgumentException("用户ID不能为空");
        }
        if (size < 1 || size > 100) {
            throw new IllegalArgumentException("每页大小必须介于1-100");
        }
        statuses = statuses == null ? List.of() : statuses;
    }
}