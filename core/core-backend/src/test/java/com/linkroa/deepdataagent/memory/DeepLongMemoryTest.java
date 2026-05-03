package com.linkroa.deepdataagent.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.linkroa.deepdataagent.memory.config.MemoryProperties;
import com.linkroa.deepdataagent.memory.extractor.MemoryExtractor;
import com.linkroa.deepdataagent.memory.file.MarkdownFileManager;
import com.linkroa.deepdataagent.memory.index.MemoryIndexManager;
import com.linkroa.deepdataagent.memory.model.ConversationContext;
import com.linkroa.deepdataagent.memory.model.ExtractedMemory;
import com.linkroa.deepdataagent.memory.model.MemoryChunk;
import com.linkroa.deepdataagent.memory.model.MemorySearchResult;
import com.linkroa.deepdataagent.memory.model.MemorySessionContext;
import com.linkroa.deepdataagent.memory.model.RetrieveOptions;
import com.linkroa.deepdataagent.memory.retrieval.HybridRetriever;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;

@ExtendWith(MockitoExtension.class)
class DeepLongMemoryTest {

    @Mock
    private MemoryExtractor memoryExtractor;

    @Mock
    private MarkdownFileManager fileManager;

    @Mock
    private MemoryIndexManager indexManager;

    @Mock
    private HybridRetriever retriever;

    private MemoryProperties properties;
    private MemorySessionContext sessionContext;
    private DeepLongMemory memory;

    @BeforeEach
    void setUp() {
        properties = new MemoryProperties();
        sessionContext = new MemorySessionContext(
                "session-main",
                Instant.parse("2026-04-21T00:00:00Z")
        );
        memory = new DeepLongMemory(
                memoryExtractor,
                fileManager,
                indexManager,
                retriever,
                properties,
                sessionContext
        );
    }

    @Test
    void should_syncExtractedMemories_when_record_given_validConversationAndQuery() {
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

        memory.record(List.of(
                user("msg-session", "Remember that I prefer Spring Boot YAML configuration."),
                assistant("msg-session", "Got it, I will prefer YAML configuration later.")
        )).block();

        ArgumentCaptor<ConversationContext> contextCaptor = ArgumentCaptor.forClass(ConversationContext.class);
        verify(memoryExtractor).extractAndClassify(contextCaptor.capture());
        ConversationContext context = contextCaptor.getValue();
        assertEquals("session-main", context.sessionId());
        assertEquals(2, context.messages().size());

        verify(fileManager).writeExtractedMemories(contextCaptor.capture(), any());
        assertEquals("session-main", contextCaptor.getValue().sessionId());
        verify(fileManager).flushDirtyFiles();
        verify(indexManager).syncIncremental(Set.of("USER.md"));
    }

    @Test
    void should_skipRecord_when_record_given_messageCountBelowMinimum() {
        properties.getRecord().setMinRoundSize(3);

        memory.record(List.of(user("msg-session", "Remember Redis."))).block();

        verify(memoryExtractor, never()).extractAndClassify(any());
        verify(fileManager, never()).writeExtractedMemories(any(), any());
        verify(indexManager, never()).syncIncremental(any());
    }

    @Test
    void should_notPersistFullConversation_when_record_given_episodicCaptureDisabled() {
        properties.getRecord().setCaptureFullConversation(false);
        ExtractedMemory episodic = new ExtractedMemory(
                "mem-episodic",
                "episodic",
                "event",
                "session event",
                "Conversation transcript",
                0.4,
                Instant.parse("2026-04-21T00:00:00Z"),
                "session-main"
        );
        ExtractedMemory semantic = new ExtractedMemory(
                "mem-pref",
                "semantic",
                "preference",
                "MyBatis-Plus preference",
                "Prefer MyBatis-Plus over JPA.",
                0.8,
                Instant.parse("2026-04-21T00:00:00Z"),
                "session-main"
        );
        when(memoryExtractor.extractAndClassify(any(ConversationContext.class))).thenReturn(List.of(episodic, semantic));
        when(fileManager.writeExtractedMemories(any(ConversationContext.class), any())).thenReturn(Set.of("USER.md"));

        memory.record(List.of(
                user("msg-session", "Remember that I prefer MyBatis-Plus instead of JPA."),
                assistant("msg-session", "Recorded.")
        )).block();

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

        String nullResult = memory.retrieve(null).block();
        String blankResult = memory.retrieve(user("msg-session", "   ")).block();
        String retrieved = memory.retrieve(user("msg-session", "YAML configuration")).block();

        assertEquals("", nullResult);
        assertEquals("", blankResult);
        assertNotNull(retrieved);
        assertTrue(retrieved.startsWith("##"));
        assertTrue(retrieved.contains("mem-pref"));
        assertTrue(retrieved.contains("Prefer Spring Boot YAML configuration."));
        verify(retriever).hybridSearch(any(), any(RetrieveOptions.class));
        verify(fileManager).readLines("USER.md", 10, 14);
    }

    @Test
    void should_useBoundSessionId_when_record_given_conflictingMessageMetadata() {
        ExtractedMemory extractedMemory = new ExtractedMemory(
                "mem-test",
                "semantic",
                "fact",
                "test fact",
                "Test memory content",
                0.9,
                Instant.parse("2026-04-21T00:00:00Z"),
                "session-main"
        );
        when(memoryExtractor.extractAndClassify(any(ConversationContext.class))).thenReturn(List.of(extractedMemory));
        when(fileManager.writeExtractedMemories(any(ConversationContext.class), any())).thenReturn(Set.of("USER.md"));

        memory.record(List.of(
                user("session-from-message", "Test message"),
                assistant("session-from-message", "Reply")
        )).block();

        ArgumentCaptor<ConversationContext> contextCaptor = ArgumentCaptor.forClass(ConversationContext.class);
        verify(memoryExtractor).extractAndClassify(contextCaptor.capture());
        assertEquals("session-main", contextCaptor.getValue().sessionId());
    }

    @Test
    void should_parseAgentScopeDefaultTimestampFormat_when_record_given_frameworkFormattedTimestamp() {
        MemorySessionContext laterSessionContext = new MemorySessionContext(
                "session-main",
                Instant.parse("2026-04-21T01:00:00Z")
        );
        DeepLongMemory laterBoundMemory = new DeepLongMemory(
                memoryExtractor,
                fileManager,
                indexManager,
                retriever,
                properties,
                laterSessionContext
        );
        ExtractedMemory extractedMemory = new ExtractedMemory(
                "mem-test",
                "semantic",
                "fact",
                "test fact",
                "Test memory content",
                0.9,
                Instant.parse("2026-04-21T00:00:00Z"),
                "session-main"
        );
        when(memoryExtractor.extractAndClassify(any(ConversationContext.class))).thenReturn(List.of(extractedMemory));
        when(fileManager.writeExtractedMemories(any(ConversationContext.class), any())).thenReturn(Set.of("USER.md"));

        Msg userMsg = Msg.builder()
                .name("user")
                .role(MsgRole.USER)
                .metadata(Map.of("sessionId", "session-from-message"))
                .timestamp("2026-04-21 08:15:30.123")
                .textContent("Test message")
                .build();
        Msg assistantMsg = Msg.builder()
                .name("assistant")
                .role(MsgRole.ASSISTANT)
                .metadata(Map.of("sessionId", "session-from-message"))
                .timestamp("2026-04-21 08:15:31.456")
                .textContent("Reply")
                .build();

        laterBoundMemory.record(List.of(userMsg, assistantMsg)).block();

        ArgumentCaptor<ConversationContext> contextCaptor = ArgumentCaptor.forClass(ConversationContext.class);
        verify(memoryExtractor).extractAndClassify(contextCaptor.capture());
        Instant expected = LocalDateTime.of(2026, 4, 21, 8, 15, 30, 123_000_000)
                .atZone(ZoneId.systemDefault())
                .toInstant();
        assertEquals(expected, contextCaptor.getValue().createdAt());
    }

    private static Msg user(String metadataSessionId, String content) {
        return Msg.builder()
                .name("user")
                .role(MsgRole.USER)
                .metadata(Map.of("sessionId", metadataSessionId))
                .timestamp("2026-04-21T00:00:00Z")
                .textContent(content)
                .build();
    }

    private static Msg assistant(String metadataSessionId, String content) {
        return Msg.builder()
                .name("assistant")
                .role(MsgRole.ASSISTANT)
                .metadata(Map.of("sessionId", metadataSessionId))
                .timestamp("2026-04-21T00:00:01Z")
                .textContent(content)
                .build();
    }
}
