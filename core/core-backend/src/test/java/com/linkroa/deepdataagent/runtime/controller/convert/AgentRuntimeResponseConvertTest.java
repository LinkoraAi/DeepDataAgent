package com.linkroa.deepdataagent.runtime.controller.convert;

import com.linkroa.deepdataagent.runtime.controller.response.SessionResponse;
import com.linkroa.deepdataagent.runtime.domain.model.AgentSession;
import com.linkroa.deepdataagent.runtime.domain.model.SessionResource;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AgentRuntimeResponseConvert} 会话响应装配单测（resources / metadata / agent 快照字段映射）。
 */
class AgentRuntimeResponseConvertTest {

    private final AgentRuntimeResponseConvert converter = AgentRuntimeResponseConvert.INSTANCE;

    @Test
    void should_mapMountedResources_when_toSessionResponse_given_sessionWithMounts() {
        // given（会话挂载两个文件：一个带自定义挂载路径、一个省略由工厂补缺省路径）
        AgentSession session = AgentSession.createWithTrigger(
                "1", "agent-a", "1.0.0", "{\"biz\":\"1\"}", "会话",
                null, null,
                List.of(SessionResource.file("file_1", "mounts/dataset/a.txt"),
                        SessionResource.file("file_2", null)));

        // when
        SessionResponse response = converter.toSessionResponse(session);

        // then（resources 回显对象数组：snake_case 键 id / type / file_id / mount_path，空值省略）
        assertEquals(2, response.resources().size());
        Map<String, Object> first = response.resources().get(0);
        assertEquals(session.resources().get(0).id(), first.get("id"));
        assertTrue(String.valueOf(first.get("id")).startsWith("sesr_"));
        assertEquals("file", first.get("type"));
        assertEquals("file_1", first.get("file_id"));
        assertEquals("mounts/dataset/a.txt", first.get("mount_path"));
        Map<String, Object> second = response.resources().get(1);
        assertEquals("file_2", second.get("file_id"));
        assertEquals("mounts/file_2", second.get("mount_path"));
    }

    @Test
    void should_returnEmptyResources_when_toSessionResponse_given_sessionWithoutMounts() {
        // given
        AgentSession session = AgentSession.create("1", "agent-a", "1.0.0", "{}", null);

        // when
        SessionResponse response = converter.toSessionResponse(session);

        // then
        assertTrue(response.resources().isEmpty());
        assertEquals("session", response.type());
        assertEquals("idle", response.status());
    }

    @Test
    void should_parseMetadataAndAgentSnapshot_when_toSessionResponse_given_session() {
        // given
        AgentSession session = AgentSession.createWithTrigger(
                "1", "agent-a", "1", "{\"biz\":\"1\"}", "会话",
                "webhook", "dep-1");

        // when
        SessionResponse response = converter.toSessionResponse(session);

        // then（metadata JSON 解析为对象；agent 摘要 / 字段命名对齐契约；
        // 触发来源调度器 ID 仅经 deployment_id 对外别名回显）
        assertEquals(Map.of("biz", "1"), response.metadata());
        assertEquals("agent-a", response.agent().get("id"));
        assertEquals("agent", response.agent().get("type"));
        assertEquals(1, response.agent().get("version"));
        assertEquals("session", response.type());
        assertEquals("idle", response.status());
        // deployment_id 为触发来源调度器业务 ID 的对外别名
        assertEquals("dep-1", response.deployment_id());
    }

    @Test
    void should_mapFullContractFields_when_toSessionResponse_given_sessionWithFullMounts() {
        // given（全量挂载会话：环境 / 保管库 / 文件 + 记忆库混合挂载 / 环境变量）
        AgentSession session = AgentSession.createWithMounts(
                "1", "agent-a", "1", "{\"biz\":\"1\"}", "会话",
                null, null,
                List.of(SessionResource.file("file_1", null),
                        SessionResource.memoryStore("ms_1", "read_only", null)),
                "env-1", List.of("vault_1"), "{\"K\":\"V\"}");

        // when
        SessionResponse response = converter.toSessionResponse(session);

        // then（字段全集：status / environment_id / vault_ids / 时间戳；响应 MUST NOT 回显环境变量）
        assertEquals("idle", response.status());
        assertEquals("env-1", response.environment_id());
        assertEquals(List.of("vault_1"), response.vault_ids());
        assertEquals(null, response.deployment_id());
        assertEquals(null, response.archived_at());
        assertEquals(session.createdAt(), response.created_at());
        assertEquals(session.updatedAt(), response.updated_at());
        // 挂载回显：file 项携资源 id 与缺省挂载路径，memory_store 项不携资源 id
        Map<String, Object> fileItem = response.resources().get(0);
        assertEquals("file", fileItem.get("type"));
        assertEquals("mounts/file_1", fileItem.get("mount_path"));
        Map<String, Object> memoryItem = response.resources().get(1);
        assertEquals("memory_store", memoryItem.get("type"));
        assertEquals("ms_1", memoryItem.get("memory_store_id"));
        assertEquals("read_only", memoryItem.get("access"));
        assertTrue(!memoryItem.containsKey("id"));
        // 环境变量仅入参接受：MUST NOT 出现在任何响应形态中
        String json = new ObjectMapper().writeValueAsString(response);
        assertTrue(!json.contains("environment_variables"));
        assertTrue(!json.contains("\"K\""));
    }

    @Test
    void should_neverExposeGithubToken_when_toSessionResponse_given_repoMount() {
        // given（GitHub 仓库挂载：令牌只写不读）
        AgentSession session = AgentSession.createWithMounts(
                "1", "agent-a", "1", "{}", null, null, null,
                List.of(SessionResource.githubRepository(
                        "https://github.com/acme/repo.git", "ghp_secret", "main")),
                null, List.of(), null);

        // when
        SessionResponse response = converter.toSessionResponse(session);

        // then（回显 url / checkout，MUST NOT 出现 authorization_token）
        Map<String, Object> item = response.resources().get(0);
        assertEquals("github_repository", item.get("type"));
        assertEquals("https://github.com/acme/repo.git", item.get("url"));
        assertEquals("main", item.get("checkout"));
        assertTrue(item.keySet().stream().noneMatch(k -> k.contains("token")));
    }

    @Test
    void should_neverExposeGitPassword_when_toSessionResponse_given_gitRepositoryMount() {
        // given（通用 Git 仓库挂载：用户名在 URL、密码只写不读）
        AgentSession session = AgentSession.createWithMounts(
                "1", "agent-a", "1", "{}", null, null, null,
                List.of(SessionResource.gitRepository(
                        "https://alice@git.example.com/acme/app.git", "s3cr3t_pwd", "main")),
                null, List.of(), null);

        // when
        SessionResponse response = converter.toSessionResponse(session);

        // then（回显 url / checkout，MUST NOT 出现 password）
        Map<String, Object> item = response.resources().get(0);
        assertEquals("git_repository", item.get("type"));
        assertEquals("https://alice@git.example.com/acme/app.git", item.get("url"));
        assertEquals("main", item.get("checkout"));
        assertTrue(item.keySet().stream().noneMatch(k -> k.contains("password")));
        assertTrue(item.values().stream().noneMatch(v -> String.valueOf(v).contains("s3cr3t_pwd")));
    }

    @Test
    void should_embedGivenAgentMap_when_toSessionResponse_given_snapshotArgument() {
        // given（应用层装配的完整 Agent 快照，双参重载原样嵌入不做降级）
        AgentSession session = AgentSession.create("1", "agent-a", "1", "{}", null);
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("id", "agent-a");
        snapshot.put("type", "agent");
        snapshot.put("version", 3);
        snapshot.put("name", "分析助手");
        snapshot.put("system", "你是数据分析专家");

        // when
        SessionResponse response = converter.toSessionResponse(session, snapshot);

        // then（嵌入键即传入快照，不做摘要裁剪）
        assertEquals("分析助手", response.agent().get("name"));
        assertEquals("你是数据分析专家", response.agent().get("system"));
        assertEquals(3, response.agent().get("version"));
    }
}