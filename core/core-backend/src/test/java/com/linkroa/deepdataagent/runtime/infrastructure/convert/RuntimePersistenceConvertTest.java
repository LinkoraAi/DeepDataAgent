package com.linkroa.deepdataagent.runtime.infrastructure.convert;

import com.linkroa.deepdataagent.runtime.domain.model.AgentSession;
import com.linkroa.deepdataagent.runtime.domain.model.ChatEvent;
import com.linkroa.deepdataagent.runtime.domain.model.SessionResource;
import com.linkroa.deepdataagent.runtime.domain.model.enums.AgentSessionStatus;
import com.linkroa.deepdataagent.runtime.domain.model.enums.ChatEventType;
import com.linkroa.deepdataagent.runtime.infrastructure.persistence.entity.AgentSessionEntity;
import com.linkroa.deepdataagent.runtime.infrastructure.persistence.entity.ChatEventEntity;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RuntimePersistenceConvert} 领域 ⇄ 实体映射单测。
 * <p>事件溯源模型下仅剩 AgentSession 与 ChatEvent（payload 信封）两组映射；
 * ExecutionRound / RunTrace 映射已随物化表删除。</p>
 */
class RuntimePersistenceConvertTest {

    private final RuntimePersistenceConvert mapper = RuntimePersistenceConvert.INSTANCE;

    // ===== AgentSession =====

    @Test
    void should_mapToEntityAndBack_when_saveAndRestore_given_agentSession() {
        // given
        AgentSession session = AgentSession.create("u-1", "agent-a", "1.0.0", "{\"eu\":\"e1\"}", "会话标题");
        AgentSession running = session.withStatus(AgentSessionStatus.TERMINATED);

        // when
        AgentSessionEntity entity = mapper.toEntity(running);
        AgentSession restored = mapper.toDomain(entity);

        // then（状态以规范小写字符串落库并回读）
        assertEquals(running.sessionId(), restored.sessionId());
        assertEquals(running.userId(), restored.userId());
        assertEquals(running.agentId(), restored.agentId());
        assertEquals(running.agentVersion(), restored.agentVersion());
        assertEquals(AgentSessionStatus.TERMINATED, restored.status());
        assertEquals("terminated", entity.getStatus());
        assertEquals("{\"eu\":\"e1\"}", restored.metadata());
        assertEquals(running.title(), restored.title());
    }

    @Test
    void should_roundTripResources_when_saveAndRestore_given_mountedFiles() {
        // given（会话挂载两个文件资源，其中第二个无挂载路径）
        List<SessionResource> resources = List.of(
                SessionResource.file("file_1", "mounts/a.txt"),
                SessionResource.file("file_2", null));

        // when
        String json = mapper.sessionResourcesToString(resources);
        List<SessionResource> restored = mapper.stringToSessionResources(json);

        // then（VO ⇄ jsonb 文本无损往返；空/null 收敛为空列表）
        assertEquals(2, restored.size());
        // sesr_ 资源 ID 随 jsonb 落库并在反查后保持稳定（不漂移）
        assertEquals(resources.get(0).id(), restored.get(0).id());
        assertEquals(resources.get(1).id(), restored.get(1).id());
        assertEquals("file_1", restored.get(0).fileId());
        assertEquals("mounts/a.txt", restored.get(0).mountPath());
        assertEquals("file_2", restored.get(1).fileId());
        assertEquals(List.of(), mapper.stringToSessionResources(null));
        assertEquals(List.of(), mapper.stringToSessionResources(" "));
        assertEquals("[]", mapper.sessionResourcesToString(List.of()));
    }

    @Test
    void should_returnEmpty_when_stringToSessionResources_given_invalidJson() {
        // when & then（脏 JSON 收敛为空列表，不阻断查询）
        assertEquals(List.of(), mapper.stringToSessionResources("[not-json"));
    }

    // ===== ChatEvent（payload 信封） =====

    @Test
    void should_mapToEntityAndBack_when_saveAndRestore_given_chatEvent() {
        // given（权威工厂：sessionId + 源码事件类型 + payload + seq + eventId + 线程归属）
        ChatEvent event = ChatEvent.create("s-1", ChatEventType.AGENT_MESSAGE, "{\"text\":\"好\"}", 3L,
                null, "sthr_main");

        // when
        ChatEventEntity entity = mapper.toEntity(event);
        ChatEvent restored = mapper.toDomain(entity);

        // then（type 以源码事件名落库；seq 为会话内游标；事件 ID 保留 evt_ 前缀）
        assertEquals(event.eventId(), restored.eventId());
        assertTrue(restored.eventId().startsWith(ChatEvent.EVENT_ID_PREFIX));
        assertEquals(event.sessionId(), restored.sessionId());
        assertEquals("agent.message", entity.getType());
        assertEquals(ChatEventType.AGENT_MESSAGE, restored.type());
        assertEquals("{\"text\":\"好\"}", restored.payload());
        assertEquals(3L, restored.seq());
        assertEquals(event.processedAt(), restored.processedAt());
        // 线程归属双向映射（防同名字段静默丢失回归）
        assertEquals("sthr_main", entity.getSessionThreadId());
        assertEquals("sthr_main", restored.sessionThreadId());
    }

    @Test
    void should_mapNullThreadIdBothWays_when_saveAndRestore_given_noThreadOwnership() {
        // given（旧签名：无线程归属，往返均应保持 null）
        ChatEvent event = ChatEvent.create("s-1", ChatEventType.AGENT_MESSAGE, "{}", 4L);

        // when
        ChatEventEntity entity = mapper.toEntity(event);
        ChatEvent restored = mapper.toDomain(entity);

        // then
        assertNull(entity.getSessionThreadId());
        assertNull(restored.sessionThreadId());
    }

    // ===== 枚举 ⇄ 字符串（小写规范值 / 大小写不敏感反解） =====

    @Test
    void should_returnNullWhen_mapEnum_given_blankValue() {
        // when & then
        assertNull(mapper.sessionStatusToString(null));
        assertNull(mapper.stringToSessionStatus(null));
        assertNull(mapper.stringToSessionStatus(" "));
        assertNull(mapper.chatEventTypeToString(null));
        assertNull(mapper.stringToChatEventType(null));
        assertNull(mapper.stringToChatEventType(""));
    }

    @Test
    void should_resolveEnum_when_mapEnum_given_matchingValue() {
        // when & then
        assertEquals("idle", mapper.sessionStatusToString(AgentSessionStatus.IDLE));
        assertEquals("agent.message", mapper.chatEventTypeToString(ChatEventType.AGENT_MESSAGE));
        assertEquals(AgentSessionStatus.IDLE, mapper.stringToSessionStatus("IDLE"));
        assertEquals(ChatEventType.AGENT_MESSAGE, mapper.stringToChatEventType("agent.message"));
    }

    @Test
    void should_throw_when_stringToChatEventType_given_unknownLegacyValue() {
        // when & then（旧枚举名 / 未知类型是脏数据，直接抛参错而非兜底）
        assertThrows(IllegalArgumentException.class, () -> mapper.stringToChatEventType("RUN_START"));
    }
}