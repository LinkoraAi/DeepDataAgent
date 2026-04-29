package com.linkroa.deepdataagent.memory.embedding;

import dev.langchain4j.data.embedding.Embedding;

public interface MemoryEmbeddingModel {

    Embedding embed(String text);
}
