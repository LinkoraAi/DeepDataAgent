package com.linkroa.deepdataagent.memory.retrieval;

import com.linkroa.deepdataagent.memory.config.MemoryProperties;
import com.linkroa.deepdataagent.memory.model.MemorySearchResult;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 时间衰减重排序器。
 *
 * <p>根据记忆层级、子类别、创建时间、重要性和访问次数计算最终强度，
 * 让稳定知识慢慢衰减、会话事件更快衰减，同时对常被召回的记忆给予增强。</p>
 */
@Component
public class TemporalReranker {

    private final MemoryProperties properties;

    public TemporalReranker(MemoryProperties properties) {
        this.properties = properties;
    }

    public List<MemorySearchResult> rerank(List<MemorySearchResult> results) {
        Instant now = Instant.now();
        return results.stream()
                .map(result -> applyDecay(result, now))
                .sorted((left, right) -> Double.compare(right.finalScore(), left.finalScore()))
                .toList();
    }

    private MemorySearchResult applyDecay(MemorySearchResult result, Instant now) {
        double days = Math.max(0.0, Duration.between(result.createdAt(), now).toHours() / 24.0);
        double lambda = lambda(result.layer(), result.subCategory());
        // accessBoost 让被反复使用的记忆抵消一部分时间衰减，模拟“常用常新”。
        double accessBoost = 1.0 + properties.getTemporal().getRecallBoostFactor() * Math.max(result.accessCount(), 0);
        double strength = Math.max(result.importance(), 0.1) * Math.exp(-lambda * days) * accessBoost;
        double finalScore = result.score() * Math.max(strength, 0.05);
        return new MemorySearchResult(
                result.chunkId(),
                result.memoryId(),
                result.filePath(),
                result.layer(),
                result.subCategory(),
                result.startLine(),
                result.endLine(),
                result.score(),
                finalScore,
                result.importance(),
                result.createdAt(),
                result.accessCount()
        );
    }

    private static double lambda(String layer, String subCategory) {
        String key = (layer == null ? "" : layer) + "/" + (subCategory == null ? "" : subCategory);
        return switch (key) {
            case "semantic/fact" -> 0.03;
            case "semantic/preference" -> 0.05;
            case "semantic/rule" -> 0.04;
            case "skills/pattern", "skills/skill" -> 0.03;
            case "episodic/event" -> 0.12;
            case "episodic/failure" -> 0.20;
            default -> 0.10;
        };
    }
}
