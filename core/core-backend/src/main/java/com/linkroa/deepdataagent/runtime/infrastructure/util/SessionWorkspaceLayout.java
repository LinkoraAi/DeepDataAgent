package com.linkroa.deepdataagent.runtime.infrastructure.util;

import com.linkroa.deepdataagent.runtime.domain.factory.SessionWorkspacePort;
import com.linkroa.deepdataagent.runtime.domain.model.SessionResource;
import com.linkroa.deepdataagent.runtime.infrastructure.config.AgentRuntimeProperties;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * 会话工作区宿主布局（{@link SessionWorkspacePort} 实现）。
 * <p>目录形态（自工作区根逐层）：
 * {@code <workspace-root>/<agentId>}（Agent 级工作区根，与工厂 {@code materializeAgentsMd}
 * 落点一致，不改）→ {@code <sessionId>}（会话命名空间目录，对齐框架
 * {@code IsolationScope.SESSION} 的命名空间层，框架运行时数据 {@code memory/}、
 * {@code agents/<agentId>/{sessions,tasks}/} 与之平级共存）→ {@code mounts}
 * （会话挂载目录，bind mount 的 hostPath）。</p>
 *
 * <p><b>与框架 {@code WorkspaceManager#resolveRuntimeDataPath} 的关系（D8/D17）</b>：
 * 不复用该框架方法——它要求已构建的 {@code HarnessAgent} 实例（物化发生在挂载写入期，
 * Agent 尚未构建），且其前缀随 {@code IsolationScope} 变化，会把平台挂载落点绑到 scope 上。
 * 本实现按 SESSION 层规则自行派生目录形态（当前配置下与框架命名空间层完全重合），
 * scope 任何取值下挂载隔离均不弱于框架。</p>
 *
 * <p>全部方法对 {@code agentId} / {@code sessionId} / {@code mountPath} 做
 * 「反斜杠归一 + 规范化 + containment」三重防护，越界一律 {@link IllegalArgumentException}。</p>
 */
@Slf4j
@Component
public class SessionWorkspaceLayout implements SessionWorkspacePort {

    @Resource
    private AgentRuntimeProperties properties;

    @Override
    public Path agentWorkspace(String agentId) {
        Path root = workspaceRoot();
        return resolveWithin(root, requireId(agentId, "AgentID"));
    }

    @Override
    public Path sessionNamespaceDirectory(String agentId, String sessionId) {
        Path agentWorkspace = agentWorkspace(agentId);
        return resolveWithin(agentWorkspace, requireId(sessionId, "会话ID"));
    }

    @Override
    public Path mountsDirectory(String agentId, String sessionId) {
        return sessionNamespaceDirectory(agentId, sessionId).resolve(SessionResource.MOUNTS_ROOT);
    }

    @Override
    public Path mountTarget(String agentId, String sessionId, String mountPath) {
        Path mountsDirectory = mountsDirectory(agentId, sessionId);
        if (StringUtils.isBlank(mountPath)) {
            throw new IllegalArgumentException("挂载路径不能为空");
        }
        // 宿主映射必须与沙箱可见路径 /workspace/<mount_path> 同构：mounts/ 前缀段落在挂载目录内
        String relative = mountPath.replace('\\', '/');
        String prefix = SessionResource.DEFAULT_MOUNT_PATH_PREFIX;
        if (!relative.startsWith(prefix) || relative.length() == prefix.length()) {
            throw new IllegalArgumentException("挂载路径必须位于 mounts/ 目录下: " + mountPath);
        }
        Path target = mountsDirectory.resolve(relative.substring(prefix.length())).normalize();
        if (!target.startsWith(mountsDirectory)) {
            throw new IllegalArgumentException("挂载路径越出会话挂载目录: " + mountPath);
        }
        return target;
    }

    @Override
    public void cleanup(String agentId, String sessionId) {
        Path namespace = sessionNamespaceDirectory(agentId, sessionId);
        if (Files.notExists(namespace)) {
            return;
        }
        // 递归删除自叶向根，尽力而为：单个条目失败不阻断整体清理，也不抛穿删除主流程
        try (Stream<Path> walk = Files.walk(namespace)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ex) {
                    log.error("会话命名空间目录条目清理失败（需人工关注）: {}", path, ex);
                }
            }
        } catch (IOException ex) {
            log.error("会话命名空间目录遍历清理失败（需人工关注）: {}", namespace, ex);
        }
    }

    /** 工作区根（与工厂 materializeAgentsMd 同一派生口径：绝对化 + 规范化）。 */
    private Path workspaceRoot() {
        return Path.of(properties.getWorkspaceRoot()).toAbsolutePath().normalize();
    }

    /** 业务 ID 必填守卫（agentId / sessionId 均源自持久化标识，空即装配现场错误）。 */
    private static String requireId(String id, String label) {
        if (StringUtils.isBlank(id)) {
            throw new IllegalArgumentException(label + "不能为空，拒绝解析工作区路径");
        }
        return id;
    }

    /** 在父目录内解析一段 ID，反斜杠归一 + 规范化后 containment 校验。 */
    private static Path resolveWithin(Path parent, String segment) {
        String normalized = segment.replace('\\', '/');
        if (normalized.startsWith("/")) {
            throw new IllegalArgumentException("路径段不得为绝对路径: " + segment);
        }
        Path target = parent.resolve(normalized).normalize();
        if (!target.startsWith(parent) || target.equals(parent)) {
            throw new IllegalArgumentException("路径段越出工作区父目录: " + segment);
        }
        return target;
    }
}
