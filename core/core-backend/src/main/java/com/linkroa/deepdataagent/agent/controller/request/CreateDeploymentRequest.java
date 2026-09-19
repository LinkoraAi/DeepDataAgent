package com.linkroa.deepdataagent.agent.controller.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

/**
 * 创建调度器请求（调度器契约语义）。
 * <p>触发配置：携带 {@code schedule} 即为定时调度器；{@code webhook=true} 时服务端生成
 * 回调 token（开放 webhook 触发）；手动触发恒可用（active 前提）。
 * {@code agent_version} 省略时由服务端解析当前<b>激活版本</b>并在创建时固定，
 * 触发不随后续发布漂移。挂载材料（环境变量 / 资源 / 保管库 / 首批事件 / 元数据）
 * 为透传载荷，格式对齐会话创建契约，触发会话装配时生效。</p>
 *
 * @param name                   调度器名称
 * @param description            描述（可空）
 * @param agent_id               指向的 Agent 业务ID
 * @param agent_version          显式 pin 的 Agent 版本号（可空=解析激活版本并固定）
 * @param environment_id         指向的 Environment 业务ID（可空=回退默认环境）
 * @param environment_variables  触发会话环境变量键值对（可空透传；格式与校验口径对齐会话创建契约）。
 *                               值类型声明为 {@code Object} 而非 {@code String}：绑定层不做标量强转，
 *                               由触发会话环境变量校验端口按「所有值 MUST 为字符串」判定并拒绝
 *                               （数字 / 布尔 / null / 对象 / 数组 → 400），避免「协议绑定静默转成字符串」
 *                               使该规则在调度器契约面上被架空
 * @param resources              触发会话挂载资源列表（可空，格式对齐 session resources）
 * @param vault_ids              触发会话保管库 ID 列表（可空）
 * @param initial_events         首批用户消息事件列表（可空，触发时合成首个 turn 消息）
 * @param metadata               会话元数据（可空透传）
 * @param schedule               调度配置（可空=仅手动/webhook 触发）
 * @param webhook                是否开放 webhook 触发（可空=false）
 */
public record CreateDeploymentRequest(

        @NotBlank(message = "调度器名称不能为空")
        @Size(max = 64, message = "调度器名称不能超过64个字符")
        String name,

        String description,

        @NotBlank(message = "Agent ID不能为空")
        String agent_id,

        Integer agent_version,

        String environment_id,

        Map<String, Object> environment_variables,

        List<DeploymentResourceRequest> resources,

        List<String> vault_ids,

        List<DeploymentInitialEventRequest> initial_events,

        Map<String, Object> metadata,

        DeploymentScheduleRequest schedule,

        Boolean webhook
) {
}
