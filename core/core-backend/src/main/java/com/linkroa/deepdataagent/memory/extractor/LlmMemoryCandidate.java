package com.linkroa.deepdataagent.memory.extractor;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * LLM 提取的原始记忆候选。
 *
 * <p>对应 LLM 输出的 JSON 结构，解析后转换为 ExtractedMemory。</p>
 */
class LlmMemoryCandidate {

    @JsonProperty("layer")
    String layer;

    @JsonProperty("subCategory")
    String subCategory;

    @JsonProperty("title")
    String title;

    @JsonProperty("content")
    String content;

    @JsonProperty("importance")
    Double importance;

    @JsonProperty("reasoning")
    String reasoning;
}
