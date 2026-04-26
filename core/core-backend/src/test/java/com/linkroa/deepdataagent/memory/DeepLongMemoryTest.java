package com.linkroa.deepdataagent.memory;

import com.linkroa.deepdataagent.memory.config.MemoryProperties;
import com.linkroa.deepdataagent.memory.extractor.SimpleMemoryExtractor;
import com.linkroa.deepdataagent.memory.file.MarkdownFileManager;
import com.linkroa.deepdataagent.memory.index.MemoryIndexManager;
import com.linkroa.deepdataagent.memory.model.*;
import com.linkroa.deepdataagent.memory.retrieval.HybridRetriever;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DeepLongMemoryTest {

    @Mock
    private SimpleMemoryExtractor memoryExtractor;

    @Mock
    private MarkdownFileManager fileManager;

    @Mock
    private MemoryIndexManager indexManager;

    @Mock
    private HybridRetriever retriever;

    private MemoryProperties properties;

    @InjectMocks
    private DeepLongMemory memory;

    @BeforeEach
    void setUp() {
        properties = new MemoryProperties();
        memory = new DeepLongMemory(memoryExtractor, fileManager, indexManager, retriever, properties);
    }

    @Test
    void should_syncExtractedMemories_when_record_given_validConversationAndQuery() {
        // given
        ExtractedMemory extractedMemory = new ExtractedMemory(
                "mem-pref",
                "semantic",
                "preference",
                "YAML preference",
                "Prefer Spring Boot YAML configuration.",
                0.9,
                Instant.parse("2026-04-21T00:00:00Z"),
                "session-main"
        );
        when(memoryExtractor.extractAndClassify(any(ConversationContext.class))).thenReturn(List.of(extractedMemory));
        when(fileManager.writeExtractedMemories(any(ConversationContext.class), any())).thenReturn(Set.of("USER.md"));

        // when
        memory.record(List.of(
                user("session-main", "请记住：我偏好 Spring Boot YAML 配置，不要 XML。"),
                assistant("session-main", "已记录，后续会优先使用 YAML 配置。")
        )).block();

        // then
        ArgumentCaptor<ConversationContext> contextCaptor = ArgumentCaptor.forClass(ConversationContext.class);
        verify(memoryExtractor).extractAndClassify(contextCaptor.capture());
        ConversationContext context = contextCaptor.getValue();
        assertEquals("session-main", context.sessionId());
        assertEquals("user", context.userName());
        assertEquals(2, context.messages().size());

        verify(fileManager).writeExtractedMemories(contextCaptor.capture(), any());
        assertEquals("session-main", contextCaptor.getValue().sessionId());
        verify(fileManager).flushDirtyFiles();
        verify(indexManager).syncIncremental(Set.of("USER.md"));
    }

    @Test
    void should_skipRecord_when_record_given_messageCountBelowMinimum() {
        // given
        properties.getRecord().setMinRoundSize(3);

        // when
        memory.record(List.of(user("session-skip", "请记住：我偏好 Redis。"))).block();

        // then
        verify(memoryExtractor, never()).extractAndClassify(any());
        verify(fileManager, never()).writeExtractedMemories(any(), any());
        verify(indexManager, never()).syncIncremental(any());
    }

    @Test
    void should_notPersistFullConversation_when_record_given_episodicCaptureDisabled() {
        // given
        properties.getRecord().setCaptureFullConversation(false);
        ExtractedMemory episodic = new ExtractedMemory(
                "mem-episodic",
                "episodic",
                "event",
                "session event",
                "Conversation transcript",
                0.4,
                Instant.parse("2026-04-21T00:00:00Z"),
                "session-light"
        );
        ExtractedMemory semantic = new ExtractedMemory(
                "mem-pref",
                "semantic",
                "preference",
                "MyBatis-Plus preference",
                "Prefer MyBatis-Plus over JPA.",
                0.8,
                Instant.parse("2026-04-21T00:00:00Z"),
                "session-light"
        );
        when(memoryExtractor.extractAndClassify(any(ConversationContext.class))).thenReturn(List.of(episodic, semantic));
        when(fileManager.writeExtractedMemories(any(ConversationContext.class), any())).thenReturn(Set.of("USER.md"));

        // when
        memory.record(List.of(
                user("session-light", "请记住：我偏好 MyBatis-Plus，不使用 JPA。"),
                assistant("session-light", "已记录。")
        )).block();

        // then
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ExtractedMemory>> memoriesCaptor = ArgumentCaptor.forClass(List.class);
        verify(fileManager).writeExtractedMemories(any(ConversationContext.class), memoriesCaptor.capture());
        List<ExtractedMemory> persisted = memoriesCaptor.getValue();

        assertEquals(1, persisted.size());
        assertEquals("semantic", persisted.getFirst().layer());
        assertFalse(persisted.stream().anyMatch(item -> "episodic".equals(item.layer())));
    }

    @Test
    void should_returnEmptyOrFormattedResults_when_retrieve_given_blankOrMatchedQuery() {
        // given
        MemoryChunk chunk = new MemoryChunk(
                "chunk-1",
                "mem-pref",
                "USER.md",
                "semantic",
                "preference",
                10,
                14,
                "ignored cached chunk",
                0.8,
                Instant.parse("2026-04-21T00:00:00Z"),
                2,
                1L
        );
        MemorySearchResult result = MemorySearchResult.fromChunk(chunk, 0.7, 0.8);
        when(retriever.hybridSearch(any(), any(RetrieveOptions.class))).thenReturn(List.of(result));
        when(fileManager.readLines("USER.md", 10, 14)).thenReturn("Prefer Spring Boot YAML configuration.");

        // when
        String nullResult = memory.retrieve(null).block();
        String blankResult = memory.retrieve(user("session-empty", "   ")).block();
        String retrieved = memory.retrieve(user("session-main", "YAML 配置")).block();

        // then
        assertEquals("", nullResult);
        assertEquals("", blankResult);
        assertNotNull(retrieved);
        assertTrue(retrieved.contains("## 相关记忆"));
        assertTrue(retrieved.contains("mem-pref"));
        assertTrue(retrieved.contains("Prefer Spring Boot YAML configuration."));
        verify(retriever).hybridSearch(any(), any(RetrieveOptions.class));
        verify(fileManager).readLines("USER.md", 10, 14);
    }

    private static Msg user(String sessionId, String content) {
        return Msg.builder()
                .name("user")
                .role(MsgRole.USER)
                .metadata(Map.of("sessionId", sessionId))
                .timestamp("2026-04-21T00:00:00Z")
                .textContent(content)
                .build();
    }

    private static Msg assistant(String sessionId, String content) {
        return Msg.builder()
                .name("assistant")
                .role(MsgRole.ASSISTANT)
                .metadata(Map.of("sessionId", sessionId))
                .timestamp("2026-04-21T00:00:01Z")
                .textContent(content)
                .build();
    }
}
