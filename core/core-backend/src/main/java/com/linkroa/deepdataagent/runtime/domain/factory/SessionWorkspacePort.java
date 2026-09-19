package com.linkroa.deepdataagent.runtime.domain.factory;

import java.nio.file.Path;

/**
 * 会话工作区布局端口（领域出站端口，宿主路径解析）。
 * <p>把「Agent 宿主工作区根 → 会话命名空间目录 → 挂载目录 → 单个挂载落点」的路径派生
 * 语义收在领域层声明，由 {@code infrastructure} 的实现读 {@code AgentRuntimeProperties}
 * 落点，避免应用层反向依赖基础设施配置（sandbox-workspace-file-mounts D8）。</p>
 *
 * <p><b>挂载落点恒按 {@code sessionId} 派生、与框架 {@code IsolationScope} 解耦（D17）</b>：
 * 无论框架隔离级别取 {@code SESSION/USER/AGENT/GLOBAL}，会话挂载目录形态固定为
 * {@code <agentWorkspace>/<sessionId>/mounts}，使挂载隔离粒度恒不弱于框架运行时数据命名空间；
 * 实现<b>不得</b>读取 {@code IsolationScope} 或调用框架 {@code WorkspaceManager#resolveRuntimeDataPath}。</p>
 *
 * <p>由 {@code runtime.infrastructure.util.SessionWorkspaceLayout} 进程内实现，
 * 全部方法对 {@code agentId} / {@code sessionId} / {@code mountPath} 做归一 + containment 逃逸防护。</p>
 */
public interface SessionWorkspacePort {

    /**
     * Agent 级宿主工作区根（= {@code materializeAgentsMd} 落点，语义不变，仅为统一取路径来源）。
     *
     * @param agentId Agent 业务 ID
     * @return {@code <workspace-root>/<agentId>} 绝对路径
     */
    Path agentWorkspace(String agentId);

    /**
     * 会话命名空间目录（对齐框架 SESSION 命名空间层，删除会话即递归清理此目录）。
     *
     * @param agentId   Agent 业务 ID
     * @param sessionId 会话业务 ID
     * @return {@code <agentWorkspace>/<sessionId>} 绝对路径
     */
    Path sessionNamespaceDirectory(String agentId, String sessionId);

    /**
     * 会话挂载目录（bind mount 的 hostPath，容器内映射为 {@code /workspace/mounts}）。
     *
     * @param agentId   Agent 业务 ID
     * @param sessionId 会话业务 ID
     * @return {@code <sessionNamespaceDirectory>/mounts} 绝对路径
     */
    Path mountsDirectory(String agentId, String sessionId);

    /**
     * 单个挂载项的宿主物化目标路径（相对路径归一 + containment 判定）。
     *
     * @param agentId   Agent 业务 ID
     * @param sessionId 会话业务 ID
     * @param mountPath 工作区相对挂载路径（{@code mounts/} 前缀）
     * @return 宿主目标文件绝对路径，落在挂载目录内
     * @throws IllegalArgumentException 挂载路径越出挂载目录根
     */
    Path mountTarget(String agentId, String sessionId, String mountPath);

    /**
     * 递归清理会话命名空间目录（尽力而为：失败记日志不抛，MUST NOT 删到 Agent 级根内容）。
     *
     * @param agentId   Agent 业务 ID
     * @param sessionId 会话业务 ID
     */
    void cleanup(String agentId, String sessionId);
}
