package com.linkroa.deepdataagent.runtime.domain.model;

import com.linkroa.deepdataagent.runtime.domain.model.enums.SpanKind;
import com.linkroa.deepdataagent.runtime.domain.model.enums.SpanStatus;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link RunTrace} 领域模型不变量单测。
 */
class RunTraceTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    @Test
    void should_createRootSpan_when_createRoot_given_validInputs() {
        // given
        String traceId = "trace-1";
        String roundId = "r-1";

        // when
        RunTrace root = RunTrace.createRoot(traceId, roundId, "agent.run");

        // then
        assertEquals(traceId, root.traceId());
        assertNotNull(root.spanId());
        assertNull(root.parentSpanId());
        assertEquals(roundId, root.roundId());
        assertEquals("agent.run", root.spanName());
        assertEquals(SpanKind.INTERNAL, root.spanKind());
        assertEquals(SpanStatus.OK, root.status());
        assertNull(root.endTime());
    }

    @Test
    void should_createChildSpan_when_createChild_given_toolCall() {
        // given
        OffsetDateTime start = OffsetDateTime.now(ZONE);

        // when
        RunTrace child = RunTrace.createChild("trace-1", "root-span", "r-1", "tool.call", "query_datasource", start);

        // then
        assertEquals("root-span", child.parentSpanId());
        assertEquals(SpanKind.CLIENT, child.spanKind());
        assertEquals("tool.call", child.spanName());
        assertEquals("query_datasource", child.toolName());
        assertEquals(start, child.startTime());
    }

    @Test
    void should_throw_when_construct_given_blankTraceId() {
        // given
        RunTrace factory = RunTrace.createRoot("trace-1", "r-1", "agent.run");

        // when & then
        assertThrows(IllegalArgumentException.class,
                () -> new RunTrace(factory.id(), "", factory.spanId(), factory.parentSpanId(),
                        factory.roundId(), factory.spanName(), factory.spanKind(), factory.status(),
                        factory.startTime(), factory.endTime(), factory.durationMs(),
                        factory.inputTokens(), factory.outputTokens(), factory.modelName(), factory.estimatedCost(),
                        factory.toolName(), factory.toolInput(), factory.toolOutput(), factory.attributes(),
                        factory.createdAt(), factory.updatedAt(), factory.createdBy(), factory.updatedBy()));
    }

    @Test
    void should_throw_when_construct_given_blankSpanId() {
        // given
        RunTrace factory = RunTrace.createRoot("trace-1", "r-1", "agent.run");

        // when & then
        assertThrows(IllegalArgumentException.class,
                () -> new RunTrace(factory.id(), factory.traceId(), "", factory.parentSpanId(),
                        factory.roundId(), factory.spanName(), factory.spanKind(), factory.status(),
                        factory.startTime(), factory.endTime(), factory.durationMs(),
                        factory.inputTokens(), factory.outputTokens(), factory.modelName(), factory.estimatedCost(),
                        factory.toolName(), factory.toolInput(), factory.toolOutput(), factory.attributes(),
                        factory.createdAt(), factory.updatedAt(), factory.createdBy(), factory.updatedBy()));
    }

    @Test
    void should_throw_when_construct_given_blankRoundId() {
        // given
        RunTrace factory = RunTrace.createRoot("trace-1", "r-1", "agent.run");

        // when & then
        assertThrows(IllegalArgumentException.class,
                () -> new RunTrace(factory.id(), factory.traceId(), factory.spanId(), factory.parentSpanId(),
                        " ", factory.spanName(), factory.spanKind(), factory.status(),
                        factory.startTime(), factory.endTime(), factory.durationMs(),
                        factory.inputTokens(), factory.outputTokens(), factory.modelName(), factory.estimatedCost(),
                        factory.toolName(), factory.toolInput(), factory.toolOutput(), factory.attributes(),
                        factory.createdAt(), factory.updatedAt(), factory.createdBy(), factory.updatedBy()));
    }

    @Test
    void should_throw_when_construct_given_blankSpanName() {
        // given
        RunTrace factory = RunTrace.createRoot("trace-1", "r-1", "agent.run");

        // when & then
        assertThrows(IllegalArgumentException.class,
                () -> new RunTrace(factory.id(), factory.traceId(), factory.spanId(), factory.parentSpanId(),
                        factory.roundId(), "", factory.spanKind(), factory.status(),
                        factory.startTime(), factory.endTime(), factory.durationMs(),
                        factory.inputTokens(), factory.outputTokens(), factory.modelName(), factory.estimatedCost(),
                        factory.toolName(), factory.toolInput(), factory.toolOutput(), factory.attributes(),
                        factory.createdAt(), factory.updatedAt(), factory.createdBy(), factory.updatedBy()));
    }

    @Test
    void should_throw_when_construct_given_nullKindOrStatus() {
        // given
        RunTrace factory = RunTrace.createRoot("trace-1", "r-1", "agent.run");

        // when & then
        assertThrows(IllegalArgumentException.class,
                () -> new RunTrace(factory.id(), factory.traceId(), factory.spanId(), factory.parentSpanId(),
                        factory.roundId(), factory.spanName(), null, factory.status(),
                        factory.startTime(), factory.endTime(), factory.durationMs(),
                        factory.inputTokens(), factory.outputTokens(), factory.modelName(), factory.estimatedCost(),
                        factory.toolName(), factory.toolInput(), factory.toolOutput(), factory.attributes(),
                        factory.createdAt(), factory.updatedAt(), factory.createdBy(), factory.updatedBy()));
        assertThrows(IllegalArgumentException.class,
                () -> new RunTrace(factory.id(), factory.traceId(), factory.spanId(), factory.parentSpanId(),
                        factory.roundId(), factory.spanName(), factory.spanKind(), null,
                        factory.startTime(), factory.endTime(), factory.durationMs(),
                        factory.inputTokens(), factory.outputTokens(), factory.modelName(), factory.estimatedCost(),
                        factory.toolName(), factory.toolInput(), factory.toolOutput(), factory.attributes(),
                        factory.createdAt(), factory.updatedAt(), factory.createdBy(), factory.updatedBy()));
    }

    @Test
    void should_throw_when_construct_given_nullStartTime() {
        // given
        RunTrace factory = RunTrace.createRoot("trace-1", "r-1", "agent.run");

        // when & then
        assertThrows(IllegalArgumentException.class,
                () -> new RunTrace(factory.id(), factory.traceId(), factory.spanId(), factory.parentSpanId(),
                        factory.roundId(), factory.spanName(), factory.spanKind(), factory.status(),
                        null, factory.endTime(), factory.durationMs(),
                        factory.inputTokens(), factory.outputTokens(), factory.modelName(), factory.estimatedCost(),
                        factory.toolName(), factory.toolInput(), factory.toolOutput(), factory.attributes(),
                        factory.createdAt(), factory.updatedAt(), factory.createdBy(), factory.updatedBy()));
    }

    @Test
    void should_throw_when_construct_given_negativeDuration() {
        // given
        RunTrace factory = RunTrace.createRoot("trace-1", "r-1", "agent.run");

        // when & then
        assertThrows(IllegalArgumentException.class,
                () -> new RunTrace(factory.id(), factory.traceId(), factory.spanId(), factory.parentSpanId(),
                        factory.roundId(), factory.spanName(), factory.spanKind(), factory.status(),
                        factory.startTime(), factory.endTime(), -1L,
                        factory.inputTokens(), factory.outputTokens(), factory.modelName(), factory.estimatedCost(),
                        factory.toolName(), factory.toolInput(), factory.toolOutput(), factory.attributes(),
                        factory.createdAt(), factory.updatedAt(), factory.createdBy(), factory.updatedBy()));
    }

    @Test
    void should_finishSpan_when_finish_given_endTime() {
        // given
        RunTrace root = RunTrace.createRoot("trace-1", "r-1", "agent.run");

        // when
        RunTrace finished = root.finish(root.startTime().plusSeconds(5));

        // then
        assertNotNull(finished.endTime());
        assertEquals(5000L, finished.durationMs());
        assertNotNull(finished.updatedAt());
    }

    @Test
    void should_useNow_when_finish_given_nullEndTime() {
        // given
        RunTrace root = RunTrace.createRoot("trace-1", "r-1", "agent.run");

        // when
        RunTrace finished = root.finish(null);

        // then
        assertNotNull(finished.endTime());
        assertEquals(0L, finished.durationMs());
    }

    @Test
    void should_restoreTrace_when_restore_given_fullFields() {
        // given
        RunTrace origin = RunTrace.createRoot("trace-1", "r-1", "agent.run");
        RunTrace finished = origin.finish(origin.startTime().plusSeconds(2));

        // when
        RunTrace restored = RunTrace.restore(
                7L, finished.traceId(), finished.spanId(), finished.parentSpanId(), finished.roundId(),
                finished.spanName(), finished.spanKind(), finished.status(),
                finished.startTime(), finished.endTime(), finished.durationMs(),
                finished.inputTokens(), finished.outputTokens(), finished.modelName(), finished.estimatedCost(),
                finished.toolName(), finished.toolInput(), finished.toolOutput(), finished.attributes(),
                finished.createdAt(), finished.updatedAt(), finished.createdBy(), finished.updatedBy());

        // then
        assertEquals(7L, restored.id());
        assertEquals(finished.durationMs(), restored.durationMs());
        assertEquals(SpanKind.INTERNAL, restored.spanKind());
        assertEquals(SpanStatus.OK, restored.status());
    }
}