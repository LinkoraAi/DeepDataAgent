package com.linkroa.deepdataagent.runtime.controller.request;

import jakarta.validation.constraints.NotBlank;

import java.util.List;
import java.util.Map;

/**
 * 创建会话请求（对齐 Managed Agents {@code POST /sessions}）。
 * <p>{@code environment_id / resources / vault_ids} 目前仅做接口形式对齐，
 * 运行环境与资源挂载的物化留待后续实现。</p>
 *
 * @param agent          绑定的智能体 ID（会话锁定其最新版本快照）
 * @param environment_id 绑定的运行环境 ID
 * @param title          会话标题（可空）
 * @param resources      挂载的文件列表（可空）
 * @param vault_ids      保险箱 ID 列表（可空）
 * @param metadata       业务自定义元数据（可空）
 */
public record CreateSessionRequest(
        @NotBlank(message = "智能体ID不能为空")
        String agent,

        @NotBlank(message = "运行环境ID不能为空")
        String environment_id,

        String title,
        List<SessionResourceRequest> resources,
        List<String> vault_ids,
        Map<String, Object> metadata
) {
}