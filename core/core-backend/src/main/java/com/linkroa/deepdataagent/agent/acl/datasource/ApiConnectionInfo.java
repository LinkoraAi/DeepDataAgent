package com.linkroa.deepdataagent.agent.acl.datasource;

import java.util.List;

/**
 * API 连接信息（ACL 值对象）
 * <p>仅携带 Agent 工具所需的 API 数据源摘要信息，
 * 不暴露 URL/认证等敏感细节，执行时通过 Gateway 按需获取。</p>
 */
public record ApiConnectionInfo(
    Long connectionId,
    List<String> apiSchemaNames
) {
    public ApiConnectionInfo {
        if (connectionId == null) {
            throw new IllegalArgumentException("连接 ID 不能为空");
        }
    }
}
