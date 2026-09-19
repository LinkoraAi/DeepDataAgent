package com.linkroa.deepdataagent.runtime.api.dto;

import org.apache.commons.lang3.StringUtils;

import java.util.List;

/**
 * 调度器触发启动契约（发布语言 DTO，跨 BC 载体，纯 record 零业务逻辑）。
 *
 * <p>由 agent BC 的调度器触发路径装配，runtime BC 据此新建打标 Session 并驱动首个 turn。
 * 装配版本号在调度器一侧解析并固定（创建时取激活版本或显式 pin）后传入，runtime 仍做
 * 装配校验兜底。挂载材料（环境变量 / 资源 / 保管库 / 首批事件 / 元数据）为调度器持有的
 * 透传载荷，格式对齐会话创建契约，由 runtime 侧解释装配。</p>
 *
 * @param ownerId              触发方归属用户 ID（数字字符串；webhook 场景取调度器 owner）
 * @param agentId              指向的 Agent 业务 ID
 * @param versionNumber        已解析并固定的装配版本号
 * @param environmentId        指向的 Environment 业务 ID（可空，回退默认环境）
 * @param input                触发消息（可空，为空回退首批事件合成或默认调度提示）
 * @param triggerType          触发来源类型（manual / webhook / cron，源码小写）
 * @param triggerId            触发来源调度器业务 ID（deployment_id）
 * @param environmentVariables 会话级环境变量 JSON 对象文本（可空透传）
 * @param resourcesJson        会话挂载资源 JSON 数组文本（可空透传，格式对齐 session resources）
 * @param vaultIds             保管库业务 ID 列表（可空，归一为空列表）
 * @param initialEvents        首批用户消息事件 JSON 数组文本（可空，触发时合成首个 turn 消息）
 * @param metadata             会话元数据 JSON 对象文本（可空透传）
 */
public record SchedulerLaunchDTO(
        String ownerId,
        String agentId,
        String versionNumber,
        String environmentId,
        String input,
        String triggerType,
        String triggerId,
        String environmentVariables,
        String resourcesJson,
        List<String> vaultIds,
        String initialEvents,
        String metadata
) {

    public SchedulerLaunchDTO {
        if (StringUtils.isBlank(ownerId)) {
            throw new IllegalArgumentException("归属用户ID不能为空");
        }
        if (StringUtils.isBlank(agentId)) {
            throw new IllegalArgumentException("Agent ID不能为空");
        }
        if (StringUtils.isBlank(versionNumber)) {
            throw new IllegalArgumentException("装配版本号不能为空");
        }
        if (StringUtils.isBlank(triggerType)) {
            throw new IllegalArgumentException("触发类型不能为空");
        }
        if (StringUtils.isBlank(triggerId)) {
            throw new IllegalArgumentException("触发来源调度器ID不能为空");
        }
        vaultIds = vaultIds == null ? List.of() : List.copyOf(vaultIds);
    }
}
