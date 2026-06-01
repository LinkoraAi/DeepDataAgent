package com.linkroa.deepdataagent.memory.model;

import com.linkroa.deepdataagent.memory.model.ConversationContext.ConversationMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(MockitoExtension.class)
class ConversationContextTest {

    @Test
    void should_buildTranscriptAndFindLatestRoleMessages_when_fromMessages_given_mixedConversation() {
        // given
        ConversationContext context = new ConversationContext(
                "session-model",
                Instant.parse("2026-04-21T00:00:00Z"),
                List.of(
                        new ConversationMessage(null, "system", "   ", null),
                        new ConversationMessage("alice", "user", "第一条问题", null),
                        new ConversationMessage(null, "assistant", "第一条回答", null),
                        new ConversationMessage(null, "user", " 最近问题", null),
                        new ConversationMessage(null, "assistant", " 最近回答", null)
                )
        );

        // when
        String transcript = context.transcript();
        String latestUserText = context.latestUserText();
        String latestAssistantText = context.latestAssistantText();

        // then
        assertTrue(transcript.contains("**alice**: 第一条问题"));
        assertTrue(transcript.contains("**assistant**: 最近回答"));
        assertEquals("最近问题", latestUserText);
        assertEquals("最近回答", latestAssistantText);
    }

    @Test
    void should_returnEmptyLatestMessages_when_findLatestMessagesByRole_given_missingRole() {
        // given
        ConversationContext context = new ConversationContext(
                "session-empty",
                Instant.now(),
                List.of(new ConversationMessage(null, null, "hello", null))
        );

        // when
        String transcript = context.transcript();
        String latestUserText = context.latestUserText();
        String latestAssistantText = context.latestAssistantText();

        // then
        assertTrue(transcript.contains("**unknown**: hello"));
        assertEquals("", latestUserText);
        assertEquals("", latestAssistantText);
    }
}
