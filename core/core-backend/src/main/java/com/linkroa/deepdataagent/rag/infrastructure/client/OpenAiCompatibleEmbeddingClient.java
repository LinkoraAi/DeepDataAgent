package com.linkroa.deepdataagent.rag.infrastructure.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.linkroa.deepdataagent.rag.domain.port.EmbeddingClient;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * OpenAI 兼容向量化客户端（/embeddings，无缓存直调）。
 * <p>HTTP 传输经 {@link ModelTransport}（生产实现基于 AgentScope 框架通道），
 * 真批量报文组装与 index 归位解析仍由本类负责。</p>
 */
@Component
public class OpenAiCompatibleEmbeddingClient implements EmbeddingClient {

    /** 向量化资源路径 */
    private static final String EMBEDDINGS_PATH = "/embeddings";

    /** 单次调用超时 */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);

    /** JSON 序列化器 */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final ModelProfileAccess modelProfileAccess;

    /** 模型端点 HTTP 通道（AgentScope 框架实现，包级缝便于单测注入） */
    private final ModelTransport modelTransport;

    /**
     * 构造向量化客户端（生产装配入口）。
     *
     * @param modelProfileAccess 模型配置解析端口
     */
    @Autowired
    public OpenAiCompatibleEmbeddingClient(ModelProfileAccess modelProfileAccess) {
        this(modelProfileAccess, AgentscopeModelTransport.shared());
    }

    /**
     * 测试缝构造器：注入指定 HTTP 通道。
     *
     * @param modelProfileAccess 模型配置解析端口
     * @param modelTransport     模型端点 HTTP 通道
     */
    OpenAiCompatibleEmbeddingClient(ModelProfileAccess modelProfileAccess, ModelTransport modelTransport) {
        this.modelProfileAccess = modelProfileAccess;
        this.modelTransport = modelTransport;
    }

    @Override
    public float[] embed(String modelProfileId, String text) {
        if (StringUtils.isBlank(modelProfileId)) {
            throw new IllegalArgumentException("嵌入模型 profileId 不能为空");
        }
        if (StringUtils.isBlank(text)) {
            throw new IllegalArgumentException("向量化文本不能为空");
        }
        ModelProfileAccess.ResolvedEndpoint endpoint = modelProfileAccess.resolve(modelProfileId);
        JsonNode response = parseJson(modelTransport.post(endpoint, EMBEDDINGS_PATH,
                buildRequestBody(endpoint, text), REQUEST_TIMEOUT));
        return parseVector(response, endpoint);
    }

    /**
     * 真批量向量化：一次 {@code /embeddings} 调用提交全部文本（input 传数组），
     * 响应 data 按 index 字段回填，保证返回列表与入参列表下标一一对应。
     */
    @Override
    public List<float[]> embedBatch(String modelProfileId, List<String> texts) {
        if (StringUtils.isBlank(modelProfileId)) {
            throw new IllegalArgumentException("嵌入模型 profileId 不能为空");
        }
        if (CollectionUtils.isEmpty(texts)) {
            throw new IllegalArgumentException("向量化文本列表不能为空");
        }
        ModelProfileAccess.ResolvedEndpoint endpoint = modelProfileAccess.resolve(modelProfileId);
        JsonNode response = parseJson(modelTransport.post(endpoint, EMBEDDINGS_PATH,
                buildBatchRequestBody(endpoint, texts), REQUEST_TIMEOUT));
        return parseVectorBatch(response, endpoint, texts.size());
    }

    /**
     * 组装向量请求体：{@code {model, input}}。
     */
    private String buildRequestBody(ModelProfileAccess.ResolvedEndpoint endpoint, String text) {
        ObjectNode body = OBJECT_MAPPER.createObjectNode();
        body.put("model", endpoint.modelName());
        body.put("input", text);
        try {
            return OBJECT_MAPPER.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("向量请求体序列化失败", e);
        }
    }

    /**
     * 组装批量向量请求体：{@code {model, input: [texts]}}。
     */
    private String buildBatchRequestBody(ModelProfileAccess.ResolvedEndpoint endpoint, List<String> texts) {
        ObjectNode body = OBJECT_MAPPER.createObjectNode();
        body.put("model", endpoint.modelName());
        var inputArray = body.putArray("input");
        texts.forEach(inputArray::add);
        try {
            return OBJECT_MAPPER.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("批量向量请求体序列化失败", e);
        }
    }

    /**
     * 通道原始响应文本转 JSON 树（非法 JSON 视为调用失败）。
     */
    private static JsonNode parseJson(String rawBody) {
        try {
            return OBJECT_MAPPER.readTree(rawBody);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("模型接口响应 JSON 解析失败", e);
        }
    }

    /**
     * 解析批量向量响应：data 元素按 index 归位；缺项或数量不足视为响应异常。
     */
    private List<float[]> parseVectorBatch(JsonNode response, ModelProfileAccess.ResolvedEndpoint endpoint,
                                           int expectedSize) {
        JsonNode data = response.path("data");
        if (!data.isArray() || data.size() != expectedSize) {
            throw new RuntimeException("批量向量响应 data 数量不符: 期望 "
                    + expectedSize + ", 实际 " + data.size());
        }
        List<float[]> vectors = new ArrayList<>(expectedSize);
        for (int i = 0; i < expectedSize; i++) {
            vectors.add(null);
        }
        for (JsonNode item : data) {
            int index = item.path("index").asInt(-1);
            if (index < 0 || index >= expectedSize) {
                throw new RuntimeException("批量向量响应 index 越界: " + item.path("index"));
            }
            vectors.set(index, parseEmbedding(item, endpoint));
        }
        return vectors;
    }

    /**
     * 提取 data[0].embedding 为 float 数组；配置了维度时校验一致性。
     */
    private float[] parseVector(JsonNode response, ModelProfileAccess.ResolvedEndpoint endpoint) {
        JsonNode data = response.path("data");
        if (!data.isArray() || data.isEmpty()) {
            throw new RuntimeException("向量响应缺少 data: " + response);
        }
        return parseEmbedding(data.get(0), endpoint);
    }

    /**
     * 从单个 data 元素提取 embedding 数组；配置了维度时校验一致性。
     */
    private float[] parseEmbedding(JsonNode item, ModelProfileAccess.ResolvedEndpoint endpoint) {
        JsonNode embedding = item.path("embedding");
        if (!embedding.isArray() || embedding.isEmpty()) {
            throw new RuntimeException("向量响应缺少 embedding: " + item);
        }
        float[] vector = new float[embedding.size()];
        for (int i = 0; i < embedding.size(); i++) {
            vector[i] = (float) embedding.get(i).asDouble();
        }
        if (endpoint.vectorDimension() != null && vector.length != endpoint.vectorDimension()) {
            throw new RuntimeException("向量维度与模型配置不一致: 期望 "
                    + endpoint.vectorDimension() + ", 实际 " + vector.length);
        }
        return vector;
    }
}
