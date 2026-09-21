package com.linkroa.deepdataagent.rag.infrastructure.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * MinerU 解析服务 HTTP 客户端（mineru-api 异步 tasks 契约）。
 * <p>按知识库解析配置实例化一次（如每知识库一个 Bean）。解析单文件走 4 步异步流程：
 * 创建任务（造 multipart 请求）→ 轮询任务状态 → 拉取解析结果 → 解析产物为按 stem 分组的 content_list。
 * 固定请求开关：{@code return_md=false / return_middle_json=true / return_content_list=true / return_images=true}
 *（content_list 是内容块映射的唯一来源），并恒开 {@code formula_enable / table_enable / image_analysis} 识别。
 * 结果按提交文件名的 stem（去扩展名）定位对应文件的 content_list。
 * 失败语义：创建 400 → 不支持的文件类型；轮询 404 → 任务失联（可能已清理或服务重启）；拉取 409 → 冲突（附 error 字段）；
 * 503 → 任务管理器不可用；其余非预期状态 → 通用 IllegalStateException。内网部署无鉴权。</p>
 * <p>轮询<b>不设总时长上限</b>（超大文档解析耗时不可预估，人为截断会把仍能成功的任务判死）：
 * 循环仅由任务自身的终态收敛——completed（成功）/ failed（解析失败）/ 404（任务失联，
 * 含 MinerU 结果 24h 清理后被清除）/ 503（任务管理器不可用）。最坏情形为任务持续
 * pending/processing 至被 24h 清理，期间占用一条在飞摄入槽位（单实例吞吐约束，已知并接受）。</p>
 * <p>请求/响应 JSON 结构（简化）：创建 POST /tasks 202 → {@code {"task_id","status_url","result_url"}}；
 * 状态 GET /tasks/{taskId} 200 → {@code {"status":"pending|processing|completed|failed"}}；
 * 结果 GET /tasks/{taskId}/result 200 →
 * {@code {"backend","version","results":{<stem>:{"content_list":[...],"images":{图片名:base64}}}}}；
 * 结果 202 → 未就绪（回到轮询循环）。</p>
 */
public class MineruClient {

    /** 默认解析后端：混合引擎。 */
    public static final String DEFAULT_BACKEND = "hybrid-engine";

    /** 默认解析方法：自动。 */
    public static final String DEFAULT_PARSE_METHOD = "auto";

    /** 默认解析质量档位。 */
    public static final String DEFAULT_QUALITY = "medium";

    /** 默认语言列表。 */
    public static final List<String> DEFAULT_LANGUAGE = List.of("ch");

    /** 仅 {@code *-http-client} 后端需要模型服务地址。 */
    private static final String BACKEND_SUFFIX_HTTP_CLIENT = "-http-client";

    /** 任务轮询间隔（毫秒）；包可见，便于单测临时改小。轮询不设总时长上限（见类 Javadoc）。 */
    static long TASK_POLL_INTERVAL_MS = 2000L;

    /** HTTP 连接超时（毫秒）。 */
    private static final int CONNECT_TIMEOUT_MS = 5000;

    /** HTTP 读取超时（毫秒）。 */
    private static final int READ_TIMEOUT_MS = 30000;

    /** 健康检查路径。 */
    private static final String API_PATH_HEALTH = "/health";

    /** 任务资源路径前缀。 */
    private static final String API_PATH_TASKS = "/tasks";

    /** 创建任务表单字段：文件。 */
    private static final String FORM_FIELD_FILES = "files";

    /** 表单布尔值：true。 */
    private static final String FORM_VALUE_TRUE = "true";

    /** 表单布尔值：false。 */
    private static final String FORM_VALUE_FALSE = "false";

    /** 任务状态：已完成。 */
    private static final String STATUS_COMPLETED = "completed";

    /** 任务状态：已失败。 */
    private static final String STATUS_FAILED = "failed";

    /** 结果 JSON 根字段：results。 */
    private static final String JSON_FIELD_RESULTS = "results";

    /** 结果 JSON 条目字段：content_list。 */
    private static final String JSON_FIELD_CONTENT_LIST = "content_list";

    /** 结果 JSON 条目字段：images（{@code {图片文件名: base64 原文}}，恒发 return_images=true 时携带）。 */
    private static final String JSON_FIELD_IMAGES = "images";

    /** 结果条目的类型字段。 */
    private static final String JSON_FIELD_TYPE = "type";

    /** 结果条目的页码字段。 */
    private static final String JSON_FIELD_PAGE_IDX = "page_idx";

    /** 错误响应中的 error 字段。 */
    private static final String JSON_FIELD_ERROR = "error";

    /**
     * 内容块剩余原始字段映射的反序列化类型常量（Jackson 2 {@code com.fasterxml}）。
     * <p>{@code TypeReference} 不可变、线程安全，泛型父类解析在构造期完成，静态复用避免
     * 逐内容块映射时反复构造匿名子类并重复泛型反射解析。</p>
     */
    private static final TypeReference<Map<String, Object>> BLOCK_FIELDS_TYPE = new TypeReference<>() {
    };

    /** 任务创建失败提示。 */
    private static final String ERROR_MESSAGE_CREATE_FAILED = "创建 MinerU 任务失败，HTTP ";

    /** 结果缺失提示。 */
    private static final String ERROR_RESULT_MISSING_CONTENT_LIST = "MinerU 结果缺少 content_list";

    /** 不支持的文件类型提示。 */
    private static final String ERROR_UNSUPPORTED_FILE_TYPE = "不支持的文件类型";

    /** 任务失联提示。 */
    private static final String ERROR_TASK_LOST = "任务失联（可能已清理或服务重启）";

    /** 任务管理不可用提示。 */
    private static final String ERROR_TASK_MANAGER_UNAVAILABLE = "任务管理器不可用";

    /** 任务解析失败提示。 */
    private static final String ERROR_TASK_PARSE_FAILED = "MinerU 任务解析失败";

    /** JSON 解析失败提示。 */
    private static final String ERROR_JSON_INVALID = "MinerU 响应 JSON 解析失败: ";

    /** API 基础地址（去除尾部斜杠）。 */
    private final String apiBaseUrl;

    /** 解析后端（hybrid-engine / pipeline / vlm-engine / hybrid-http-client / vlm-http-client）。 */
    private final String backend;

    /** 模型服务地址（可空，仅 *-http-client 后端必填）。 */
    private final String modelServerUrl;

    /** 解析方法（auto / txt / ocr）。 */
    private final String parseMethod;

    /** 语言列表（ch / ch_server / korean / ta / te / ka / th / el / arabic / east_slavic / cyrillic / devanagari）。 */
    private final List<String> language;

    /** 解析质量（medium / high）。 */
    private final String quality;

    /** HTTP 客户端。 */
    private final RestTemplate restTemplate;

    /** JSON 解析器（spring-web 自带 Jackson）。 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 默认参数构造器：其余解析参数取默认值（backend=hybrid-engine、parseMethod=auto、
     * language=[ch]、quality=medium、modelServerUrl=null）。
     *
     * @param apiBaseUrl   MinerU 服务地址（必填，调用方保证 URL 格式合法；内网无鉴权）
     * @param restTemplate HTTP 客户端（可空：为空时内部新建并配置连接/读超时）
     */
    public MineruClient(String apiBaseUrl, RestTemplate restTemplate) {
        this(apiBaseUrl, DEFAULT_BACKEND, null, DEFAULT_PARSE_METHOD, DEFAULT_LANGUAGE, DEFAULT_QUALITY, restTemplate);
    }

    /**
     * 完整参数构造器：按知识库解析配置实例化一次。
     *
     * @param apiBaseUrl     MinerU 服务地址（必填，调用方保证 URL 格式合法；内网无鉴权）
     * @param backend        解析后端（hybrid-engine / pipeline / vlm-engine / hybrid-http-client / vlm-http-client）
     * @param modelServerUrl 模型服务地址（可空；仅 *-http-client 后端必填）
     * @param parseMethod    解析方法（auto / txt / ocr）
     * @param language       语言列表（12 种支持语言之一或多个）
     * @param quality        解析质量（medium / high）
     * @param restTemplate   HTTP 客户端（可空：为空时内部新建并配置连接/读超时）
     */
    public MineruClient(String apiBaseUrl, String backend, String modelServerUrl, String parseMethod,
                        List<String> language, String quality, RestTemplate restTemplate) {
        if (StringUtils.isBlank(apiBaseUrl)) {
            throw new IllegalArgumentException("MinerU API 地址不能为空");
        }
        this.apiBaseUrl = stripTrailingSlash(apiBaseUrl);
        this.backend = StringUtils.defaultIfBlank(backend, DEFAULT_BACKEND);
        this.modelServerUrl = modelServerUrl;
        this.parseMethod = StringUtils.defaultIfBlank(parseMethod, DEFAULT_PARSE_METHOD);
        this.language = ObjectUtils.isEmpty(language) ? DEFAULT_LANGUAGE : List.copyOf(language);
        this.quality = StringUtils.defaultIfBlank(quality, DEFAULT_QUALITY);
        this.restTemplate = initRestTemplate(restTemplate);
    }

    /**
     * 初始化 HTTP 客户端：传入为空时内部新建并配置连接/读超时；一律关闭默认 4xx/5xx 抛异常行为，
     * 改为由调用方按响应状态码分支处理（exchange 直接返回带状态码的响应）。
     *
     * @param given 调用方传入的客户端（可空）
     * @return 可用的 RestTemplate
     */
    private RestTemplate initRestTemplate(RestTemplate given) {
        RestTemplate template = given;
        if (ObjectUtils.isEmpty(template)) {
            template = new RestTemplate();
            SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
            requestFactory.setConnectTimeout(CONNECT_TIMEOUT_MS);
            requestFactory.setReadTimeout(READ_TIMEOUT_MS);
            template.setRequestFactory(requestFactory);
        }
        template.setErrorHandler(new DefaultResponseErrorHandler() {
            @Override
            protected boolean hasError(HttpStatusCode statusCode) {
                return false;
            }
        });
        return template;
    }

    /**
     * 探活：GET {apiBaseUrl}/health，200 视为服务正常；503 / 其他视为不可用抛异常
     * （供探活复用）。
     *
     * @throws IllegalStateException 服务不可用
     */
    public void healthCheck() {
        ResponseEntity<String> response = restTemplate.exchange(apiBaseUrl + API_PATH_HEALTH, HttpMethod.GET,
                HttpEntity.EMPTY, String.class);
        if (response.getStatusCode().is2xxSuccessful()) {
            return;
        }
        throw new IllegalStateException("MinerU 服务不可用，HTTP " + response.getStatusCode().value());
    }

    /**
     * 解析单文件：异步四步（创建任务 → 轮询状态 → 拉取结果 → 解析映射），
     * 返回按文件名 stem（去扩展名）分组的 content_list 解析结果（含恒发 return_images=true
     * 时响应携带的媒体图片 base64 载荷）。
     *
     * @param fileContent 文件内容字节
     * @param fileName    提交给 MinerU 的文件名（含扩展名）
     * @return 按 stem 分组的结果（该文件的 content_list 块列表 + 媒体图片载荷）
     * @throws IllegalArgumentException 创建任务 400（不支持的文件类型）
     * @throws IllegalStateException    任务失败 / 失联 / 任务管理器不可用 / 结果冲突 / 结果缺失
     */
    public ParsedResult parse(byte[] fileContent, String fileName) {
        String taskId = createTask(fileContent, fileName);
        String resultJson = awaitResult(taskId);
        return buildParsedResult(resultJson);
    }

    /**
     * 第 1 步：创建解析任务（POST multipart/form-data），期望 202。
     *
     * @return 任务 ID
     */
    private String createTask(byte[] fileContent, String fileName) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        HttpEntity<LinkedMultiValueMap<String, Object>> request =
                new HttpEntity<>(buildCreateTaskBody(fileContent, fileName), headers);
        ResponseEntity<String> response = restTemplate.exchange(apiBaseUrl + API_PATH_TASKS, HttpMethod.POST,
                request, String.class);
        int status = response.getStatusCode().value();
        if (status == 202) {
            return extractTaskId(response.getBody());
        }
        if (status == 400) {
            throw new IllegalArgumentException(ERROR_UNSUPPORTED_FILE_TYPE);
        }
        throw new IllegalStateException(ERROR_MESSAGE_CREATE_FAILED + status);
    }

    /**
     * 组装创建任务的多部分表单体：固定开关、恒开识别与按配置映射的解析参数。
     */
    private LinkedMultiValueMap<String, Object> buildCreateTaskBody(byte[] fileContent, String fileName) {
        LinkedMultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add(FORM_FIELD_FILES, new NamedByteArrayResource(fileContent, fileName));
        // 固定开关：content_list 为内容块映射唯一来源
        body.add("return_md", FORM_VALUE_FALSE);
        body.add("return_middle_json", FORM_VALUE_TRUE);
        body.add("return_content_list", FORM_VALUE_TRUE);
        body.add("return_images", FORM_VALUE_TRUE);
        // 恒开识别
        body.add("formula_enable", FORM_VALUE_TRUE);
        body.add("table_enable", FORM_VALUE_TRUE);
        body.add("image_analysis", FORM_VALUE_TRUE);
        // 参数映射：ApiConsume 字段名 → MinerU 请求字段
        body.add("backend", backend);
        if (StringUtils.endsWith(backend, BACKEND_SUFFIX_HTTP_CLIENT) && StringUtils.isNotBlank(modelServerUrl)) {
            body.add("model_server_url", modelServerUrl);
        }
        if (isMethodCapableBackend()) {
            body.add("parse_method", parseMethod);
            for (String lang : language) {
                body.add("lang_list", lang);
            }
        }
        if (StringUtils.equals(backend, DEFAULT_BACKEND)) {
            body.add("effort", quality);
        }
        return body;
    }

    /**
     * parse_method / lang_list 仅对 pipeline 与 hybrid-engine 后端生效。
     *
     * @return true 表示当前后端支持解析方法与语言参数
     */
    private boolean isMethodCapableBackend() {
        return StringUtils.equals(backend, "pipeline") || StringUtils.equals(backend, DEFAULT_BACKEND);
    }

    /**
     * 从创建任务响应中提取 task_id。
     */
    private String extractTaskId(String body) {
        JsonNode root = readJson(body);
        JsonNode taskId = root.get("task_id");
        if (ObjectUtils.isEmpty(taskId) || StringUtils.isBlank(taskId.asText())) {
            throw new IllegalStateException("创建 MinerU 任务响应缺少 task_id");
        }
        return taskId.asText();
    }

    /**
     * 第 2/3 步：轮询任务状态直到 completed 后拉取结果；failed / 失联 / 管理器不可用均抛异常。
     * 拉取结果 202（未就绪）时回到轮询循环继续等待。
     * <p><b>不设总时长上限</b>：仅由任务终态收敛（见类 Javadoc），中断线程即抛
     * {@link IllegalStateException}（停机路径）。最坏情形受 MinerU 结果 24h 清理约束——
     * 任务被清除后轮询 404 → 任务失联异常。</p>
     *
     * @return 拉取到的结果 JSON 字符串（200 响应体）
     */
    private String awaitResult(String taskId) {
        while (true) {
            String status = pollTaskStatus(taskId);
            if (StringUtils.equals(status, STATUS_COMPLETED)) {
                String resultJson = fetchResult(taskId);
                if (StringUtils.isNotBlank(resultJson)) {
                    return resultJson;
                }
            } else if (StringUtils.equals(status, STATUS_FAILED)) {
                throw new IllegalStateException(ERROR_TASK_PARSE_FAILED);
            }
            sleepQuietly();
        }
    }

    /**
     * 第 2 步：查询任务状态（GET /tasks/{taskId}）。404 视为任务失联，503 视为任务管理器不可用。
     *
     * @return 任务状态字符串
     */
    private String pollTaskStatus(String taskId) {
        ResponseEntity<String> response = restTemplate.exchange(
                apiBaseUrl + API_PATH_TASKS + "/" + taskId, HttpMethod.GET, HttpEntity.EMPTY, String.class);
        int status = response.getStatusCode().value();
        if (status == 200) {
            return readJson(response.getBody()).path("status").asText();
        }
        if (status == 404) {
            throw new IllegalStateException(ERROR_TASK_LOST);
        }
        if (status == 503) {
            throw new IllegalStateException(ERROR_TASK_MANAGER_UNAVAILABLE);
        }
        throw new IllegalStateException("查询 MinerU 任务状态失败，HTTP " + status);
    }

    /**
     * 第 3 步：拉取解析结果（GET /tasks/{taskId}/result）。202 未就绪返回 null（回到轮询循环）；
     * 409 冲突时附加响应 error 字段。
     *
     * @return 200 响应体字符串；202 返回 null
     */
    private String fetchResult(String taskId) {
        ResponseEntity<String> response = restTemplate.exchange(
                apiBaseUrl + API_PATH_TASKS + "/" + taskId + "/result", HttpMethod.GET, HttpEntity.EMPTY, String.class);
        int status = response.getStatusCode().value();
        if (status == 200) {
            return response.getBody();
        }
        if (status == 202) {
            return null;
        }
        if (status == 409) {
            String error = readJson(response.getBody()).path(JSON_FIELD_ERROR).asText();
            throw new IllegalStateException("拉取 MinerU 结果冲突: " + error);
        }
        throw new IllegalStateException("拉取 MinerU 结果失败，HTTP " + status);
    }

    /**
     * 第 4 步：解析产物 —— results 中每项取 content_list 数组（逐条映射 Block，缺失或空视为解析失败）
     * 与 images 对象（{@code {图片文件名: base64}}，缺失 / 非对象时为空头，不影响解析成功判定）。
     */
    private ParsedResult buildParsedResult(String resultJson) {
        JsonNode root = readJson(resultJson);
        JsonNode results = root.path(JSON_FIELD_RESULTS);
        Map<String, List<Block>> resultMap = new HashMap<>();
        Map<String, Map<String, String>> imageMap = new HashMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = results.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            JsonNode contentList = entry.getValue().path(JSON_FIELD_CONTENT_LIST);
            if (CollectionUtils.isEmpty(toList(contentList))) {
                throw new IllegalStateException(ERROR_RESULT_MISSING_CONTENT_LIST);
            }
            List<Block> blocks = new ArrayList<>();
            for (JsonNode item : contentList) {
                blocks.add(toBlock(item));
            }
            resultMap.put(entry.getKey(), blocks);
            imageMap.put(entry.getKey(), toImagePayload(entry.getValue().path(JSON_FIELD_IMAGES)));
        }
        return new ParsedResult(resultMap, imageMap);
    }

    /**
     * 解析 {@code results.<stem>.images} 为「图片文件名 → base64 原文」映射。
     * <p>容错口径：节点缺失 / 非对象 → 空头；单项值非字符串（null / 数字 / 对象等）→ 跳过该项。
     * 图片载荷缺失不构成解析失败（内容块映射只依赖 content_list）。</p>
     *
     * @param imagesNode images 节点，可为 MissingNode
     * @return 图片名 → base64 原文（不可变，无图片时为空映射）
     */
    private Map<String, String> toImagePayload(JsonNode imagesNode) {
        if (ObjectUtils.isEmpty(imagesNode) || !imagesNode.isObject()) {
            return Map.of();
        }
        Map<String, String> images = new HashMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = imagesNode.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            JsonNode value = entry.getValue();
            if (ObjectUtils.isNotEmpty(value) && value.isTextual() && StringUtils.isNotBlank(value.asText())) {
                images.put(entry.getKey(), value.asText());
            }
        }
        return Map.copyOf(images);
    }

    /**
     * JsonNode 节点转 Block：type / page_idx 单独取出，其余原始字段原样放入 fields。
     */
    private Block toBlock(JsonNode item) {
        JsonNode typeNode = item.get(JSON_FIELD_TYPE);
        String type = ObjectUtils.isEmpty(typeNode) ? null : typeNode.asText();
        JsonNode pageIdxNode = item.get(JSON_FIELD_PAGE_IDX);
        Integer pageIdx = ObjectUtils.isEmpty(pageIdxNode) ? null : pageIdxNode.asInt();
        Map<String, Object> fields = objectMapper.convertValue(item, new TypeReference<Map<String, Object>>() {
        });
        return new Block(type, pageIdx, fields);
    }

    /**
     * JsonNode 转 List（content_list 数组为空数组 / Missing 节点时返回空列表）。
     */
    private List<JsonNode> toList(JsonNode node) {
        List<JsonNode> list = new ArrayList<>();
        if (!ObjectUtils.isEmpty(node) && node.isArray()) {
            node.forEach(list::add);
        }
        return list;
    }

    /**
     * 解析 JSON 字符串为树节点，非法 JSON 视为响应契约破坏。
     */
    private JsonNode readJson(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(ERROR_JSON_INVALID + json, e);
        }
    }

    /**
     * 轮询间隔休眠；中断时恢复中断标记并整体失败。
     */
    private void sleepQuietly() {
        try {
            Thread.sleep(TASK_POLL_INTERVAL_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("任务轮询被中断", e);
        }
    }

    /**
     * 去除 API 地址尾部斜杠，避免双斜杠拼接。
     */
    private String stripTrailingSlash(String url) {
        return StringUtils.removeEnd(url, "/");
    }

    /**
     * 解析结果：按文件名 stem（去扩展名）分组的 content_list 块映射与媒体图片载荷。
     *
     * @param results stem → 该文件的 content_list 块列表
     * @param images  stem → 该文件的媒体图片载荷（图片名 → base64 原文，无图片时为空映射）
     */
    public record ParsedResult(Map<String, List<Block>> results, Map<String, Map<String, String>> images) {

        /**
         * 紧凑构造器：图片载荷缺失归一为空映射（调用方无需再判空）。
         */
        public ParsedResult {
            images = ObjectUtils.isEmpty(images) ? Map.of() : Map.copyOf(images);
        }

        /**
         * 兼容构造器：无媒体图片载荷的解析结果。
         *
         * @param results stem → 该文件的 content_list 块列表
         */
        public ParsedResult(Map<String, List<Block>> results) {
            this(results, Map.of());
        }
    }

    /**
     * content_list 原始条目块：type（text/image/table/equation/generic）、页码与原始 JSON 字段。
     *
     * @param type    content_list 项的类型（空为 MINERU 语义缺失）
     * @param pageIdx 所在页码（page_idx）
     * @param fields  该条目的全部原始 JSON 字段
     */
    public record Block(String type, Integer pageIdx, Map<String, Object> fields) {
    }

    /**
     * 携带文件名信息的字节资源（multipart 文件 part 载体）。
     */
    private static final class NamedByteArrayResource extends ByteArrayResource {

        /** 提交给 MinerU 的文件名（含扩展名）。 */
        private final String filename;

        NamedByteArrayResource(byte[] content, String filename) {
            super(content);
            this.filename = filename;
        }

        @Override
        public String getFilename() {
            return filename;
        }
    }
}