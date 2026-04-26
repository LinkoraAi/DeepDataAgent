package com.linkroa.deepdataagent.memory.model;

/**
 * 检索通道返回的带分分块。
 *
 * <p>不同检索通道先输出 ScoredChunk，再由 HybridRetriever 做融合和重排。</p>
 */
public record ScoredChunk(MemoryChunk chunk, double score) {
}
