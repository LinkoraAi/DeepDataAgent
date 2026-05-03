package com.linkroa.deepdataagent.memory.extractor;

import com.linkroa.deepdataagent.memory.model.ConversationContext;
import com.linkroa.deepdataagent.memory.model.ConversationContext.ConversationMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(MockitoExtension.class)
class SimpleMemoryExtractorTest {

    @Test
    void should_extractEpisodicAndPreferenceMemories_when_extract_given_explicitUserPreference() {
        // given
        ConversationContext context = new ConversationContext(
                "session-test",
                Instant.parse("2026-04-21T00:00:00Z"),
                List.of(
                        new ConversationMessage("user", "user", "请记住：我偏好 Spring Boot YAML 配置，不要 XML。", null),
                        new ConversationMessage("assistant", "assistant", "已记录这个偏好。", null)
                )
        );

        // when
        var memories = new SimpleMemoryExtractor().extractAndClassify(context);

        // then
        assertTrue(memories.stream().anyMatch(memory -> "episodic".equals(memory.layer())));
        assertTrue(memories.stream().anyMatch(memory ->
                "semantic".equals(memory.layer()) && "preference".equals(memory.subCategory())));
    }

    @Test
    void should_extractSemanticMemories_when_extract_given_factAndDecisionSignals() {
        // given
        ConversationContext context = new ConversationContext(
                "session-facts",
                Instant.parse("2026-04-21T00:00:00Z"),
                List.of(
                        new ConversationMessage("user", "user", "项目使用 PostgreSQL 15，接口统一 REST。", null),
                        new ConversationMessage("user", "user", "决定采用 Redis 缓存热点查询，放弃 GraphQL。", null)
                )
        );

        // when
        var memories = new SimpleMemoryExtractor().extractAndClassify(context);

        // then
        assertTrue(memories.stream().anyMatch(memory ->
                "semantic".equals(memory.layer()) && "fact".equals(memory.subCategory())));
        assertTrue(memories.stream().anyMatch(memory ->
                "semantic".equals(memory.layer()) && "rule".equals(memory.subCategory())));
    }

    @Test
    void should_extractSkillMemory_when_extract_given_proceduralConversationWithSufficientAssistantContent() {
        // given
        String longProcedure = """
                第一步检查配置，第二步创建索引，第三步执行测试，第四步观察日志。
                如果出现失败，需要回滚变更并重新同步索引。最后记录新的执行经验，确保后续复用。
                这个流程适合重复性的部署和修复任务，可以沉淀为技能记忆。
                另外需要补充回滚策略、验收标准、日志采集和异常处理细节，保证步骤足够完整。
                """;
        ConversationContext context = new ConversationContext(
                "session-skill",
                Instant.parse("2026-04-21T00:00:00Z"),
                List.of(
                        new ConversationMessage("user", "user", "这个部署流程应该怎么做？", null),
                        new ConversationMessage("assistant", "assistant", longProcedure, null)
                )
        );

        // when
        var memories = new SimpleMemoryExtractor().extractAndClassify(context);

        // then
        assertTrue(memories.stream().anyMatch(memory ->
                "skills".equals(memory.layer()) && "skill".equals(memory.subCategory())));
    }

    @Test
    void should_notCreateSemanticOrSkillMemory_when_extract_given_conversationWithoutSignals() {
        // given
        ConversationContext context = new ConversationContext(
                "session-chat",
                Instant.parse("2026-04-21T00:00:00Z"),
                List.of(
                        new ConversationMessage("user", "user", "你好，今天状态怎么样？", null),
                        new ConversationMessage("assistant", "assistant", "我很好。", null)
                )
        );

        // when
        var memories = new SimpleMemoryExtractor().extractAndClassify(context);

        // then
        assertTrue(memories.stream().anyMatch(memory -> "episodic".equals(memory.layer())));
        assertFalse(memories.stream().anyMatch(memory -> "semantic".equals(memory.layer())));
        assertFalse(memories.stream().anyMatch(memory -> "skills".equals(memory.layer())));
    }
}
