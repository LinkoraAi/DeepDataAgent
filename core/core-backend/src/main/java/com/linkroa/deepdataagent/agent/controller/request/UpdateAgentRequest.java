package com.linkroa.deepdataagent.agent.controller.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 更新 Agent 定义请求（PATCH，6.2 管理面）。
 * <p>更新以乐观并发控制：{@code version} 必填且 MUST 匹配当前 {@code latest_version}，
 * 不匹配返回 409 conflict_error；更新成功同步产生新版本快照（全量替换语义）并递增 latest_version。
 * 名称/描述缺省视为沿用现值。</p>
 *
 * @param name        新名称（可空 = 沿用现名）
 * @param description 新描述（可空 = 沿用现描述）
 * @param version     客户端持有的当前版本号（必填）
 */
public record UpdateAgentRequest(

        @Size(max = 256, message = "名称不能超过256个字符")
        String name,

        @Size(max = 2048, message = "描述不能超过2048个字符")
        String description,

        @NotNull(message = "version 不能为空（乐观并发控制）")
        Integer version
) {
}
