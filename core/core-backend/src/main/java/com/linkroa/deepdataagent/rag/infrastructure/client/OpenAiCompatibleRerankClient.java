package com.linkroa.deepdataagent.rag.infrastructure.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 重排客户端（{@code POST {api_endpoint_url}} 原样直调，一次请求打完全部候选）。
 * <p><b>URL 语义（与 embedding / chat 的「版本化 base + 追加资源路径」约定不同）</b>：
 * 本客户端不在配置端点后追加任何资源路径，{@code api_endpoint_url} 登记什么就请求什么——
 * OpenAI 兼容形态（Cohere / Jina / SiliconFlow 事实标准）须登记完整地址
 * （如 {@code https://api.siliconflow.cn/v1/rerank}）；DashScope 原生
 * text-rerank 服务登记其完整路径（如
 * {@code https://{host}/api/v1/services/rerank/text-rerank/text-rerank}）。</p>
 * <p>请求体统一为扁平 {@code {model, query, documents}}（DashScope 原生路径实测同样接受该形态）；
 * 响应体兼容两种包装：OpenAI 兼容为顶层 {@code {results: [{index, relevance_score}]}}，
 * DashScope 原生为 {@code {output: {results: [...]}}}（由 {@link #resolveResultsNode} 统一解包）。</p>
 * <p>刻意<b>不下发</b> {@code top_n}：一旦下发，服务端可能只返回前 N 条，响应长度与请求长度
 * 无法对齐，剩余候选会被 {@link #parseScores(JsonNode, int)} 判为协议异常。</p>
 * <p><b>不做模型类型校验</b>：agent BC 的 {@code ModelType} 只有 CHAT / EMBEDDING
 * （历史上设计过的 RERANKER 类型已删除），模型注册表内不存在 rerank 专用类型，
 * 故本类只按 {@code modelProfileId} 取端点 / apiKey / modelName 直调；
 * 后续请勿在此补「模型类型必须等于某枚举」的前置校验，否则重排会因无类型可选而永久不可用。</p>
 *
 * @author DeepDataAgent
 */
@Component
public class OpenAiCompatibleRerankClient implements RerankClient {

    /** 空资源路径：端点原样使用配置值，不做任何路径追加（URL 语义见类注释） */
    private static final String EMPTY_RESOURCE_PATH = "";

    /**
     * 单次调用超时。
     * <p>重排位于同步检索链路（不同于摄入侧分钟级的 LLM 生成调用），其负载是纯打分、
     * 与向量化同量级，故取与 {@code OpenAiCompatibleEmbeddingClient} 一致的 60s 上限；
     * 超过即视为服务不可用，由精排降级直出粗排。</p>
     */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);

    /** JSON 序列化器 */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** 请求体字段：模型名 */
    private static final String FIELD_MODEL = "model";

    /** 请求体字段：查询文本 */
    private static final String FIELD_QUERY = "query";

    /** 请求体字段：候选文档列表 */
    private static final String FIELD_DOCUMENTS = "documents";

    /** 响应体字段：打分结果列表 */
    private static final String FIELD_RESULTS = "results";

    /** 响应体字段：DashScope 原生响应的结果外层包装（{@code output.results}） */
    private static final String FIELD_OUTPUT = "output";

    /** 响应体字段：结果对应的请求文档下标 */
    private static final String FIELD_INDEX = "index";

    /** 响应体字段：相关分 */
    private static final String FIELD_RELEVANCE_SCORE = "relevance_score";

    /** 响应 index 缺失/非数值时的哨兵值（负数必然被下界校验判为协议异常） */
    private static final int INDEX_ABSENT = -1;

    /** 合法 index 下界 */
    private static final int INDEX_MIN = 0;

    /** 模型配置解析端口（profileId → baseUrl / apiKey / modelName） */
    private final ModelProfileAccess modelProfileAccess;

    /** 模型端点 HTTP 通道（AgentScope 框架实现，包级缝便于单测注入） */
    private final ModelTransport modelTransport;

    /**
     * 构造重排客户端（生产装配入口）。
     *
     * @param modelProfileAccess 模型配置解析端口
     */
    @Autowired
    public OpenAiCompatibleRerankClient(ModelProfileAccess modelProfileAccess) {
        this(modelProfileAccess, AgentscopeModelTransport.shared());
    }

    /**
     * 测试缝构造器：注入指定 HTTP 通道。
     *
     * @param modelProfileAccess 模型配置解析端口
     * @param modelTransport     模型端点 HTTP 通道
     */
    OpenAiCompatibleRerankClient(ModelProfileAccess modelProfileAccess, ModelTransport modelTransport) {
        this.modelProfileAccess = modelProfileAccess;
        this.modelTransport = modelTransport;
    }

    @Override
    public List<Double> scoreBatch(String modelProfileId, String query, List<String> passages) {
        if (StringUtils.isBlank(modelProfileId)) {
            throw new IllegalArgumentException("重排模型 profileId 不能为空");
        }
        if (CollectionUtils.isEmpty(passages)) {
            return Collections.emptyList();
        }
        ModelProfileAccess.ResolvedEndpoint endpoint = modelProfileAccess.resolve(modelProfileId);
        JsonNode response = parseJson(modelTransport.post(endpoint, EMPTY_RESOURCE_PATH,
                buildRequestBody(endpoint, query, passages), REQUEST_TIMEOUT));
        return parseScores(response, passages.size());
    }

    /**
     * 通道原始响应文本转 JSON 树（非法 JSON 视为调用失败）。
     *
     * @param rawBody 响应体文本
     * @return 响应 JSON 树
     */
    private static JsonNode parseJson(String rawBody) {
        try {
            return OBJECT_MAPPER.readTree(rawBody);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("模型接口响应 JSON 解析失败", e);
        }
    }

    /**
     * 组装重排请求体：{@code {model, query, documents:[passages]}}。
     * <p>包级可见以便单测直接断言报文形态（口径同 {@code OpenAiCompatibleLlmClient}）。</p>
     *
     * @param endpoint 已解析的模型端点（提供 model 字段值）
     * @param query    用户查询文本（null 归一为空串，避免请求体出现 null 字面量）
     * @param passages 候选正文列表（顺序即 documents 顺序）
     * @return 请求体 JSON 文本
     */
    static String buildRequestBody(ModelProfileAccess.ResolvedEndpoint endpoint, String query,
                                   List<String> passages) {
        ObjectNode body = OBJECT_MAPPER.createObjectNode();
        body.put(FIELD_MODEL, endpoint.modelName());
        body.put(FIELD_QUERY, StringUtils.defaultString(query));
        var documents = body.putArray(FIELD_DOCUMENTS);
        passages.forEach(documents::add);
        try {
            return OBJECT_MAPPER.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("重排请求体序列化失败", e);
        }
    }

    /**
     * 解包重排响应的 results 节点：优先取顶层 {@code results}（OpenAI 兼容形态），
     * 顶层缺失或非数组时回退取 {@code output.results}（DashScope 原生 text-rerank 形态）。
     * <p>两种形态均缺失时返回最后一次探测结果（MissingNode 或非数组），由调用方统一判协议异常。</p>
     *
     * @param response 远程响应 JSON
     * @return results 节点（可能为缺失节点，交调用方校验）
     */
    static JsonNode resolveResultsNode(JsonNode response) {
        JsonNode results = response.path(FIELD_RESULTS);
        if (results.isArray()) {
            return results;
        }
        return response.path(FIELD_OUTPUT).path(FIELD_RESULTS);
    }

    /**
     * 解析重排响应：results 元素按 {@code index} 归位，保证返回列表与请求 documents 同序等长。
     * <p>先经 {@link #resolveResultsNode} 统一解包 OpenAI 兼容（顶层 results）与
     * DashScope 原生（{@code output.results}）两种响应形态，再做归位与协议校验。</p>
     * <p>包级可见以便单测直接覆盖乱序回填与协议异常分支。</p>
     *
     * @param response     远程响应 JSON
     * @param expectedSize 请求候选数量
     * @return 与请求同序的相关分列表
     * @throws RuntimeException 响应缺 results、数量不符、index 越界、重复 index 或分数非数值
     */
    static List<Double> parseScores(JsonNode response, int expectedSize) {
        JsonNode results = resolveResultsNode(response);
        if (!results.isArray()) {
            throw new RuntimeException("重排响应缺少 results: " + response);
        }
        if (results.size() != expectedSize) {
            throw new RuntimeException("重排响应 results 数量不符: 期望 " + expectedSize
                    + ", 实际 " + results.size());
        }
        List<Double> scores = new ArrayList<>(expectedSize);
        for (int i = 0; i < expectedSize; i++) {
            scores.add(null);
        }
        for (JsonNode item : results) {
            int index = item.path(FIELD_INDEX).asInt(INDEX_ABSENT);
            if (index < INDEX_MIN || index >= expectedSize) {
                throw new RuntimeException("重排响应 index 越界: " + item.path(FIELD_INDEX));
            }
            scores.set(index, parseRelevanceScore(item));
        }
        for (int i = 0; i < expectedSize; i++) {
            if (ObjectUtils.isEmpty(scores.get(i))) {
                throw new RuntimeException("重排响应 results 存在重复 index 或缺项: " + response);
            }
        }
        return scores;
    }

    /**
     * 提取单条 {@code relevance_score}。
     * <p>非数值（缺失、字符串、布尔）视为协议异常而非 0 分——0 分会让该候选被相似阈值静默过滤。</p>
     */
    private static double parseRelevanceScore(JsonNode item) {
        JsonNode score = item.path(FIELD_RELEVANCE_SCORE);
        if (!score.isNumber()) {
            throw new RuntimeException("重排响应 relevance_score 非数值: " + item);
        }
        return score.asDouble();
    }
}
