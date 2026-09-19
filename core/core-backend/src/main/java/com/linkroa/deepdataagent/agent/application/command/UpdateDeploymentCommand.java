package com.linkroa.deepdataagent.agent.application.command;

import com.linkroa.deepdataagent.agent.domain.model.DeploymentSchedule;
import org.apache.commons.lang3.StringUtils;

import java.util.List;

/**
 * 更新调度器命令（merge-patch 语义，6.5 管理面）。
 *
 * <p>三态表达约定：{@code xxxPresent} 标志区分「缺省不改」与「显式提供」——
 * present=false 时对应值字段无意义（应用层回填原值）；present=true 且值为 {@code null}
 * 表示<b>显式清空</b>（description→空串、environment_variables→{}、resources→[]、
 * vault_ids→[]、initial_events→[]、metadata→{}、schedule→null 并清 nextRunAt）。</p>
 *
 * <p>{@code name} 例外：非必填可改字段不提供即不改，显式 null 在协议装配层即拒绝（400），
 * 命令侧以 {@code null}=不改、非空串=改名两态表达。{@code metadataMerge} 为浅合并增量
 * JSON 文本（同名键覆盖、键值 {@code null} 删除该键），整体显式 null 时清空元数据。
 * {@code agentId / agentVersion}（创建时固定、触发不漂移）与 webhook 开通状态不可调。</p>
 *
 * @param deploymentId            调度器业务ID（必填）
 * @param name                    名称（null=不改）
 * @param description             描述（与 present 成对）
 * @param descriptionPresent      描述是否提供
 * @param environmentId           Environment 业务ID（与 present 成对；present 且 null=回退默认环境）
 * @param environmentIdPresent    Environment 是否提供
 * @param environmentVariables    环境变量 JSON 文本（与 present 成对）
 * @param environmentVariablesPresent 环境变量是否提供
 * @param resources               挂载资源 JSON 文本（与 present 成对）
 * @param resourcesPresent        资源是否提供
 * @param vaultIds                保管库 ID 列表（与 present 成对）
 * @param vaultIdsPresent         保管库是否提供
 * @param initialEvents           首批事件 JSON 文本（与 present 成对）
 * @param initialEventsPresent    首批事件是否提供
 * @param metadataMerge           元数据浅合并增量 JSON 文本（与 present 成对；present 且 null=整体清空）
 * @param metadataPresent         元数据是否提供
 * @param schedule                调度配置（与 present 成对；present 且 null=清空调度）
 * @param schedulePresent         调度配置是否提供
 */
public record UpdateDeploymentCommand(
        String deploymentId,
        String name,
        String description,
        boolean descriptionPresent,
        String environmentId,
        boolean environmentIdPresent,
        String environmentVariables,
        boolean environmentVariablesPresent,
        String resources,
        boolean resourcesPresent,
        List<String> vaultIds,
        boolean vaultIdsPresent,
        String initialEvents,
        boolean initialEventsPresent,
        String metadataMerge,
        boolean metadataPresent,
        DeploymentSchedule schedule,
        boolean schedulePresent
) {

    public UpdateDeploymentCommand {
        if (StringUtils.isBlank(deploymentId)) {
            throw new IllegalArgumentException("调度器ID不能为空");
        }
        // 名称长度与 deployment.name 列宽对齐（审查修复 F12：VARCHAR(64)）
        if (StringUtils.isNotBlank(name) && name.length() > 64) {
            throw new IllegalArgumentException("调度器名称不能超过64个字符");
        }
    }
}
