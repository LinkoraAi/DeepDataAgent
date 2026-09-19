package com.linkroa.deepdataagent.runtime.infrastructure.util;

import com.linkroa.deepdataagent.runtime.infrastructure.config.AgentRuntimeProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SessionWorkspaceLayout} 会话工作区宿主布局单测。
 * <p>覆盖：四层路径形态（Agent 根 / 会话命名空间 / 挂载目录 / 单挂载落点）、
 * 越界 ID 与越界挂载路径拒绝、合法嵌套路径通过、cleanup 只删会话命名空间目录
 * 且幂等（Agent 级根内容保留、目录不存在不抛）。</p>
 */
class SessionWorkspaceLayoutTest {

    @TempDir
    Path tempDir;

    private SessionWorkspaceLayout layout;

    @BeforeEach
    void setUp() {
        AgentRuntimeProperties properties = new AgentRuntimeProperties();
        properties.setWorkspaceRoot(tempDir.toString());
        layout = new SessionWorkspaceLayout();
        ReflectionTestUtils.setField(layout, "properties", properties);
    }

    // ==================== 四层路径形态 ====================

    @Test
    void should_deriveFourLayerPaths_when_workspaceQueries_given_validIds() {
        // given // when
        Path agentWorkspace = layout.agentWorkspace("agent-a");
        Path namespace = layout.sessionNamespaceDirectory("agent-a", "sess_1");
        Path mounts = layout.mountsDirectory("agent-a", "sess_1");
        Path target = layout.mountTarget("agent-a", "sess_1", "mounts/file_1");

        // then（根/<agentId>/<sessionId>/mounts/<相对余部> 逐层拼接、全部绝对化）
        assertEquals(tempDir.resolve("agent-a"), agentWorkspace);
        assertEquals(agentWorkspace.resolve("sess_1"), namespace);
        assertEquals(namespace.resolve("mounts"), mounts);
        assertEquals(mounts.resolve("file_1"), target);
        assertTrue(agentWorkspace.isAbsolute());
        assertTrue(target.isAbsolute());
    }

    @Test
    void should_resolveNestedTarget_when_mountTarget_given_nestedRelativePath() {
        // given // when（合法嵌套：mounts/dataset/a.csv）
        Path target = layout.mountTarget("agent-a", "sess_1", "mounts/dataset/a.csv");

        // then
        assertEquals(layout.mountsDirectory("agent-a", "sess_1").resolve("dataset").resolve("a.csv"), target);
    }

    // ==================== 越界防护 ====================

    @Test
    void should_throw_when_agentWorkspace_given_escapingAgentId() {
        // given // when & then（越界 agentId 拒绝，不越出工作区根）
        assertThrows(IllegalArgumentException.class, () -> layout.agentWorkspace("../x"));
        assertThrows(IllegalArgumentException.class, () -> layout.agentWorkspace("a/../../x"));
        assertThrows(IllegalArgumentException.class, () -> layout.agentWorkspace(null));
        assertThrows(IllegalArgumentException.class, () -> layout.agentWorkspace("  "));
    }

    @Test
    void should_throw_when_sessionNamespace_given_escapingSessionId() {
        // given // when & then
        assertThrows(IllegalArgumentException.class, () -> layout.sessionNamespaceDirectory("agent-a", "../agent-b"));
        assertThrows(IllegalArgumentException.class, () -> layout.sessionNamespaceDirectory("agent-a", "."));
    }

    @Test
    void should_throw_when_mountTarget_given_traversalOrForeignRootPath() {
        // given // when & then（穿越与挂载根之外的路径一律拒绝）
        assertThrows(IllegalArgumentException.class,
                () -> layout.mountTarget("agent-a", "sess_1", "mounts/../../secret"));
        assertThrows(IllegalArgumentException.class,
                () -> layout.mountTarget("agent-a", "sess_1", "data/file.csv"));
        assertThrows(IllegalArgumentException.class,
                () -> layout.mountTarget("agent-a", "sess_1", "mounts"));
        assertThrows(IllegalArgumentException.class,
                () -> layout.mountTarget("agent-a", "sess_1", null));
    }

    @Test
    void should_notEscapeMountsDirectory_when_mountTarget_given_dotDotWrittenInsidePrefix() {
        // given（mounts/ 前缀内的 .. 段：归一后若仍落在挂载目录内则允许，越出则拒绝）
        Path inside = layout.mountTarget("agent-a", "sess_1", "mounts/a/../file_1");

        // when & then（归一后仍在挂载目录内 → 通过且与直写等价）
        assertEquals(layout.mountsDirectory("agent-a", "sess_1").resolve("file_1"), inside);
    }

    // ==================== cleanup 范围与幂等 ====================

    @Test
    void should_deleteOnlySessionNamespace_when_cleanup_given_populatedWorkspace() throws IOException {
        // given（Agent 根下放 AGENTS.md 与两个会话的命名空间目录，会话内含挂载副本与框架数据目录）
        Path agentRoot = layout.agentWorkspace("agent-a");
        Files.createDirectories(agentRoot);
        Files.writeString(agentRoot.resolve("AGENTS.md"), "agent level", StandardCharsets.UTF_8);
        Path victim = layout.sessionNamespaceDirectory("agent-a", "sess_1");
        Path survivor = layout.sessionNamespaceDirectory("agent-a", "sess_2");
        Files.createDirectories(layout.mountsDirectory("agent-a", "sess_1"));
        Files.writeString(layout.mountTarget("agent-a", "sess_1", "mounts/file_1"), "内容", StandardCharsets.UTF_8);
        Files.createDirectories(victim.resolve("memory"));
        Files.writeString(victim.resolve("memory").resolve("note.md"), "x", StandardCharsets.UTF_8);
        Files.createDirectories(survivor);
        Files.writeString(survivor.resolve("keep.txt"), "y", StandardCharsets.UTF_8);

        // when
        layout.cleanup("agent-a", "sess_1");

        // then（会话命名空间整体消失；Agent 级内容与另一会话目录原样保留）
        assertTrue(Files.notExists(victim));
        assertTrue(Files.exists(agentRoot.resolve("AGENTS.md")));
        assertTrue(Files.exists(survivor.resolve("keep.txt")));
    }

    @Test
    void should_beIdempotent_when_cleanup_given_missingOrAlreadyCleanedNamespace() {
        // given（目录从未创建的会话）
        // when & then（不存在不抛、重复调用同样静默）
        layout.cleanup("agent-a", "sess_none");
        layout.cleanup("agent-a", "sess_none");
        assertTrue(Files.notExists(layout.sessionNamespaceDirectory("agent-a", "sess_none")));
    }
}
