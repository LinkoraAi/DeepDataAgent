package com.linkroa.deepdataagent.memory;

import com.linkroa.deepdataagent.memory.config.MemoryProperties;
import com.linkroa.deepdataagent.memory.extractor.SimpleMemoryExtractor;
import com.linkroa.deepdataagent.memory.file.MarkdownFileManager;
import com.linkroa.deepdataagent.memory.index.MemoryIndexManager;
import com.linkroa.deepdataagent.memory.model.ConversationContext;
import com.linkroa.deepdataagent.memory.model.ConversationContext.ConversationMessage;
import com.linkroa.deepdataagent.memory.model.ExtractedMemory;
import com.linkroa.deepdataagent.memory.model.MemorySearchResult;
import com.linkroa.deepdataagent.memory.model.RetrieveOptions;
import com.linkroa.deepdataagent.memory.retrieval.HybridRetriever;
import io.agentscope.core.memory.LongTermMemory;
import io.agentscope.core.message.Msg;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.*;

/**
 * AgentScope LongTermMemory 的主入口。
 *
 * <p>负责把框架传入的对话消息写入长期记忆，并在推理前按查询召回相关记忆。
 * 该类只编排流程：提取、Markdown 真相源写入、索引同步、检索和源文件回读分别委托给子组件完成。</p>
 */
@Component
public class DeepLongMemory implements LongTermMemory {

    private final SimpleMemoryExtractor memoryExtractor;
    private final MarkdownFileManager fileManager;
    private final MemoryIndexManager indexManager;
    private final HybridRetriever retriever;
    private final MemoryProperties properties;

    public DeepLongMemory(
            SimpleMemoryExtractor memoryExtractor,
            MarkdownFileManager fileManager,
            MemoryIndexManager indexManager,
            HybridRetriever retriever,
            MemoryProperties properties
    ) {
        this.memoryExtractor = memoryExtractor;
        this.fileManager = fileManager;
        this.indexManager = indexManager;
        this.retriever = retriever;
        this.properties = properties;
    }

    @Override
    public Mono<Void> record(List<Msg> msgs) {
        return Mono.fromRunnable(() -> {
            if (msgs == null || msgs.size() < properties.getRecord().getMinRoundSize()) {
                return;
            }

            ConversationContext context = buildConversationContext(msgs);
            List<ExtractedMemory> memories = memoryExtractor.extractAndClassify(context);
            // episodic 会保存完整会话片段，量最大；该开关用于需要更轻量记录时只保留沉淀后的长期事实/技能。
            if (!properties.getRecord().isCaptureFullConversation()) {
                memories = memories.stream()
                        .filter(memory -> !"episodic".equals(memory.layer()))
                        .toList();
            }
            if (memories.isEmpty()) {
                return;
            }

            // 先写 Markdown 真相源，再同步可重建索引；索引失败不会改变“已经记住”的事实来源。
            Set<String> changedPaths = fileManager.writeExtractedMemories(context, memories);
            if (properties.getIo().getCache().isFlushOnRecord()) {
                fileManager.flushDirtyFiles();
            }
            indexManager.syncIncremental(changedPaths);
        });
    }

    @Override
    public Mono<String> retrieve(Msg msg) {
        return Mono.fromSupplier(() -> {
            String query = msg == null ? "" : msg.getTextContent();
            if (query == null || query.isBlank()) {
                return "";
            }

            // 每次 retrieve 都从配置生成选项，方便运行期通过环境变量调整召回规模和输出长度。
            RetrieveOptions options = new RetrieveOptions(
                    properties.getRetrieve().getTopK(),
                    properties.getRetrieve().getMaxChars(),
                    properties.getRetrieve().getRrfK(),
                    properties.getRetrieve().getMinScore(),
                    true
            );
            List<MemorySearchResult> results = retriever.hybridSearch(query, options);
            if (results.isEmpty()) {
                return "";
            }
            return formatResultsFromSource(results, options.maxChars());
        });
    }

    private String formatResultsFromSource(List<MemorySearchResult> results, int maxChars) {
        StringBuilder builder = new StringBuilder("## 相关记忆\n\n");
        int remaining = Math.max(maxChars, 500);
        for (MemorySearchResult result : results) {
            if (remaining <= 0) {
                break;
            }
            String source = fileManager.readLines(result.filePath(), result.startLine(), result.endLine());
            if (source.isBlank()) {
                continue;
            }
            // 检索命中的 content 只是索引副本；最终注入给模型的内容必须回读 Markdown 源文件。
            String header = "### [%s] 来源: %s (行 %d-%d, score %.4f)\n"
                    .formatted(
                            result.memoryId(),
                            result.filePath(),
                            result.startLine(),
                            result.endLine(),
                            result.finalScore()
                    );
            String block = header + source + "\n\n";
            if (block.length() > remaining) {
                block = block.substring(0, Math.max(0, remaining - 3)) + "...";
            }
            builder.append(block);
            remaining -= block.length();
        }
        return builder.toString().strip();
    }

    private ConversationContext buildConversationContext(List<Msg> msgs) {
        List<ConversationMessage> messages = new ArrayList<>();
        String sessionId = null;
        String userName = null;
        Instant createdAt = Instant.now();

        // 尽量沿用上游会话元数据，缺失时再生成稳定兜底 ID，避免同一轮消息被写到随机文件。
        for (Msg msg : msgs) {
            if (msg == null) {
                continue;
            }
            Map<String, Object> metadata = msg.getMetadata();
            if (sessionId == null && metadata != null) {
                sessionId = firstMetadata(metadata, "sessionId", "session_id", "conversationId", "threadId");
            }
            if (userName == null && "user".equalsIgnoreCase(roleOf(msg))) {
                userName = msg.getName();
            }
            Instant timestamp = parseInstant(msg.getTimestamp());
            if (timestamp != null && timestamp.isBefore(createdAt)) {
                createdAt = timestamp;
            }
            messages.add(new ConversationMessage(
                    msg.getName(),
                    roleOf(msg),
                    msg.getTextContent(),
                    msg.getTimestamp()
            ));
        }

        if (sessionId == null || sessionId.isBlank()) {
            sessionId = "session-" + Integer.toHexString(new LinkedHashSet<>(messages).hashCode());
        }
        if (userName == null || userName.isBlank()) {
            userName = "user";
        }
        return new ConversationContext(sessionId, userName, createdAt, List.copyOf(messages));
    }

    private static String firstMetadata(Map<String, Object> metadata, String... keys) {
        for (String key : keys) {
            Object value = metadata.get(key);
            if (value != null && !value.toString().isBlank()) {
                return value.toString();
            }
        }
        return null;
    }

    private static Instant parseInstant(String timestamp) {
        if (timestamp == null || timestamp.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(timestamp);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private static String roleOf(Msg msg) {
        return msg.getRole() == null ? "unknown" : msg.getRole().name().toLowerCase();
    }
}
