package com.linkroa.deepdataagent.memory.embedding;

import com.linkroa.deepdataagent.memory.config.MemoryProperties;
import com.linkroa.deepdataagent.memory.util.MemoryText;
import dev.langchain4j.data.embedding.Embedding;

import java.util.Set;

public class HashingMemoryEmbeddingModel implements MemoryEmbeddingModel {

    private final MemoryProperties properties;

    public HashingMemoryEmbeddingModel(MemoryProperties properties) {
        this.properties = properties;
    }

    @Override
    public Embedding embed(String text) {
        int dimension = Math.max(properties.getVector().getDimension(), 1);
        float[] vector = new float[dimension];
        Set<String> tokens = MemoryText.tokens(text);
        if (tokens.isEmpty()) {
            addToken(vector, text == null ? "" : text);
        } else {
            for (String token : tokens) {
                addToken(vector, token);
            }
        }
        normalize(vector);
        return Embedding.from(vector);
    }

    private static void addToken(float[] vector, String token) {
        int hash = token == null ? 0 : token.hashCode();
        int index = Math.floorMod(hash, vector.length);
        float sign = (hash & 1) == 0 ? 1.0f : -1.0f;
        vector[index] += sign;
    }

    private static void normalize(float[] vector) {
        double sum = 0.0;
        for (float value : vector) {
            sum += value * value;
        }
        if (sum <= 0.0) {
            vector[0] = 1.0f;
            return;
        }
        float norm = (float) Math.sqrt(sum);
        for (int i = 0; i < vector.length; i++) {
            vector[i] /= norm;
        }
    }
}
