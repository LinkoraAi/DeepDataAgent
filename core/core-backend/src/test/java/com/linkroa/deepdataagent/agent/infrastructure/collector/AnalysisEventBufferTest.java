package com.linkroa.deepdataagent.agent.infrastructure.collector;

import io.agentscope.core.event.AgentEvent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * {@link AnalysisEventBuffer} 的单元测试
 * <p>验证分析事件缓冲的累积、空值过滤与快照导出语义，供刷新恢复（resume）时回放使用。</p>
 */
class AnalysisEventBufferTest {

    @Test
    void should_accumulateEventsInOrder_when_add_given_validEvents() {
        // given
        AnalysisEventBuffer buffer = new AnalysisEventBuffer();
        AgentEvent first = mock(AgentEvent.class);
        AgentEvent second = mock(AgentEvent.class);

        // when
        buffer.add(first);
        buffer.add(second);

        // then
        assertEquals(2, buffer.size());
        assertEquals(List.of(first, second), buffer.snapshot());
    }

    @Test
    void should_ignoreNull_when_add_given_nullEvent() {
        // given
        AnalysisEventBuffer buffer = new AnalysisEventBuffer();

        // when
        buffer.add(null);

        // then
        assertEquals(0, buffer.size());
        assertTrue(buffer.snapshot().isEmpty());
    }

    @Test
    void should_returnEmptySnapshot_when_snapshot_given_emptyBuffer() {
        // given
        AnalysisEventBuffer buffer = new AnalysisEventBuffer();

        // when
        List<AgentEvent> snapshot = buffer.snapshot();

        // then
        assertNotNull(snapshot);
        assertTrue(snapshot.isEmpty());
    }

    @Test
    void should_returnIndependentCopy_when_snapshot_given_eventsAddedAfterSnapshot() {
        // given
        AnalysisEventBuffer buffer = new AnalysisEventBuffer();
        AgentEvent first = mock(AgentEvent.class);
        AgentEvent later = mock(AgentEvent.class);
        buffer.add(first);

        // 导出快照后再追加事件，快照不应受影响（不可变语义）
        List<AgentEvent> snapshot = buffer.snapshot();
        buffer.add(later);

        // then：快照保持导出时的内容，不含后续追加事件，且可安全修改返回列表
        assertEquals(1, snapshot.size());
        assertEquals(first, snapshot.get(0));
        snapshot.add(mock(AgentEvent.class));
        assertEquals(2, buffer.size());
    }
}