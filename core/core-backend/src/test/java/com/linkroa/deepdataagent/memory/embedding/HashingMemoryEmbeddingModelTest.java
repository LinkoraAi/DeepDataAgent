package com.linkroa.deepdataagent.memory.embedding;

import com.linkroa.deepdataagent.memory.config.MemoryProperties;
import dev.langchain4j.data.embedding.Embedding;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HashingMemoryEmbeddingModelTest {

    @Test
    void should_generateNormalizedVector_when_embed_given_nonEmptyText() {
        MemoryProperties properties = new MemoryProperties();
        properties.getVector().setDimension(4);
        HashingMemoryEmbeddingModel model = new HashingMemoryEmbeddingModel(properties);

        Embedding embedding = model.embed("hello world");

        List<Float> vector = embedding.vectorAsList();
        assertEquals(4, vector.size());

        double sum = 0.0;
        for (Float v : vector) {
            sum += v * v;
        }
        assertEquals(1.0, Math.sqrt(sum), 0.0001);
    }

    @Test
    void should_generateNonZeroVector_when_embed_given_nullText() {
        MemoryProperties properties = new MemoryProperties();
        properties.getVector().setDimension(4);
        HashingMemoryEmbeddingModel model = new HashingMemoryEmbeddingModel(properties);

        Embedding embedding = model.embed(null);

        List<Float> vector = embedding.vectorAsList();
        assertEquals(4, vector.size());

        double sum = 0.0;
        for (Float v : vector) {
            sum += v * v;
        }
        assertEquals(1.0, Math.sqrt(sum), 0.0001);
    }

    @Test
    void should_generateNonZeroVector_when_embed_given_blankText() {
        MemoryProperties properties = new MemoryProperties();
        properties.getVector().setDimension(4);
        HashingMemoryEmbeddingModel model = new HashingMemoryEmbeddingModel(properties);

        Embedding embedding = model.embed("   ");

        List<Float> vector = embedding.vectorAsList();
        assertEquals(4, vector.size());

        double sum = 0.0;
        for (Float v : vector) {
            sum += v * v;
        }
        assertEquals(1.0, Math.sqrt(sum), 0.0001);
    }

    @Test
    void should_generateVectorWithMultipleTokens_when_embed_given_multipleWords() {
        MemoryProperties properties = new MemoryProperties();
        properties.getVector().setDimension(8);
        HashingMemoryEmbeddingModel model = new HashingMemoryEmbeddingModel(properties);

        Embedding embedding = model.embed("Spring Boot YAML 配置");

        List<Float> vector = embedding.vectorAsList();
        assertEquals(8, vector.size());

        boolean hasNonZero = false;
        for (Float v : vector) {
            if (v != 0.0f) {
                hasNonZero = true;
                break;
            }
        }
        assertTrue(hasNonZero, "Vector should have non-zero values");
    }

    @Test
    void should_useDimensionOne_when_embed_given_dimensionLessThanOne() {
        MemoryProperties properties = new MemoryProperties();
        properties.getVector().setDimension(0);
        HashingMemoryEmbeddingModel model = new HashingMemoryEmbeddingModel(properties);

        Embedding embedding = model.embed("test");

        List<Float> vector = embedding.vectorAsList();
        assertEquals(1, vector.size());
    }
}
