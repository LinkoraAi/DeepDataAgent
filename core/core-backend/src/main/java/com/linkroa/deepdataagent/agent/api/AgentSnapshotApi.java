package com.linkroa.deepdataagent.agent.api;

import com.linkroa.deepdataagent.agent.api.dto.AgentSnapshotDTO;

/**
 * Agent 嵌入快照服务契约（跨 BC 服务边界，未来 Feign 落点）。
 * <p>runtime BC 在 Session / Session Thread 响应中嵌入 Agent 快照（契约标准形态）时
 * 经本契约向 agent BC 请求「已按裁剪规则格式化」的快照发布语言 DTO：
 * {@code created_at / updated_at / archived / archived_at / metadata} 剔除、
 * 系统提示词以 {@code system} 输出、{@code multiagent} 恒 {@code null}
 * （写入侧非空提交一律 400，MUST NOT 展开 coordinator {@code agents[]} 阵列）。
 * 当前由 {@code DefaultAgentSnapshotApi} 进程内实现，未来接入 Feign 时
 * 仅需在本接口追加 {@code @FeignClient} 注解。</p>
 */
public interface AgentSnapshotApi {

    /**
     * 解析指定 Agent 版本快照为嵌入形态（Session 级：{@code multiagent} 恒 {@code null}）。
     * <p>归属隔离（审查修复 F05）：顶层定义按 {@code ownerId} 过滤，
     * 非归属主的定义一律视为不存在；{@code ownerId} 为 {@code null} 时 fail-closed 返回
     * {@code null}，防止跨租户泄露他人 Agent 定义内容。</p>
     *
     * @param agentId       Agent 业务 ID（agent_ 前缀）
     * @param versionNumber 发布版本号；{@code null} 或非法（&lt;1）时解析当前激活版本
     *                      （无激活版本回落最新已发布版本，与 Agent 读取路径口径一致）
     * @param ownerId       归属用户 ID（null 时 fail-closed 不返回任何快照）
     * @return 裁剪格式化后的快照 DTO；Agent 不存在 / 越权 / 无版本时返回 {@code null}（消费方降级摘要）
     */
    AgentSnapshotDTO resolveSnapshot(String agentId, Integer versionNumber, Long ownerId);
}
