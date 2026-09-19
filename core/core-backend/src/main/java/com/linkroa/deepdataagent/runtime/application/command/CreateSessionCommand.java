package com.linkroa.deepdataagent.runtime.application.command;

import com.linkroa.deepdataagent.runtime.domain.model.SessionResource;
import org.apache.commons.lang3.StringUtils;

import java.util.List;

/**
 * 创建 Agent 会话命令。
 *
 * @param userId               用户 ID
 * @param agentId              Agent 业务 ID
 * @param agentVersion         Agent 版本（可空，省略时绑定该 Agent 的激活版本）
 * @param title                会话标题（可空）
 * @param metadata             会话元数据（可空，JSON 文本）
 * @param triggerType          触发来源类型（manual / webhook / cron，源码小写；可空=普通用户会话）
 * @param triggerId            触发来源调度器业务 ID（deployment_id；triggerType 存在时必填）
 * @param resources            会话挂载资源引用（可空，file / github_repository / memory_store 三类）
 * @param environmentId        运行环境业务 ID（可空；存在性 / 归属 / 执行平面校验由应用服务承担）
 * @param vaultIds             保管库业务 ID 列表（可空，归一为空列表；存在性 / 归属校验由应用服务承担）
 * @param environmentVariables 会话级环境变量（可空，JSON 对象文本；领域模型空白归一为 {@code "{}"}）
 */
public record CreateSessionCommand(
        String userId,
        String agentId,
        String agentVersion,
        String title,
        String metadata,
        String triggerType,
        String triggerId,
        List<SessionResource> resources,
        String environmentId,
        List<String> vaultIds,
        String environmentVariables
) {

    public CreateSessionCommand {
        if (StringUtils.isBlank(userId)) {
            throw new IllegalArgumentException("用户ID不能为空");
        }
        if (StringUtils.isBlank(agentId)) {
            throw new IllegalArgumentException("AgentID不能为空");
        }
        if (title != null && title.length() > 255) {
            throw new IllegalArgumentException("会话标题长度不能超过255");
        }
        // 触发标记成对校验：来源调度器ID存在时必须有来源类型
        if (StringUtils.isNotBlank(triggerId) && StringUtils.isBlank(triggerType)) {
            throw new IllegalArgumentException("触发来源ID存在时必须携带触发类型");
        }
        // 挂载资源归一：null 收敛为空列表、防御性复制（元素自身经 SessionResource 紧凑构造器校验）
        resources = resources == null ? List.of() : List.copyOf(resources);
        // 保管库列表归一：null 收敛为空列表、防御性复制
        vaultIds = vaultIds == null ? List.of() : List.copyOf(vaultIds);
    }

    /**
     * 便捷构造：普通用户会话（无触发标记、无挂载资源、无环境 / 保管库挂载）。
     */
    public CreateSessionCommand(String userId, String agentId, String agentVersion, String title, String metadata) {
        this(userId, agentId, agentVersion, title, metadata, null, null, List.of(), null, List.of(), null);
    }

    /**
     * 便捷构造：调度器触发会话（携带触发标记、无挂载资源、无环境 / 保管库挂载）。
     */
    public CreateSessionCommand(String userId, String agentId, String agentVersion, String title, String metadata,
                                String triggerType, String triggerId) {
        this(userId, agentId, agentVersion, title, metadata, triggerType, triggerId, List.of(), null, List.of(), null);
    }

    /**
     * 便捷构造：携带挂载资源、未挂接环境 / 保管库（旧 8 参形态，新列走默认值）。
     */
    public CreateSessionCommand(String userId, String agentId, String agentVersion, String title, String metadata,
                                String triggerType, String triggerId, List<SessionResource> resources) {
        this(userId, agentId, agentVersion, title, metadata, triggerType, triggerId, resources, null, List.of(), null);
    }
}
