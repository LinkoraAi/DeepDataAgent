package com.linkroa.deepdataagent.runtime.application.query;

import org.apache.commons.lang3.StringUtils;

/**
 * 会话列表查询（按用户分页）。
 *
 * @param userId 用户 ID
 * @param page   页码（从 1 开始）
 * @param size   每页大小
 */
public record ListSessionsQuery(String userId, int page, int size) {

    public ListSessionsQuery {
        if (StringUtils.isBlank(userId)) {
            throw new IllegalArgumentException("用户ID不能为空");
        }
        if (page < 1) {
            throw new IllegalArgumentException("页码必须从1开始");
        }
        if (size < 1 || size > 100) {
            throw new IllegalArgumentException("每页大小必须介于1-100");
        }
    }
}