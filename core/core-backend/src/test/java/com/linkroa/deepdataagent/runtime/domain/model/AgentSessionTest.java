package com.linkroa.deepdataagent.runtime.domain.model;

import com.linkroa.deepdataagent.runtime.domain.model.enums.AgentSessionStatus;
import com.linkroa.deepdataagent.runtime.domain.model.enums.MountViolationType;
import com.linkroa.deepdataagent.runtime.domain.model.enums.TurnPhase;
import com.linkroa.deepdataagent.runtime.domain.service.SessionMountPolicy;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AgentSession} 领域模型不变量单测（双列状态机）。
 * <p>覆盖：会话级 {@code status} 四态（idle / running / rescheduling / terminated）与
 * 内部轮次相位 {@code turnPhase} 四态（idle / running / awaiting_confirmation / cancelling）的
 * 独立维护语义；挂载字段（environmentId / vaultIds / memoryStoreIds / environmentVariables / resources）
 * 归一与透传；归档为 {@code archived_at} 独立正交维度（仅写时间戳、不改写 status）；
 * 调度器触发打标成对不变量；挂载行为（追加去重 / 路径占用 / 配额求和 / 仅 file 移除 / github 令牌轮换）
 * 下沉至聚合后的领域拒绝口径；owner 隔离与终态冻结谓词（{@code ownedBy} / {@code mutable}）。
 * 旧六态词汇（processing / canceling / waiting_confirmation / archived）与旧轮次三态已随改造失效。</p>
 */
class AgentSessionTest {

    private static final Long ID = 1L;
    private static final String SESSION_ID = "sess-1";
    private static final String USER_ID = "u-1";
    private static final String AGENT_ID = "agent-a";
    private static final String AGENT_VERSION = "1.0.0";
    private static final String METADATA = "{}";
    private static final String TITLE = "标题";
    private static final String ENVIRONMENT_ID = "env_1";
    private static final String TRIGGER_TYPE = "webhook";
    private static final String TRIGGER_ID = "dep-1";
    private static final String CREATED_BY = "creator";
    private static final String UPDATED_BY = "updater";

    /** 固定历史时间戳（避免恢复 / 派生断言依赖真实时钟）。 */
    private static final OffsetDateTime TS = OffsetDateTime.parse("2026-01-01T08:00:00+08:00");

    // ==================== create / createWithTrigger：初始双字段状态 ====================

    @Test
    void should_initIdleDualStatus_when_create_given_validInputs() {
        // given
        String metadata = null;

        // when
        AgentSession session = AgentSession.create(USER_ID, AGENT_ID, AGENT_VERSION, metadata, "你好会话");

        // then（新会话初始为 idle / idle：旧 created 初始态已并入 idle；业务 ID 带 sess_ 语义前缀）
        assertTrue(session.sessionId().startsWith(AgentSession.SESSION_ID_PREFIX));
        assertEquals(AgentSessionStatus.IDLE, session.status());
        assertEquals(TurnPhase.IDLE, session.turnPhase());
        assertTrue(session.runnable());
        assertEquals(USER_ID, session.userId());
        assertEquals(AGENT_ID, session.agentId());
        assertEquals(AGENT_VERSION, session.agentVersion());
        assertEquals("你好会话", session.title());
        assertEquals("{}", session.metadata());
        assertNull(session.archivedAt());
        assertFalse(session.archived());
        assertNotNull(session.lastActiveAt());
        assertNotNull(session.createdAt());
        assertNotNull(session.updatedAt());
        // 普通用户会话无触发打标
        assertNull(session.triggerType());
        assertNull(session.triggerId());
    }

    @Test
    void should_reportActiveExecution_when_hasActiveExecution_given_eachTurnPhase() {
        // given（idle / idle 会话样本；活跃执行判定口径为内部相位，非对外状态）
        AgentSession base = AgentSession.create(USER_ID, AGENT_ID, AGENT_VERSION, "{}", null);

        // when & then（活跃执行 = running / awaiting_confirmation / cancelling；idle 非活跃）
        assertFalse(base.hasActiveExecution());
        assertTrue(base.withPhase(TurnPhase.RUNNING).hasActiveExecution());
        assertTrue(base.withPhase(TurnPhase.AWAITING_CONFIRMATION).hasActiveExecution());
        assertTrue(base.withPhase(TurnPhase.CANCELLING).hasActiveExecution());
        assertFalse(base.withPhase(TurnPhase.IDLE).hasActiveExecution());
        // 对外状态不影响活跃执行判定（双列独立）
        assertTrue(base.withStatus(AgentSessionStatus.RUNNING).withPhase(TurnPhase.RUNNING).hasActiveExecution());
    }

    @Test
    void should_defaultMountFields_when_create_given_plainSession() {
        // when
        AgentSession session = validSession();

        // then（create 缺省挂载：执行环境为空，保管库 / 记忆库 / 挂载资源为空列表，环境变量为空 JSON）
        assertNull(session.environmentId());
        assertEquals(List.of(), session.vaultIds());
        assertEquals(List.of(), session.memoryStoreIds());
        assertEquals(List.of(), session.resources());
        assertEquals("{}", session.environmentVariables());
    }

    @Test
    void should_createWithTriggerMark_when_createWithTrigger_given_validTrigger() {
        // when
        AgentSession session = AgentSession.createWithTrigger(
                USER_ID, AGENT_ID, AGENT_VERSION, "{}", null, TRIGGER_TYPE, TRIGGER_ID);

        // then（触发即新建：打标来源类型 + 调度器ID 可追溯，初始同为 idle / idle）
        assertEquals(TRIGGER_TYPE, session.triggerType());
        assertEquals(TRIGGER_ID, session.triggerId());
        assertEquals(AgentSessionStatus.IDLE, session.status());
        assertEquals(TurnPhase.IDLE, session.turnPhase());
        assertTrue(session.runnable());
        assertNull(session.archivedAt());
    }

    @Test
    void should_createWithResources_when_createWithTrigger_given_resourcesAndNullTrigger() {
        // given（三类挂载资源混挂）
        List<SessionResource> resources = List.of(
                SessionResource.file("file_1", "mounts/a.txt"),
                SessionResource.githubRepository("https://github.com/acme/app.git", "ghp_token", "main"),
                SessionResource.memoryStore("ms_1", "read_only", null));

        // when
        AgentSession session = AgentSession.createWithTrigger(
                USER_ID, AGENT_ID, AGENT_VERSION, "{}", null, null, null, resources);

        // then
        assertEquals(resources, session.resources());
        assertEquals(3, session.resources().size());
        assertEquals(AgentSessionStatus.IDLE, session.status());
        assertEquals(TurnPhase.IDLE, session.turnPhase());
        assertNull(session.triggerType());
        assertNull(session.triggerId());
    }

    // ==================== createWithMounts：全量挂载与记忆库派生 ====================

    @Test
    void should_deriveMemoryStoreIdsAndPassThroughMounts_when_createWithMounts_given_mixedResources() {
        // given（混挂：file + memory_store（含重复 ms_1），重复项应保序去重）
        List<SessionResource> resources = List.of(
                SessionResource.file("file_1", "mounts/a.txt"),
                SessionResource.memoryStore("ms_1", "read_only", null),
                SessionResource.githubRepository("https://github.com/acme/app.git", "ghp_token", "main"),
                SessionResource.memoryStore("ms_2", "read_write", "参考说明"),
                SessionResource.memoryStore("ms_1", "read_write", null));

        // when
        AgentSession session = AgentSession.createWithMounts(
                USER_ID, AGENT_ID, AGENT_VERSION, null, TITLE, TRIGGER_TYPE, TRIGGER_ID,
                resources, ENVIRONMENT_ID, List.of("vault_1"), "{\"LOG_LEVEL\":\"debug\"}");

        // then（记忆库由 resources 派生：过滤非 memory_store 项、保序去重）
        assertEquals(List.of("ms_1", "ms_2"), session.memoryStoreIds());
        assertEquals(resources, session.resources());
        // 其余挂载字段透传
        assertEquals(ENVIRONMENT_ID, session.environmentId());
        assertEquals(List.of("vault_1"), session.vaultIds());
        assertEquals("{\"LOG_LEVEL\":\"debug\"}", session.environmentVariables());
        // 触发打标与初始状态与 createWithTrigger 形态一致
        assertEquals(TRIGGER_TYPE, session.triggerType());
        assertEquals(TRIGGER_ID, session.triggerId());
        assertEquals(AgentSessionStatus.IDLE, session.status());
        assertEquals(TurnPhase.IDLE, session.turnPhase());
    }

    @Test
    void should_normalizeMountFields_when_createWithMounts_given_nullEnvVarsAndVaults() {
        // when（null 挂载入参：环境变量收敛空 JSON、保管库 / 派生记忆库收敛空列表）
        AgentSession session = AgentSession.createWithMounts(
                USER_ID, AGENT_ID, AGENT_VERSION, null, TITLE, null, null,
                null, null, null, null);

        // then
        assertEquals("{}", session.environmentVariables());
        assertEquals(List.of(), session.vaultIds());
        assertEquals(List.of(), session.memoryStoreIds());
        assertEquals(List.of(), session.resources());
        assertNull(session.environmentId());
    }

    @Test
    void should_normalizeResources_when_createWithTrigger_given_nullResources() {
        // when（null 挂载资源收敛为空列表）
        AgentSession session = AgentSession.createWithTrigger(
                USER_ID, AGENT_ID, AGENT_VERSION, "{}", null, null, null, null);

        // then
        assertEquals(List.of(), session.resources());
    }

    // ==================== withAppendedResources：创建后追加挂载 ====================

    @Test
    void should_mergeResourcesAndBumpUpdatedAt_when_withAppendedResources_given_existingMounts() {
        // given（会话已挂 file_1，追加 file_2）
        SessionResource existing = SessionResource.file("file_1", "mounts/a.txt");
        AgentSession session = AgentSession.createWithMounts(
                USER_ID, AGENT_ID, AGENT_VERSION, METADATA, TITLE, null, null,
                List.of(existing), ENVIRONMENT_ID, List.of(), null);
        SessionResource appended = SessionResource.file("file_2", null);

        // when
        AgentSession updated = session.withAppendedResources(List.of(appended));

        // then（合并保序：既有在前、追加在后；原会话不可变）
        assertEquals(2, updated.resources().size());
        assertEquals(existing, updated.resources().get(0));
        assertEquals(appended, updated.resources().get(1));
        assertEquals(1, session.resources().size());
        // 仅刷新 updated_at：created_at / last_active_at 保持原值
        assertFalse(updated.updatedAt().isBefore(session.updatedAt()));
        assertEquals(session.createdAt(), updated.createdAt());
        assertEquals(session.lastActiveAt(), updated.lastActiveAt());
        // 其余挂载字段透传不变（资源 ID 随行保持稳定）
        assertEquals(session.sessionId(), updated.sessionId());
        assertEquals(existing.id(), updated.resources().get(0).id());
    }

    @Test
    void should_keepResourcesUnchanged_when_withAppendedResources_given_nullAdditional() {
        // given
        AgentSession session = AgentSession.createWithMounts(
                USER_ID, AGENT_ID, AGENT_VERSION, METADATA, TITLE, null, null,
                List.of(SessionResource.file("file_1", null)), null, null, null);

        // when（null 追加等价空追加）
        AgentSession updated = session.withAppendedResources(null);

        // then
        assertEquals(session.resources(), updated.resources());
    }

    // ==================== 挂载行为下沉：appendFiles（判重 / 路径 / 就绪 / 配额） ====================

    @Test
    void should_mergeAppendedFiles_when_appendFiles_given_materializedSizesWithinQuota() {
        // given（会话已挂 file_1@mounts/a.txt=300MB，追加 file_2 走缺省路径 100MB）
        AgentSession session = sessionWithResources(SessionResource.file("file_1", "mounts/a.txt"));
        SessionResource addition = SessionResource.file("file_2", null);
        Map<String, Long> sizes = Map.of("file_1", mb(300), "file_2", mb(100));

        // when
        AgentSession appended = session.appendFiles(List.of(addition), sizes);

        // then（既有在前、追加在后；原会话不受影响——聚合不可变派生）
        assertEquals(2, appended.resources().size());
        assertEquals("file_1", appended.resources().get(0).fileId());
        assertEquals("file_2", appended.resources().get(1).fileId());
        assertEquals("mounts/file_2", appended.resources().get(1).mountPath());
        assertEquals(1, session.resources().size());
    }

    @Test
    void should_acceptTotalExactlyAtQuota_when_appendFiles_given_sumEqualsFiveHundredMegabytes() {
        // given（既有 200MB + 追加 300MB = 恰好 500MB：上限为「不得超过」而非「必须小于」）
        AgentSession session = sessionWithResources(SessionResource.file("file_1", "mounts/a.txt"));
        Map<String, Long> sizes = Map.of("file_1", mb(200), "file_2", mb(300));

        // when
        AgentSession appended = session.appendFiles(List.of(SessionResource.file("file_2", "mounts/b.txt")), sizes);

        // then
        assertEquals(2, appended.resources().size());
    }

    @Test
    void should_countExistingWithoutMetadataAsZero_when_appendFiles_given_existingKeyMissingFromSizeMap() {
        // given（既有 file_1 在清单中缺键——按「元数据缺失计 0」口径参与求和；新增 file_2=499MB 有元数据）
        AgentSession session = sessionWithResources(SessionResource.file("file_1", "mounts/a.txt"));
        Map<String, Long> sizes = Map.of("file_2", mb(499));

        // when & then（缺键不判失败：既有计 0，总量 499MB 未越界）
        assertEquals(2, session.appendFiles(List.of(SessionResource.file("file_2", "mounts/b.txt")), sizes)
                .resources().size());
    }

    @Test
    void should_rejectDuplicateFile_when_appendFiles_given_fileAlreadyMounted() {
        // given（同一 file_1 以不同挂载路径再次追加）
        AgentSession session = sessionWithResources(SessionResource.file("file_1", "mounts/a.txt"));
        Map<String, Long> sizes = Map.of("file_1", mb(10));

        // when & then
        MountViolationException ex = assertThrows(MountViolationException.class,
                () -> session.appendFiles(List.of(SessionResource.file("file_1", "mounts/b.txt")), sizes));

        // then（冲突种类 + 冲突对象 + 消息文本单点口径）
        assertEquals(MountViolationType.DUPLICATE_FILE, ex.violation());
        assertEquals("file_1", ex.subject());
        assertTrue(ex.getMessage().contains("文件重复挂载"));
        // 领域拒绝继承 IllegalArgumentException：未接住即落 400
        assertTrue(ex instanceof IllegalArgumentException);
    }

    @Test
    void should_rejectDuplicateFile_when_appendFiles_given_sameFileTwiceWithinBatch() {
        // given（批内自重复：两条同 fileId，会话原本无挂载）
        AgentSession session = validSession();
        Map<String, Long> sizes = Map.of("file_1", mb(10));

        // when & then
        MountViolationException ex = assertThrows(MountViolationException.class,
                () -> session.appendFiles(
                        List.of(SessionResource.file("file_1", "mounts/a.txt"),
                                SessionResource.file("file_1", "mounts/b.txt")), sizes));

        assertEquals(MountViolationType.DUPLICATE_FILE, ex.violation());
    }

    @Test
    void should_rejectOccupiedPath_when_appendFiles_given_mountPathUsedByAnotherFile() {
        // given（不同文件抢占同一挂载路径 mounts/a.txt）
        AgentSession session = sessionWithResources(SessionResource.file("file_1", "mounts/a.txt"));
        Map<String, Long> sizes = Map.of("file_2", mb(10));

        // when & then
        MountViolationException ex = assertThrows(MountViolationException.class,
                () -> session.appendFiles(List.of(SessionResource.file("file_2", "mounts/a.txt")), sizes));

        assertEquals(MountViolationType.DUPLICATE_MOUNT_PATH, ex.violation());
        assertEquals("mounts/a.txt", ex.subject());
        assertTrue(ex.getMessage().contains("挂载路径已被占用"));
    }

    @Test
    void should_rejectUnreadyFile_when_appendFiles_given_sizeMapMissingKeyForAddition() {
        // given（清单缺新增项键 = 应用层判定其不存在 / 越权 / 未就绪）
        AgentSession session = validSession();
        Map<String, Long> sizes = Map.of("file_1", mb(10));

        // when & then
        MountViolationException ex = assertThrows(MountViolationException.class,
                () -> session.appendFiles(
                        List.of(SessionResource.file("file_1", "mounts/a.txt"),
                                SessionResource.file("file_2", "mounts/b.txt")), sizes));

        assertEquals(MountViolationType.FILE_NOT_MOUNTABLE, ex.violation());
        assertEquals("file_2", ex.subject());
    }

    @Test
    void should_rejectQuota_when_appendFiles_given_sumExceedsFiveHundredMegabytes() {
        // given（既有 400MB + 追加两条：99MB 通过（累计 499MB）、2MB 使总量达 501MB）
        AgentSession session = sessionWithResources(SessionResource.file("file_1", "mounts/a.txt"));
        Map<String, Long> sizes = Map.of("file_1", mb(400), "file_2", mb(99), "file_3", mb(2));

        // when & then（整批全有或全无：越界项被点名，会话挂载零变更）
        MountViolationException ex = assertThrows(MountViolationException.class,
                () -> session.appendFiles(
                        List.of(SessionResource.file("file_2", "mounts/b.txt"),
                                SessionResource.file("file_3", "mounts/c.txt")), sizes));

        assertEquals(MountViolationType.QUOTA_EXCEEDED, ex.violation());
        assertEquals("file_3", ex.subject());
        assertEquals(1, session.resources().size());
    }

    @Test
    void should_rejectQuotaByOneByte_when_appendFiles_given_totalExceedsLimitMarginally() {
        // given（恰好越界一字节）
        AgentSession session = validSession();
        long oversize = SessionMountPolicy.MAX_MOUNTED_TOTAL_BYTES + 1;

        // when & then
        MountViolationException ex = assertThrows(MountViolationException.class,
                () -> session.appendFiles(List.of(SessionResource.file("file_1", null)),
                        Map.of("file_1", oversize)));

        assertEquals(MountViolationType.QUOTA_EXCEEDED, ex.violation());
    }

    @Test
    void should_rejectNonFileAppend_when_appendFiles_given_githubRepositoryAddition() {
        // given（本期仅 file 可追加，且该批次无字节元数据）
        AgentSession session = validSession();

        // when & then（类型门禁先于就绪 / 配额判定）
        MountViolationException ex = assertThrows(MountViolationException.class,
                () -> session.appendFiles(
                        List.of(SessionResource.githubRepository("https://github.com/acme/app.git", "ghp_t", "main")),
                        Map.of()));

        assertEquals(MountViolationType.APPEND_TYPE_UNSUPPORTED, ex.violation());
        assertEquals(SessionResource.GITHUB_REPO_TYPE, ex.subject());
    }

    // ==================== 挂载行为下沉：removeResource / rotateResourceToken / findResource ====================

    @Test
    void should_removeFileResource_when_removeResource_given_fileMountedWithMemoryStore() {
        // given（混挂：file_1 + 记忆库 ms_1）
        AgentSession session = sessionWithResources(
                SessionResource.file("file_1", "mounts/a.txt"),
                SessionResource.memoryStore("ms_1", "read_only", null));
        String fileId = session.resources().get(0).id();

        // when
        AgentSession remaining = session.removeResource(fileId);

        // then（仅剔除目标项，记忆库关联按替换规则重算后保持）
        assertEquals(1, remaining.resources().size());
        assertEquals(SessionResource.MEMORY_STORE_TYPE, remaining.resources().get(0).type());
        assertEquals(List.of("ms_1"), remaining.memoryStoreIds());
        assertEquals(2, session.resources().size());
    }

    @Test
    void should_rejectRemove_when_removeResource_given_githubRepositoryMount() {
        // given
        AgentSession session = sessionWithResources(
                SessionResource.githubRepository("https://github.com/acme/app.git", "ghp_t", "main"));
        String repoId = session.resources().get(0).id();

        // when & then（github 挂载创建后不可摘除）
        MountViolationException ex = assertThrows(MountViolationException.class,
                () -> session.removeResource(repoId));

        assertEquals(MountViolationType.REMOVE_TYPE_UNSUPPORTED, ex.violation());
        assertEquals(SessionResource.GITHUB_REPO_TYPE, ex.subject());
        assertTrue(ex.getMessage().contains("不可摘除"));
    }

    @Test
    void should_rejectRemove_when_removeResource_given_memoryStoreMount() {
        // given
        AgentSession session = sessionWithResources(SessionResource.memoryStore("ms_1", null, null));
        String memoryStoreId = session.resources().get(0).id();

        // when & then
        MountViolationException ex = assertThrows(MountViolationException.class,
                () -> session.removeResource(memoryStoreId));

        assertEquals(MountViolationType.REMOVE_TYPE_UNSUPPORTED, ex.violation());
        assertEquals(SessionResource.MEMORY_STORE_TYPE, ex.subject());
    }

    @Test
    void should_reportNotFound_when_removeResource_given_unknownResourceId() {
        // given
        AgentSession session = sessionWithResources(SessionResource.file("file_1", null));

        // when & then（未命中为资源寻址失败，状态码由应用层映射 404）
        MountViolationException ex = assertThrows(MountViolationException.class,
                () -> session.removeResource("sesr_missing"));

        assertEquals(MountViolationType.MOUNTED_RESOURCE_NOT_FOUND, ex.violation());
        assertEquals("sesr_missing", ex.subject());
    }

    @Test
    void should_replaceTokenOnly_when_rotateResourceToken_given_githubMount() {
        // given
        AgentSession session = sessionWithResources(
                SessionResource.githubRepository("https://github.com/acme/app.git", "ghp_old", "main"));
        String repoId = session.resources().get(0).id();

        // when
        AgentSession rotated = session.rotateResourceToken(repoId, "ghp_new");

        // then（资源 ID 与仓库地址 / 检出分支保持不变，仅令牌覆盖）
        assertEquals(1, rotated.resources().size());
        assertEquals(repoId, rotated.resources().get(0).id());
        assertEquals("ghp_new", rotated.resources().get(0).authorizationToken());
        assertEquals("https://github.com/acme/app.git", rotated.resources().get(0).url());
        assertEquals("main", rotated.resources().get(0).checkout());
        assertEquals("ghp_old", session.resources().get(0).authorizationToken());
    }

    @Test
    void should_rejectBlankToken_when_rotateResourceToken_given_blankNewToken() {
        // given
        AgentSession session = sessionWithResources(
                SessionResource.githubRepository("https://github.com/acme/app.git", "ghp_old", "main"));
        String repoId = session.resources().get(0).id();

        // when & then（空令牌轮换会清空既有凭证，必须先于寻址与类型门禁拒绝）
        MountViolationException ex = assertThrows(MountViolationException.class,
                () -> session.rotateResourceToken(repoId, "  "));

        assertEquals(MountViolationType.TOKEN_BLANK, ex.violation());
        assertNull(ex.subject());
    }

    @Test
    void should_preferBlankTokenViolation_when_rotateResourceToken_given_blankTokenAndUnknownResource() {
        // given（两个条件同时成立：判定顺序即状态码优先级 400 先于 404）
        AgentSession session = sessionWithResources(SessionResource.file("file_1", null));

        // when & then
        MountViolationException ex = assertThrows(MountViolationException.class,
                () -> session.rotateResourceToken("sesr_missing", null));

        assertEquals(MountViolationType.TOKEN_BLANK, ex.violation());
    }

    @Test
    void should_reportNotFound_when_rotateResourceToken_given_unknownResourceId() {
        // given
        AgentSession session = sessionWithResources(SessionResource.file("file_1", null));

        // when & then
        MountViolationException ex = assertThrows(MountViolationException.class,
                () -> session.rotateResourceToken("sesr_missing", "ghp_new"));

        assertEquals(MountViolationType.MOUNTED_RESOURCE_NOT_FOUND, ex.violation());
    }

    @Test
    void should_rejectRotate_when_rotateResourceToken_given_fileMount() {
        // given
        AgentSession session = sessionWithResources(SessionResource.file("file_1", null));
        String fileId = session.resources().get(0).id();

        // when & then
        MountViolationException ex = assertThrows(MountViolationException.class,
                () -> session.rotateResourceToken(fileId, "ghp_new"));

        assertEquals(MountViolationType.ROTATE_TYPE_UNSUPPORTED, ex.violation());
        assertEquals(SessionResource.FILE_TYPE, ex.subject());
    }

    @Test
    void should_findMountedResource_when_findResource_given_hitAndMiss() {
        // given
        AgentSession session = sessionWithResources(
                SessionResource.file("file_1", "mounts/a.txt"),
                SessionResource.githubRepository("https://github.com/acme/app.git", "ghp_t", null));
        String repoId = session.resources().get(1).id();

        // when & then（命中返回条目本体，未命中返回 empty 交由调用方映射状态码）
        assertTrue(session.findResource(repoId).isPresent());
        assertEquals("https://github.com/acme/app.git", session.findResource(repoId).orElseThrow().url());
        assertTrue(session.findResource("sesr_missing").isEmpty());
        assertTrue(validSession().findResource("sesr_any").isEmpty());
    }

    // ==================== 聚合谓词单点：ownedBy / mutable ====================

    @Test
    void should_matchOwner_when_ownedBy_given_numericUserIdEquals() {
        // given（会话 userId 落库存的是数字 user_id 的字符串形态）
        AgentSession session = newSession(SESSION_ID, "1", AGENT_ID, AGENT_VERSION,
                AgentSessionStatus.IDLE, TurnPhase.IDLE, METADATA, TITLE, null);

        // when & then
        assertTrue(session.ownedBy(1L));
        assertFalse(session.ownedBy(2L));
    }

    @Test
    void should_rejectOwner_when_ownedBy_given_legacyNonNumericUserId() {
        // given（历史非数字 userId 无法匹配任何数字用户：与「越权即 404」口径一致）
        AgentSession session = validSession();

        // when & then（USER_ID 常量为 "u-1"）
        assertFalse(session.ownedBy(1L));
        assertFalse(session.ownedBy(0L));
    }

    @Test
    void should_allowMountChanges_when_mutable_given_nonTerminalStatuses() {
        // given
        AgentSession session = validSession();

        // when & then（idle / running / rescheduling 均可变更挂载）
        assertTrue(session.withStatus(AgentSessionStatus.IDLE).mutable());
        assertTrue(session.withStatus(AgentSessionStatus.RUNNING).mutable());
        assertTrue(session.withStatus(AgentSessionStatus.RESCHEDULING).mutable());
    }

    @Test
    void should_freezeMounts_when_mutable_given_archivedOrTerminated() {
        // given
        AgentSession session = validSession();

        // when & then（归档 / 终止两态挂载冻结）
        assertFalse(session.withArchived().mutable());
        assertFalse(session.withStatus(AgentSessionStatus.TERMINATED).mutable());
    }

    @Test
    void should_throw_when_createWithTrigger_given_triggerIdWithoutType() {
        // given & when & then（触发标记成对不变量：来源ID存在时必须携带来源类型）
        assertThrows(IllegalArgumentException.class,
                () -> AgentSession.createWithTrigger(
                        USER_ID, AGENT_ID, AGENT_VERSION, "{}", null, null, TRIGGER_ID));
    }

    // ==================== 构造器不变量：必填字段 / 标题长度 / 触发成对 / 归档双写 ====================

    @Test
    void should_throw_when_construct_given_blankSessionId() {
        // given & when & then
        assertThrows(IllegalArgumentException.class,
                () -> newSession("", USER_ID, AGENT_ID, AGENT_VERSION,
                        AgentSessionStatus.IDLE, TurnPhase.IDLE, METADATA, TITLE, null));
    }

    @Test
    void should_throw_when_construct_given_blankUserId() {
        // given & when & then
        assertThrows(IllegalArgumentException.class,
                () -> newSession(SESSION_ID, " ", AGENT_ID, AGENT_VERSION,
                        AgentSessionStatus.IDLE, TurnPhase.IDLE, METADATA, TITLE, null));
    }

    @Test
    void should_throw_when_construct_given_blankAgentId() {
        // given & when & then
        assertThrows(IllegalArgumentException.class,
                () -> newSession(SESSION_ID, USER_ID, "", AGENT_VERSION,
                        AgentSessionStatus.IDLE, TurnPhase.IDLE, METADATA, TITLE, null));
    }

    @Test
    void should_throw_when_construct_given_blankAgentVersion() {
        // given & when & then
        assertThrows(IllegalArgumentException.class,
                () -> newSession(SESSION_ID, USER_ID, AGENT_ID, null,
                        AgentSessionStatus.IDLE, TurnPhase.IDLE, METADATA, TITLE, null));
    }

    @Test
    void should_throw_when_construct_given_nullStatus() {
        // given & when & then（会话级状态不得为空）
        assertThrows(IllegalArgumentException.class,
                () -> newSession(SESSION_ID, USER_ID, AGENT_ID, AGENT_VERSION,
                        null, TurnPhase.IDLE, METADATA, TITLE, null));
    }

    @Test
    void should_throw_when_construct_given_nullTurnPhase() {
        // given & when & then（内部相位为独立字段，构造器层同样不得为空）
        assertThrows(IllegalArgumentException.class,
                () -> newSession(SESSION_ID, USER_ID, AGENT_ID, AGENT_VERSION,
                        AgentSessionStatus.IDLE, null, METADATA, TITLE, null));
    }

    @Test
    void should_throw_when_construct_given_nullMetadata() {
        // given & when & then
        assertThrows(IllegalArgumentException.class,
                () -> newSession(SESSION_ID, USER_ID, AGENT_ID, AGENT_VERSION,
                        AgentSessionStatus.IDLE, TurnPhase.IDLE, null, TITLE, null));
    }

    @Test
    void should_throw_when_construct_given_titleTooLong() {
        // given
        String tooLong = "t".repeat(256);

        // when & then
        assertThrows(IllegalArgumentException.class,
                () -> newSession(SESSION_ID, USER_ID, AGENT_ID, AGENT_VERSION,
                        AgentSessionStatus.IDLE, TurnPhase.IDLE, METADATA, tooLong, null));
    }

    @Test
    void should_acceptMaxLengthTitle_when_construct_given_titleWith255Chars() {
        // given
        String maxLength = "t".repeat(255);

        // when
        AgentSession session = newSession(SESSION_ID, USER_ID, AGENT_ID, AGENT_VERSION,
                AgentSessionStatus.IDLE, TurnPhase.IDLE, METADATA, maxLength, null);

        // then（255 为闭区间上界）
        assertEquals(maxLength, session.title());
    }

    @Test
    void should_allowArbitraryStatusAndArchivedAtCombination_when_construct_given_archivedTimestamp() {
        // given & when（归档为独立正交维度：archived_at 可与任意 status 组合，非终态亦可归档）
        AgentSession archived = newSession(SESSION_ID, USER_ID, AGENT_ID, AGENT_VERSION,
                AgentSessionStatus.IDLE, TurnPhase.IDLE, METADATA, TITLE, TS);

        // then
        assertTrue(archived.archived());
        assertEquals(AgentSessionStatus.IDLE, archived.status());
    }

    @Test
    void should_throw_when_construct_given_triggerIdWithoutTriggerType() {
        // given & when & then（canonical 构造器同样守卫触发成对不变量）
        assertThrows(IllegalArgumentException.class, () -> newTriggeredSession(null, TRIGGER_ID));
    }

    @Test
    void should_acceptTriggerTypeWithoutId_when_construct_given_typeOnly() {
        // when（触发类型可单独存在：如手工会话标注来源而无调度器ID）
        AgentSession session = newTriggeredSession("cron", null);

        // then
        assertEquals("cron", session.triggerType());
        assertNull(session.triggerId());
    }

    // ==================== 元数据归一 ====================

    @Test
    void should_defaultMetadata_when_create_given_blankMetadata() {
        // given
        String blank = "  ";

        // when
        AgentSession session = AgentSession.create(USER_ID, AGENT_ID, AGENT_VERSION, blank, null);

        // then
        assertEquals("{}", session.metadata());
    }

    @Test
    void should_preserveMetadata_when_create_given_nonBlankMetadata() {
        // given
        String metadata = "{\"external_user_id\":\"eu-1\"}";

        // when
        AgentSession session = AgentSession.create(USER_ID, AGENT_ID, AGENT_VERSION, metadata, null);

        // then
        assertEquals(metadata, session.metadata());
    }

    @Test
    void should_defaultMetadata_when_restore_given_nullMetadata() {
        // when
        AgentSession restored = restored(AgentSessionStatus.IDLE, TurnPhase.IDLE, null, null);

        // then
        assertEquals("{}", restored.metadata());
    }

    // ==================== restore：相位缺省 / 显式值保留 ====================

    @Test
    void should_defaultPhaseToIdle_when_restore_given_nullTurnPhase() {
        // given（存量库行可能缺 turn_phase：恢复时收敛为 idle，对外状态原样保留）
        List<AgentSessionStatus> allStatuses = List.of(AgentSessionStatus.IDLE, AgentSessionStatus.RUNNING,
                AgentSessionStatus.RESCHEDULING, AgentSessionStatus.TERMINATED);

        // when & then（四态逐一覆盖：null 相位一律回落 idle）
        for (AgentSessionStatus status : allStatuses) {
            AgentSession restored = restored(status, null, METADATA, null);
            assertEquals(TurnPhase.IDLE, restored.turnPhase(), "缺省相位应回落 idle: " + status);
            assertEquals(status, restored.status());
        }
    }

    @Test
    void should_preserveExplicitTurnPhase_when_restore_given_nonNullTurnPhase() {
        // when（两列独立维护：库中显式 turn_phase 不被会话级状态强制覆盖）
        AgentSession restored = restored(AgentSessionStatus.RUNNING, TurnPhase.AWAITING_CONFIRMATION,
                METADATA, null);

        // then
        assertEquals(AgentSessionStatus.RUNNING, restored.status());
        assertEquals(TurnPhase.AWAITING_CONFIRMATION, restored.turnPhase());
    }

    @Test
    void should_preserveMountAndTriggerFields_when_restore_given_persistedRow() {
        // given
        List<String> vaultIds = List.of("vault_1");
        List<String> memoryStoreIds = List.of("ms_1", "ms_2");
        String envVars = "{\"OPENAI_BASE\":\"https://api.example.com\"}";
        List<SessionResource> resources = List.of(SessionResource.memoryStore("ms_1", "read_write", null));

        // when
        AgentSession restored = restoredWithMounts(vaultIds, memoryStoreIds, envVars, resources);

        // then（restore 原样恢复挂载模型、触发打标与审计字段）
        assertEquals(ID, restored.id());
        assertEquals(SESSION_ID, restored.sessionId());
        assertEquals(ENVIRONMENT_ID, restored.environmentId());
        assertEquals(vaultIds, restored.vaultIds());
        assertEquals(memoryStoreIds, restored.memoryStoreIds());
        assertEquals(envVars, restored.environmentVariables());
        assertEquals(resources, restored.resources());
        assertEquals(TRIGGER_TYPE, restored.triggerType());
        assertEquals(TRIGGER_ID, restored.triggerId());
        assertEquals(CREATED_BY, restored.createdBy());
        assertEquals(UPDATED_BY, restored.updatedBy());
    }

    // ==================== 列表 / JSON 字段归一 ====================

    @Test
    void should_normalizeListAndJsonFields_when_restore_given_nullCollectionsAndBlankEnvVars() {
        // when（null 列表收敛为空列表、空白环境变量 JSON 收敛为空对象文本）
        AgentSession session = restoredWithMounts(null, null, "   ", null);

        // then
        assertEquals(List.of(), session.vaultIds());
        assertEquals(List.of(), session.memoryStoreIds());
        assertEquals(List.of(), session.resources());
        assertEquals("{}", session.environmentVariables());
    }

    @Test
    void should_copyCollectionsDefensively_when_restore_given_mutableLists() {
        // given（调用方持有可变列表：会话须防御性复制且对外不可变）
        List<String> vaultIds = new ArrayList<>(List.of("vault_1"));
        List<SessionResource> resources = new ArrayList<>(List.of(SessionResource.file("file_1", null)));

        // when
        AgentSession session = restoredWithMounts(vaultIds, List.of(), "{}", resources);
        vaultIds.add("vault_2");
        resources.add(SessionResource.file("file_2", null));

        // then（外部集合后续变更不影响已构造会话）
        assertEquals(List.of("vault_1"), session.vaultIds());
        assertEquals(1, session.resources().size());
        assertThrows(UnsupportedOperationException.class, () -> session.vaultIds().add("vault_3"));
        assertThrows(UnsupportedOperationException.class,
                () -> session.resources().add(SessionResource.file("file_3", null)));
    }

    // ==================== withStatus / withPhase：双列独立派生 ====================

    @Test
    void should_changeOnlyStatusAndKeepPhase_when_withStatus_given_eachSessionStatus() {
        // given
        AgentSession session = validSession().withPhase(TurnPhase.AWAITING_CONFIRMATION);

        // when & then（withStatus 只改写对外状态列，内部相位原样保留）
        for (AgentSessionStatus status : AgentSessionStatus.values()) {
            AgentSession derived = session.withStatus(status);
            assertEquals(status, derived.status(), "对外状态应被改写: " + status);
            assertEquals(TurnPhase.AWAITING_CONFIRMATION, derived.turnPhase(), "相位不应被 withStatus 改写");
        }
    }

    @Test
    void should_changeOnlyPhaseAndKeepStatus_when_withPhase_given_eachTurnPhase() {
        // given
        AgentSession session = validSession().withStatus(AgentSessionStatus.RUNNING);

        // when & then（withPhase 只改写内部相位列，对外状态原样保留）
        for (TurnPhase phase : TurnPhase.values()) {
            AgentSession derived = session.withPhase(phase);
            assertEquals(phase, derived.turnPhase(), "内部相位应被改写: " + phase);
            assertEquals(AgentSessionStatus.RUNNING, derived.status(), "对外状态不应被 withPhase 改写");
        }
    }

    @Test
    void should_refreshActiveTime_when_withStatus_given_runningStatus() {
        // given
        AgentSession session = validSession();
        OffsetDateTime before = OffsetDateTime.now();

        // when
        AgentSession running = session.withStatus(AgentSessionStatus.RUNNING);

        // then（CAS 后回填内存视图：状态切换 + last_active_at / updated_at 刷新，created_at / archived_at 不变）
        assertEquals(AgentSessionStatus.RUNNING, running.status());
        assertFalse(running.runnable());
        assertFalse(running.lastActiveAt().isBefore(before));
        assertFalse(running.updatedAt().isBefore(before));
        assertEquals(session.createdAt(), running.createdAt());
        assertEquals(session.archivedAt(), running.archivedAt());
        assertNotEquals(session, running);
    }

    @Test
    void should_preserveMountAndAuditFields_when_withStatusAndArchive_given_mountedSession() {
        // given（会话挂载执行环境、保管库、记忆库、环境变量与两类挂载资源）
        List<String> vaultIds = List.of("vault_1", "vault_2");
        List<String> memoryStoreIds = List.of("ms_1");
        String envVars = "{\"OPENAI_BASE\":\"https://api.example.com\"}";
        List<SessionResource> resources = List.of(
                SessionResource.file("file_1", "mounts/a.txt"),
                SessionResource.githubRepository("https://github.com/acme/app.git", "ghp_token", "main"));
        AgentSession session = restoredWithMounts(vaultIds, memoryStoreIds, envVars, resources);

        // when（状态 / 相位派生与归档均须原样透传挂载字段）
        AgentSession running = session.withStatus(AgentSessionStatus.RUNNING).withPhase(TurnPhase.RUNNING);
        AgentSession archived = session.withArchived();

        // then
        for (AgentSession derived : List.of(running, archived)) {
            assertEquals(ENVIRONMENT_ID, derived.environmentId(), "执行环境不得丢失");
            assertEquals(vaultIds, derived.vaultIds(), "保管库列表不得丢失");
            assertEquals(memoryStoreIds, derived.memoryStoreIds(), "记忆库列表不得丢失");
            assertEquals(envVars, derived.environmentVariables(), "会话级环境变量不得丢失");
            assertEquals(resources, derived.resources(), "挂载资源不得丢失");
            assertEquals(CREATED_BY, derived.createdBy());
            assertEquals(UPDATED_BY, derived.updatedBy());
        }
    }

    // ==================== withArchived：归档仅写时间戳（正交维度） ====================

    @Test
    void should_onlyStampArchivedAtAndKeepStatus_when_withArchived_given_runningSession() {
        // given
        AgentSession session = validSession().withStatus(AgentSessionStatus.RUNNING).withPhase(TurnPhase.RUNNING);

        // when（归档 = 仅置 archived_at，status 与相位保持原值）
        AgentSession archived = session.withArchived();

        // then
        assertEquals(AgentSessionStatus.RUNNING, archived.status());
        assertEquals(TurnPhase.RUNNING, archived.turnPhase());
        assertNotNull(archived.archivedAt());
        assertTrue(archived.archived());
    }

    @Test
    void should_reportArchivedByTimestampOnly_when_archived_given_statusCombinations() {
        // given & when & then（归档判定只看 archived_at，与 status 正交）
        assertTrue(validSession().withArchived().archived());
        assertTrue(restored(AgentSessionStatus.TERMINATED, TurnPhase.IDLE, METADATA, TS).archived());
        assertFalse(validSession().archived());
        // 未置时间戳即视为未归档（不论 status）
        assertFalse(restored(AgentSessionStatus.TERMINATED, TurnPhase.IDLE, METADATA, null).archived());
    }

    // ==================== 触发打标透传 ====================

    @Test
    void should_preserveTriggerMark_when_withStatusAndArchive_given_triggeredSession() {
        // given
        AgentSession triggered = AgentSession.createWithTrigger(
                USER_ID, AGENT_ID, AGENT_VERSION, "{}", null, TRIGGER_TYPE, TRIGGER_ID);

        // when（状态 / 相位派生与归档均不得丢失触发溯源标记）
        AgentSession running = triggered.withStatus(AgentSessionStatus.RUNNING).withPhase(TurnPhase.RUNNING);
        AgentSession archived = triggered.withArchived();

        // then
        for (AgentSession derived : List.of(running, archived)) {
            assertEquals(TRIGGER_TYPE, derived.triggerType());
            assertEquals(TRIGGER_ID, derived.triggerId());
        }
    }

    // ==================== runnable：可抢占态判定 ====================

    @Test
    void should_beRunnableOnlyWhenIdle_when_runnable_given_allSessionStatuses() {
        // given
        AgentSession session = validSession();

        // when & then（可运行态仅 idle：running / rescheduling / terminated 一律不可抢占）
        assertTrue(session.runnable());
        assertTrue(session.withStatus(AgentSessionStatus.IDLE).runnable());
        assertFalse(session.withStatus(AgentSessionStatus.RUNNING).runnable());
        assertFalse(session.withStatus(AgentSessionStatus.RESCHEDULING).runnable());
        assertFalse(session.withStatus(AgentSessionStatus.TERMINATED).runnable());
        // 归档与 status / 可运行态正交：仅写 archived_at，不影响 runnable 判定
        assertTrue(session.withArchived().runnable());
    }

    // ==================== 状态 / 相位词汇迁移（旧六态已失效） ====================

    @Test
    void should_exposeFourStateVocabulary_when_valueAndFromValue_given_newStatuses() {
        // given & when & then（四态小写规范取值 + 事件类型派生 + 大小写不敏感解析）
        assertEquals(4, AgentSessionStatus.values().length);
        assertEquals("idle", AgentSessionStatus.IDLE.value());
        assertEquals("running", AgentSessionStatus.RUNNING.value());
        assertEquals("rescheduling", AgentSessionStatus.RESCHEDULING.value());
        assertEquals("terminated", AgentSessionStatus.TERMINATED.value());
        assertEquals("session.status_running", AgentSessionStatus.RUNNING.statusEventType());
        assertEquals("session.status_terminated", AgentSessionStatus.TERMINATED.statusEventType());
        assertEquals(AgentSessionStatus.RESCHEDULING, AgentSessionStatus.fromValue(" RESCHEDULING "));
        assertNull(AgentSessionStatus.fromValue(null));
        assertNull(AgentSessionStatus.fromValue("  "));
    }

    @Test
    void should_rejectLegacyStatusVocabulary_when_fromValue_given_removedStatusValues() {
        // given（processing 更名 running、canceling / waiting_confirmation / archived 已移出状态取值域）
        List<String> legacyValues = List.of("created", "processing", "canceling", "waiting_confirmation",
                "archived", "requires_action");

        // when & then
        for (String value : legacyValues) {
            assertThrows(IllegalArgumentException.class, () -> AgentSessionStatus.fromValue(value),
                    "旧状态取值应已失效: " + value);
        }
    }

    @Test
    void should_exposeFourPhaseVocabulary_when_valueAndFromValue_given_turnPhases() {
        // given & when & then（内部相位四态取值域独立于对外四态，且无 forSessionStatus 派生）
        assertEquals(4, TurnPhase.values().length);
        assertEquals("idle", TurnPhase.IDLE.value());
        assertEquals("running", TurnPhase.RUNNING.value());
        assertEquals("awaiting_confirmation", TurnPhase.AWAITING_CONFIRMATION.value());
        assertEquals("cancelling", TurnPhase.CANCELLING.value());
        assertEquals(TurnPhase.RUNNING, TurnPhase.fromValue("RUNNING"));
        assertNull(TurnPhase.fromValue("  "));
        assertThrows(IllegalArgumentException.class, () -> TurnPhase.fromValue("processing"));
        // 活跃相位口径：除 idle 外皆活跃
        assertFalse(TurnPhase.IDLE.active());
        assertTrue(TurnPhase.RUNNING.active());
        assertTrue(TurnPhase.AWAITING_CONFIRMATION.active());
        assertTrue(TurnPhase.CANCELLING.active());
    }

    // ==================== 测试夹具 ====================

    /**
     * 规范可运行会话（create 派生，初始 idle / idle）。
     */
    private AgentSession validSession() {
        return AgentSession.create(USER_ID, AGENT_ID, AGENT_VERSION, METADATA, TITLE);
    }

    /**
     * 以 canonical 构造器装配会话（仅开放核心字段与归档时间，其余取规范默认值），供逐字段不变量用例复用。
     */
    private AgentSession newSession(String sessionId, String userId, String agentId, String agentVersion,
            AgentSessionStatus status, TurnPhase turnPhase, String metadata, String title,
            OffsetDateTime archivedAt) {
        return new AgentSession(ID, sessionId, userId, agentId, agentVersion, status, turnPhase, metadata, title,
                ENVIRONMENT_ID, List.of(), List.of(), "{}", List.of(), null, null,
                TS, archivedAt, TS, TS, CREATED_BY, UPDATED_BY);
    }

    /**
     * 以 canonical 构造器装配会话（仅开放触发标记字段），供触发成对不变量用例复用。
     */
    private AgentSession newTriggeredSession(String triggerType, String triggerId) {
        return new AgentSession(ID, SESSION_ID, USER_ID, AGENT_ID, AGENT_VERSION,
                AgentSessionStatus.IDLE, TurnPhase.IDLE, METADATA, TITLE,
                ENVIRONMENT_ID, List.of(), List.of(), "{}", List.of(), triggerType, triggerId,
                TS, null, TS, TS, CREATED_BY, UPDATED_BY);
    }

    /**
     * 以规范取值经 restore 恢复会话（仅开放双字段状态、元数据与归档时间），供状态派生用例复用。
     */
    private AgentSession restored(AgentSessionStatus status, TurnPhase turnPhase, String metadata,
            OffsetDateTime archivedAt) {
        return AgentSession.restore(ID, SESSION_ID, USER_ID, AGENT_ID, AGENT_VERSION, status, turnPhase, metadata,
                TITLE, ENVIRONMENT_ID, List.of(), List.of(), "{}", List.of(),
                TRIGGER_TYPE, TRIGGER_ID, TS, archivedAt, TS, TS, CREATED_BY, UPDATED_BY);
    }

    /**
     * 以规范取值经 restore 恢复会话（仅开放挂载列表、环境变量 JSON 与挂载资源），供归一化 / 透传用例复用。
     */
    private AgentSession restoredWithMounts(List<String> vaultIds, List<String> memoryStoreIds,
            String environmentVariables, List<SessionResource> resources) {
        return AgentSession.restore(ID, SESSION_ID, USER_ID, AGENT_ID, AGENT_VERSION,
                AgentSessionStatus.IDLE, null, METADATA, TITLE, ENVIRONMENT_ID,
                vaultIds, memoryStoreIds, environmentVariables, resources,
                TRIGGER_TYPE, TRIGGER_ID, TS, null, TS, TS, CREATED_BY, UPDATED_BY);
    }

    /**
     * 装配带挂载资源的 idle 会话（挂载行为用例共用夹具，资源业务 ID 由值对象自动补齐）。
     */
    private AgentSession sessionWithResources(SessionResource... resources) {
        return AgentSession.createWithMounts(USER_ID, AGENT_ID, AGENT_VERSION, METADATA, TITLE,
                null, null, List.of(resources), null, null, null);
    }

    /**
     * 兆字节换算（配额用例统一入参写法，避免测试内散落 1024*1024 字面量）。
     */
    private static long mb(long megabytes) {
        return megabytes * 1024L * 1024L;
    }
}