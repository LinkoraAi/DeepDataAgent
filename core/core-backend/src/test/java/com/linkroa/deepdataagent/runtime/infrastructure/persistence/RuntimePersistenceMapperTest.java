package com.linkroa.deepdataagent.runtime.infrastructure.persistence;

import com.linkroa.deepdataagent.runtime.domain.model.AgentSession;
import com.linkroa.deepdataagent.runtime.domain.model.ChatEvent;
import com.linkroa.deepdataagent.runtime.domain.model.ExecutionRound;
import com.linkroa.deepdataagent.runtime.domain.model.RunTrace;
import com.linkroa.deepdataagent.runtime.domain.model.enums.AgentSessionStatus;
import com.linkroa.deepdataagent.runtime.domain.model.enums.ChatEventType;
import com.linkroa.deepdataagent.runtime.domain.model.enums.RoundStatus;
import com.linkroa.deepdataagent.runtime.domain.model.enums.SpanKind;
import com.linkroa.deepdataagent.runtime.domain.model.enums.SpanStatus;
import com.linkroa.deepdataagent.runtime.infrastructure.persistence.entity.AgentSessionEntity;
import com.linkroa.deepdataagent.runtime.infrastructure.persistence.entity.ChatEventEntity;
import com.linkroa.deepdataagent.runtime.infrastructure.persistence.entity.ExecutionRoundEntity;
import com.linkroa.deepdataagent.runtime.infrastructure.persistence.entity.RunTraceEntity;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link RuntimePersistenceMapper} 领域 ⇄ 实体映射单测（MapStruct 生成实现类）。
 */
class RuntimePersistenceMapperTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final RuntimePersistenceMapper mapper = new RuntimePersistenceMapperImpl();

    // ===== AgentSession =====

    @Test
    void should_mapToEntityAndBack_when_saveAndRestore_given_agentSession() {
        // given
        AgentSession session = AgentSession.create("u-1", "agent-a", "1.0.0", "{\"eu\":\"e1\"}", "会话标题");
        AgentSession running = session.withStatus(AgentSessionStatus.TERMINATED);

        // when
        AgentSessionEntity entity = mapper.toEntity(running);
        AgentSession restored = mapper.toDomain(entity);

        // then
        assertEquals(running.sessionId(), restored.sessionId());
        assertEquals(running.userId(), restored.userId());
        assertEquals(running.agentId(), restored.agentId());
        assertEquals(running.agentVersion(), restored.agentVersion());
        assertEquals(AgentSessionStatus.TERMINATED, restored.status());
        assertEquals("{\"eu\":\"e1\"}", restored.metadata());
        assertEquals(running.title(), restored.title());
    }

    // ===== ExecutionRound =====

    @Test
    void should_mapToEntityAndBack_when_saveAndRestore_given_executionRound() {
        // given
        ExecutionRound round = ExecutionRound.create("s-1", "run-1", 1, "你好");
        ExecutionRound completed = round.complete("最终输出", RoundStatus.COMPLETED);

        // when
        ExecutionRoundEntity entity = mapper.toEntity(completed);
        ExecutionRound restored = mapper.toDomain(entity);

        // then
        assertEquals(completed.roundId(), restored.roundId());
        assertEquals(completed.sessionId(), restored.sessionId());
        assertEquals(completed.runId(), restored.runId());
        assertEquals(1, restored.roundNumber());
        assertEquals("最终输出", restored.output());
        assertEquals(RoundStatus.COMPLETED, restored.status());
    }

    // ===== ChatEvent =====

    @Test
    void should_mapToEntityAndBack_when_saveAndRestore_given_chatEvent() {
        // given
        ChatEvent event = ChatEvent.create("s-1", "r-1", ChatEventType.MESSAGE, "{\"delta\":\"好\"}", 3L);

        // when
        ChatEventEntity entity = mapper.toEntity(event);
        ChatEvent restored = mapper.toDomain(entity);

        // then
        assertEquals(event.eventId(), restored.eventId());
        assertEquals(event.sessionId(), restored.sessionId());
        assertEquals("r-1", restored.roundId());
        assertEquals(ChatEventType.MESSAGE, restored.eventType());
        assertEquals("{\"delta\":\"好\"}", restored.payload());
        assertEquals(3L, restored.sequenceNum());
    }

    // ===== RunTrace =====

    @Test
    void should_mapToEntityAndBack_when_saveAndRestore_given_runTrace() {
        // given
        RunTrace root = RunTrace.createRoot("trace-1", "r-1", "agent.run");
        RunTrace finished = root.finish(root.startTime().plusSeconds(3));

        // when
        RunTraceEntity entity = mapper.toEntity(finished);
        RunTrace restored = mapper.toDomain(entity);

        // then
        assertEquals(finished.traceId(), restored.traceId());
        assertEquals(finished.spanId(), restored.spanId());
        assertEquals(finished.roundId(), restored.roundId());
        assertEquals("agent.run", restored.spanName());
        assertEquals(SpanKind.INTERNAL, restored.spanKind());
        assertEquals(SpanStatus.OK, restored.status());
        assertEquals(3000L, restored.durationMs());
    }

    // ===== 枚举反解（default 方法兜底） =====

    @Test
    void should_returnNullEnum_when_mapEnum_given_blankValue() {
        // when & then
        assertNull(mapper.mapSessionStatus(null));
        assertNull(mapper.mapSessionStatus(" "));
        assertNull(mapper.mapRoundStatus(null));
        assertNull(mapper.mapChatEventType(null));
        assertNull(mapper.mapSpanKind(null));
        assertNull(mapper.mapSpanStatus(null));
    }

    @Test
    void should_resolveEnum_when_mapEnum_given_matchingName() {
        // when & then
        assertEquals(AgentSessionStatus.IDLE, mapper.mapSessionStatus("IDLE"));
        assertEquals(RoundStatus.RUNNING, mapper.mapRoundStatus("RUNNING"));
        assertEquals(ChatEventType.TOOL_CALL, mapper.mapChatEventType("TOOL_CALL"));
        assertEquals(SpanKind.CLIENT, mapper.mapSpanKind("CLIENT"));
        assertEquals(SpanStatus.ERROR, mapper.mapSpanStatus("ERROR"));
    }

    // ===== 兜底：实体扩展字段透传 =====

    @Test
    void should_preserveCostAndTokens_when_createChildAndRestore_given_fullSpan() {
        // given
        OffsetDateTime start = OffsetDateTime.now(ZONE);
        RunTrace child = RunTrace.createChild("trace-1", "root", "r-1", "llm.call", null, start);
        RunTrace withMeta = RunTrace.restore(
                null, child.traceId(), child.spanId(), child.parentSpanId(), child.roundId(), child.spanName(),
                child.spanKind(), child.status(), child.startTime(), child.endTime(), child.durationMs(),
                100, 50, "qwen-plus", new BigDecimal("0.012"),
                child.toolName(), child.toolInput(), child.toolOutput(), child.attributes(),
                child.createdAt(), child.updatedAt(), child.createdBy(), child.updatedBy());

        // when
        RunTrace restored = mapper.toDomain(mapper.toEntity(withMeta));

        // then
        assertEquals(100, restored.inputTokens());
        assertEquals(50, restored.outputTokens());
        assertEquals("qwen-plus", restored.modelName());
        assertEquals(new BigDecimal("0.012"), restored.estimatedCost());
    }
}