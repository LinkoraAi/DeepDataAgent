package com.linkroa.deepdataagent.rag.infrastructure.client;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link MineruClient} 单元测试。
 * <p>Mock {@link RestTemplate}，模拟 MinerU 异步任务契约（创建 202 / 轮询 completed / 拉取结果），
 * 覆盖完整成功链路、文件类型拒绝（400）、任务失联（404）、任务管理器不可用（503）、解析失败
 * （failed / 409 冲突 / content_list 缺失）、轮询不设总时长上限（多轮轮询与结果未就绪再轮，
 * 借助包可见间隔常量临时改小后恢复正常快速收敛）、
 * 探活 200/503，以及后端参数表单映射（lang_list / parse_method / model_server_url / effort 条件提交）。</p>
 */
@ExtendWith(MockitoExtension.class)
class MineruClientTest {

    /** 测试 API 基础地址。 */
    private static final String API_BASE_URL = "http://mineru.internal";

    /** 任务创建地址。 */
    private static final String TASKS_URL = API_BASE_URL + "/tasks";

    /** 任务状态查询地址。 */
    private static final String TASK_URL = TASKS_URL + "/t1";

    /** 任务结果拉取地址。 */
    private static final String RESULT_URL = TASK_URL + "/result";

    /** 健康检查地址。 */
    private static final String HEALTH_URL = API_BASE_URL + "/health";

    /** 轮询临时间隔（毫秒），多轮轮询用例改小以快速完成。 */
    private static final long POLL_INTERVAL_TINY_MS = 20L;

    /** HTTP 客户端 Mock。 */
    @Mock
    private RestTemplate restTemplate;

    /** 被测对象（每个用例前新建，注入 Mock 客户端）。 */
    private MineruClient client;

    /** 轮询间隔原始值（用例临时改小前的备份）。 */
    private long originalPollIntervalMs;

    /**
     * 每个用例前构造被测对象（默认参数：hybrid-engine / auto / [ch] / medium）。
     */
    @BeforeEach
    void setUp() {
        client = new MineruClient(API_BASE_URL, restTemplate);
    }

    /**
     * 场景：探活返回 200。
     * 预期：不抛异常。
     */
    @Test
    void should_succeed_when_healthCheck_given_200() {
        // given
        when(restTemplate.exchange(eq(HEALTH_URL), eq(HttpMethod.GET), any(), eq(String.class)))
                .thenReturn(ResponseEntity.status(200).build());

        // when / then
        client.healthCheck();
    }

    /**
     * 场景：探活返回 503（服务不可用）。
     * 预期：抛出 {@link IllegalStateException}，提示 HTTP 状态码。
     */
    @Test
    void should_throwException_when_healthCheck_given_503() {
        // given
        when(restTemplate.exchange(eq(HEALTH_URL), eq(HttpMethod.GET), any(), eq(String.class)))
                .thenReturn(ResponseEntity.status(503).build());

        // when / then
        IllegalStateException exception = assertThrows(IllegalStateException.class, client::healthCheck);
        assertEquals("MinerU 服务不可用，HTTP 503", exception.getMessage());
    }

    /**
     * 场景：创建任务 202 → 轮询 completed → 拉取 200 且含 content_list。
     * 预期：返回按 stem 分组的结果，块类型 / 页码 / 原始字段正确。
     */
    @Test
    void should_returnParsedResult_when_parse_given_completedFlow() {
        // given
        when(restTemplate.exchange(eq(TASKS_URL), eq(HttpMethod.POST), any(), eq(String.class)))
                .thenReturn(ResponseEntity.status(202).body("{\"task_id\":\"t1\"}"));
        when(restTemplate.exchange(eq(TASK_URL), eq(HttpMethod.GET), any(), eq(String.class)))
                .thenReturn(ResponseEntity.status(200).body("{\"status\":\"completed\"}"));
        when(restTemplate.exchange(eq(RESULT_URL), eq(HttpMethod.GET), any(), eq(String.class)))
                .thenReturn(ResponseEntity.status(200).body(
                        "{\"status\":\"completed\",\"results\":{\"report\":{\"content_list\":"
                                + "[{\"type\":\"text\",\"page_idx\":0,\"text\":\"hello\",\"ordered\":0}]}}}"));

        // when
        MineruClient.ParsedResult parsed = client.parse(new byte[]{1, 2, 3}, "report.pdf");

        // then
        List<MineruClient.Block> blocks = parsed.results().get("report");
        assertEquals(1, blocks.size());
        assertEquals("text", blocks.get(0).type());
        assertEquals(0, blocks.get(0).pageIdx());
        assertEquals("hello", blocks.get(0).fields().get("text"), "原始 JSON 字段应保留进 fields");
        assertTrue(parsed.images().get("report").isEmpty(), "响应未携带 images 时该 stem 的图片载荷为空映射");
    }

    /**
     * 场景（converge-rag-hot-path-object-creation / 3.4）：逐内容块字段映射经常量
     * {@code BLOCK_FIELDS_TYPE}（Jackson 2）解码。
     * 预期：同一 JSON 经常量解析与经匿名 TypeReference 实例解析结果逐字段一致，
     * 反序列化产出不因常量化而改变。
     */
    @Test
    void should_mapBlockFieldsIdenticallyToAnonymousType_when_parse_given_constantTypeReference() throws Exception {
        // given：一个携带多种字段形态的内容块
        String blockJson = "{\"type\":\"table\",\"page_idx\":2,\"text\":\"表格文本\","
                + "\"table_caption\":\"表一\",\"ordered\":1}";
        when(restTemplate.exchange(eq(TASKS_URL), eq(HttpMethod.POST), any(), eq(String.class)))
                .thenReturn(ResponseEntity.status(202).body("{\"task_id\":\"t1\"}"));
        when(restTemplate.exchange(eq(TASK_URL), eq(HttpMethod.GET), any(), eq(String.class)))
                .thenReturn(ResponseEntity.status(200).body("{\"status\":\"completed\"}"));
        when(restTemplate.exchange(eq(RESULT_URL), eq(HttpMethod.GET), any(), eq(String.class)))
                .thenReturn(ResponseEntity.status(200).body(
                        "{\"status\":\"completed\",\"results\":{\"report\":{\"content_list\":["
                                + blockJson + "]}}}"));

        // when：走生产常量解码路径
        MineruClient.ParsedResult parsed = client.parse(new byte[]{1, 2, 3}, "report.pdf");

        // then：与匿名 TypeReference 实例独立解码的基准逐字段一致
        Map<String, Object> anonymousBaseline = new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue(blockJson,
                        new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                        });
        assertEquals(anonymousBaseline, parsed.results().get("report").get(0).fields(),
                "BLOCK_FIELDS_TYPE 常量映射应与匿名 TypeReference 实例解码一致");
    }

    /**
     * 场景：结果响应在 content_list 之外携带 images 载荷（恒发 return_images=true）。
     * 预期：images 按 stem 分组解析为「图片名 → base64 原文」，非字符串值的图片项被跳过。
     */
    @Test
    void should_returnImagePayload_when_parse_given_resultsWithImages() {
        // given
        when(restTemplate.exchange(eq(TASKS_URL), eq(HttpMethod.POST), any(), eq(String.class)))
                .thenReturn(ResponseEntity.status(202).body("{\"task_id\":\"t1\"}"));
        when(restTemplate.exchange(eq(TASK_URL), eq(HttpMethod.GET), any(), eq(String.class)))
                .thenReturn(ResponseEntity.status(200).body("{\"status\":\"completed\"}"));
        when(restTemplate.exchange(eq(RESULT_URL), eq(HttpMethod.GET), any(), eq(String.class)))
                .thenReturn(ResponseEntity.status(200).body(
                        "{\"results\":{\"report\":{\"content_list\":[{\"type\":\"image\",\"page_idx\":0,"
                                + "\"img_path\":\"images/a.png\",\"image_caption\":[\"图1\"]}],"
                                + "\"images\":{\"a.png\":\"YWJj\",\"b.png\":\"\",\"c.png\":123}}}}"));

        // when
        MineruClient.ParsedResult parsed = client.parse(new byte[]{1, 2, 3}, "report.pdf");

        // then：base64 原文按名承载；空白值与非字符串值项跳过
        Map<String, String> images = parsed.images().get("report");
        assertEquals(1, images.size());
        assertEquals("YWJj", images.get("a.png"), "base64 原文应原样透传，由解析器侧解码");
        assertEquals("image", parsed.results().get("report").get(0).type(), "content_list 主链路不受图片载荷影响");
    }

    /**
     * 场景：结果响应 images 键缺失或形态异常（非对象）。
     * 预期：解析成功且图片载荷为空映射，不影响 content_list 主链路。
     */
    @Test
    void should_returnEmptyImagePayload_when_parse_given_missingOrMalformedImages() {
        // given
        when(restTemplate.exchange(eq(TASKS_URL), eq(HttpMethod.POST), any(), eq(String.class)))
                .thenReturn(ResponseEntity.status(202).body("{\"task_id\":\"t1\"}"));
        when(restTemplate.exchange(eq(TASK_URL), eq(HttpMethod.GET), any(), eq(String.class)))
                .thenReturn(ResponseEntity.status(200).body("{\"status\":\"completed\"}"));
        when(restTemplate.exchange(eq(RESULT_URL), eq(HttpMethod.GET), any(), eq(String.class)))
                .thenReturn(ResponseEntity.status(200).body(
                        "{\"results\":{\"report\":{\"content_list\":[{\"type\":\"text\",\"page_idx\":0,"
                                + "\"text\":\"x\"}],\"images\":\"not-an-object\"}}}"));

        // when
        MineruClient.ParsedResult parsed = client.parse(new byte[]{1, 2, 3}, "report.pdf");

        // then
        assertEquals(1, parsed.results().get("report").size());
        assertTrue(parsed.images().get("report").isEmpty(), "images 非对象时应归一为空映射");
    }

    /**
     * 场景：创建任务返回 400（不支持的文件类型）。
     * 预期：抛出 {@link IllegalArgumentException} 且不再发起后续轮询。
     */
    @Test
    void should_throwException_when_parse_given_createStatus400() {
        // given
        when(restTemplate.exchange(eq(TASKS_URL), eq(HttpMethod.POST), any(), eq(String.class)))
                .thenReturn(ResponseEntity.status(400).build());

        // when / then
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> client.parse(new byte[]{1}, "bad.exe"));
        assertEquals("不支持的文件类型", exception.getMessage());
        verify(restTemplate, never()).exchange(anyString(), eq(HttpMethod.GET), any(), eq(String.class));
    }

    /**
     * 场景：创建任务返回 500（服务端异常）。
     * 预期：抛出 {@link IllegalStateException} 附带 HTTP 状态码；202 响应缺少 task_id 同样视为失败。
     */
    @Test
    void should_throwException_when_createTask_given_unexpectedStatusOrMissingTaskId() {
        // given
        when(restTemplate.exchange(eq(TASKS_URL), eq(HttpMethod.POST), any(), eq(String.class)))
                .thenReturn(ResponseEntity.status(500).build());

        // when / then
        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> client.parse(new byte[]{1}, "report.pdf"));
        assertEquals("创建 MinerU 任务失败，HTTP 500", exception.getMessage());
    }

    /**
     * 场景：创建任务 202 但响应体缺少 task_id（契约破坏）。
     * 预期：抛出 {@link IllegalStateException} 提示缺 task_id。
     */
    @Test
    void should_throwException_when_createTask_given_missingTaskId() {
        // given
        when(restTemplate.exchange(eq(TASKS_URL), eq(HttpMethod.POST), any(), eq(String.class)))
                .thenReturn(ResponseEntity.status(202).body("{\"status_url\":\"\"}"));

        // when / then
        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> client.parse(new byte[]{1}, "report.pdf"));
        assertEquals("创建 MinerU 任务响应缺少 task_id", exception.getMessage());
    }

    /**
     * 场景：轮询任务状态返回 404（任务失联，可能已清理或服务重启）。
     * 预期：抛出 {@link IllegalStateException} 提示失联。
     */
    @Test
    void should_throwException_when_parse_given_pollStatus404() {
        // given
        when(restTemplate.exchange(eq(TASKS_URL), eq(HttpMethod.POST), any(), eq(String.class)))
                .thenReturn(ResponseEntity.status(202).body("{\"task_id\":\"t1\"}"));
        when(restTemplate.exchange(eq(TASK_URL), eq(HttpMethod.GET), any(), eq(String.class)))
                .thenReturn(ResponseEntity.status(404).build());

        // when / then
        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> client.parse(new byte[]{1}, "report.pdf"));
        assertEquals("任务失联（可能已清理或服务重启）", exception.getMessage());
    }

    /**
     * 场景：轮询任务状态返回 503（任务管理器不可用）。
     * 预期：抛出 {@link IllegalStateException} 提示管理器不可用。
     */
    @Test
    void should_throwException_when_parse_given_pollStatus503() {
        // given
        when(restTemplate.exchange(eq(TASKS_URL), eq(HttpMethod.POST), any(), eq(String.class)))
                .thenReturn(ResponseEntity.status(202).body("{\"task_id\":\"t1\"}"));
        when(restTemplate.exchange(eq(TASK_URL), eq(HttpMethod.GET), any(), eq(String.class)))
                .thenReturn(ResponseEntity.status(503).build());

        // when / then
        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> client.parse(new byte[]{1}, "report.pdf"));
        assertEquals("任务管理器不可用", exception.getMessage());
    }

    /**
     * 场景：轮询到任务状态为 failed（解析失败）。
     * 预期：抛出 {@link IllegalStateException} 提示解析失败。
     */
    @Test
    void should_throwException_when_parse_given_failedStatus() {
        // given
        when(restTemplate.exchange(eq(TASKS_URL), eq(HttpMethod.POST), any(), eq(String.class)))
                .thenReturn(ResponseEntity.status(202).body("{\"task_id\":\"t1\"}"));
        when(restTemplate.exchange(eq(TASK_URL), eq(HttpMethod.GET), any(), eq(String.class)))
                .thenReturn(ResponseEntity.status(200).body("{\"status\":\"failed\"}"));

        // when / then
        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> client.parse(new byte[]{1}, "report.pdf"));
        assertEquals("MinerU 任务解析失败", exception.getMessage());
    }

    /**
     * 场景：轮询 completed 后拉取结果返回 409（冲突，附 error 字段）。
     * 预期：抛出 {@link IllegalStateException}，消息携带响应 error。
     */
    @Test
    void should_throwException_when_parse_given_resultStatus409() {
        // given
        when(restTemplate.exchange(eq(TASKS_URL), eq(HttpMethod.POST), any(), eq(String.class)))
                .thenReturn(ResponseEntity.status(202).body("{\"task_id\":\"t1\"}"));
        when(restTemplate.exchange(eq(TASK_URL), eq(HttpMethod.GET), any(), eq(String.class)))
                .thenReturn(ResponseEntity.status(200).body("{\"status\":\"completed\"}"));
        when(restTemplate.exchange(eq(RESULT_URL), eq(HttpMethod.GET), any(), eq(String.class)))
                .thenReturn(ResponseEntity.status(409).body("{\"error\":\"conflict-detected\"}"));

        // when / then
        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> client.parse(new byte[]{1}, "report.pdf"));
        assertEquals("拉取 MinerU 结果冲突: conflict-detected", exception.getMessage());
    }

    /**
     * 场景：轮询到结果但 content_list 缺失或为空（契约破坏）。
     * 预期：抛出 {@link IllegalStateException} 提示缺少 content_list。
     */
    @Test
    void should_throwException_when_parse_given_resultMissingContentList() {
        // given
        when(restTemplate.exchange(eq(TASKS_URL), eq(HttpMethod.POST), any(), eq(String.class)))
                .thenReturn(ResponseEntity.status(202).body("{\"task_id\":\"t1\"}"));
        when(restTemplate.exchange(eq(TASK_URL), eq(HttpMethod.GET), any(), eq(String.class)))
                .thenReturn(ResponseEntity.status(200).body("{\"status\":\"completed\"}"));
        when(restTemplate.exchange(eq(RESULT_URL), eq(HttpMethod.GET), any(), eq(String.class)))
                .thenReturn(ResponseEntity.status(200).body(
                        "{\"results\":{\"report\":{\"content_list\":[]}}}"));

        // when / then
        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> client.parse(new byte[]{1}, "report.pdf"));
        assertEquals("MinerU 结果缺少 content_list", exception.getMessage());
    }

    /**
     * 场景：任务先 pending、下一轮才 completed（轮询不设总时长上限，多轮等待由任务终态收敛）。
     * 预期：持续轮询直至完成后正常返回解析结果；间隔需临时改小并在用例结束后恢复。
     */
    @Test
    void should_keepPollingUntilCompleted_when_parse_given_statusPendingThenCompleted() {
        // given
        when(restTemplate.exchange(eq(TASKS_URL), eq(HttpMethod.POST), any(), eq(String.class)))
                .thenReturn(ResponseEntity.status(202).body("{\"task_id\":\"t1\"}"));
        when(restTemplate.exchange(eq(TASK_URL), eq(HttpMethod.GET), any(), eq(String.class)))
                .thenReturn(ResponseEntity.status(200).body("{\"status\":\"pending\"}"),
                        ResponseEntity.status(200).body("{\"status\":\"completed\"}"));
        when(restTemplate.exchange(eq(RESULT_URL), eq(HttpMethod.GET), any(), eq(String.class)))
                .thenReturn(ResponseEntity.status(200).body(
                        "{\"results\":{\"report\":{\"content_list\":"
                                + "[{\"type\":\"text\",\"page_idx\":0,\"text\":\"hello\"}]}}}"));
        shrinkPollInterval();

        // when
        MineruClient.ParsedResult parsed;
        try {
            parsed = client.parse(new byte[]{1}, "report.pdf");
        } finally {
            restorePollInterval();
        }

        // then：两轮状态查询后收敛为 completed，结果正常返回
        verify(restTemplate, times(2)).exchange(eq(TASK_URL), eq(HttpMethod.GET), any(), eq(String.class));
        assertEquals(1, parsed.results().get("report").size());
        assertEquals("hello", parsed.results().get("report").get(0).fields().get("text"));
    }

    /**
     * 场景：状态已 completed 但结果先 202（未就绪）再 200——202 分支应回到轮询循环而非判死。
     * 预期：继续轮询直至结果就绪，正常返回解析结果。
     */
    @Test
    void should_repollUntilResultReady_when_parse_given_resultNotReadyFirst() {
        // given
        when(restTemplate.exchange(eq(TASKS_URL), eq(HttpMethod.POST), any(), eq(String.class)))
                .thenReturn(ResponseEntity.status(202).body("{\"task_id\":\"t1\"}"));
        when(restTemplate.exchange(eq(TASK_URL), eq(HttpMethod.GET), any(), eq(String.class)))
                .thenReturn(ResponseEntity.status(200).body("{\"status\":\"completed\"}"));
        when(restTemplate.exchange(eq(RESULT_URL), eq(HttpMethod.GET), any(), eq(String.class)))
                .thenReturn(ResponseEntity.status(202).build(),
                        ResponseEntity.status(200).body(
                                "{\"results\":{\"report\":{\"content_list\":"
                                        + "[{\"type\":\"text\",\"page_idx\":0,\"text\":\"ready\"}]}}}"));
        shrinkPollInterval();

        // when
        MineruClient.ParsedResult parsed;
        try {
            parsed = client.parse(new byte[]{1}, "report.pdf");
        } finally {
            restorePollInterval();
        }

        // then：未就绪轮与就绪轮各一次状态查询 + 两次结果拉取，最终正常返回
        verify(restTemplate, times(2)).exchange(eq(TASK_URL), eq(HttpMethod.GET), any(), eq(String.class));
        verify(restTemplate, times(2)).exchange(eq(RESULT_URL), eq(HttpMethod.GET), any(), eq(String.class));
        assertEquals("ready", parsed.results().get("report").get(0).fields().get("text"));
    }

    /**
     * 场景：pipeline 后端解析参数映射。
     * 预期：创建请求携带 parse_method 与全部 lang_list 条目，不携带 model_server_url / effort。
     */
    @Test
    void should_submitParseMethodAndLangList_when_createTask_given_pipelineBackend() {
        // given
        MineruClient pipelineClient = new MineruClient(API_BASE_URL, "pipeline", "http://models:8000", "ocr",
                List.of("ch", "en"), "high", restTemplate);
        when(restTemplate.exchange(eq(TASKS_URL), eq(HttpMethod.POST), any(), eq(String.class)))
                .thenReturn(ResponseEntity.status(202).body("{\"task_id\":\"t1\"}"));
        when(restTemplate.exchange(eq(TASK_URL), eq(HttpMethod.GET), any(), eq(String.class)))
                .thenReturn(ResponseEntity.status(200).body("{\"status\":\"completed\"}"));
        when(restTemplate.exchange(eq(RESULT_URL), eq(HttpMethod.GET), any(), eq(String.class)))
                .thenReturn(ResponseEntity.status(200).body(
                        "{\"results\":{\"report\":{\"content_list\":[{\"type\":\"text\",\"page_idx\":0,\"text\":\"x\"}]}}}"));

        // when
        pipelineClient.parse(new byte[]{1}, "report.pdf");

        // then
        LinkedMultiValueMap<String, Object> body = captureCreateRequest();
        assertEquals(List.of("ocr"), body.get("parse_method"), "pipeline 后端应提交 parse_method");
        assertEquals(List.of("ch", "en"), body.get("lang_list"), "pipeline 后端应逐条提交全部语言");
        assertNull(body.get("model_server_url"), "pipeline 后端不应提交 model_server_url");
        assertNull(body.get("effort"), "仅 hybrid-engine 后端才提交 effort");
    }

    /**
     * 场景：*-http-client 后端参数映射。
     * 预期：创建请求携带 model_server_url，不携带 lang_list / parse_method / effort。
     */
    @Test
    void should_submitModelServerUrl_when_createTask_given_httpClientBackend() {
        // given
        MineruClient httpClientClient = new MineruClient(API_BASE_URL, "vlm-http-client", "http://models:8000",
                "ocr", List.of("ch"), "high", restTemplate);
        when(restTemplate.exchange(eq(TASKS_URL), eq(HttpMethod.POST), any(), eq(String.class)))
                .thenReturn(ResponseEntity.status(202).body("{\"task_id\":\"t1\"}"));
        when(restTemplate.exchange(eq(TASK_URL), eq(HttpMethod.GET), any(), eq(String.class)))
                .thenReturn(ResponseEntity.status(200).body("{\"status\":\"completed\"}"));
        when(restTemplate.exchange(eq(RESULT_URL), eq(HttpMethod.GET), any(), eq(String.class)))
                .thenReturn(ResponseEntity.status(200).body(
                        "{\"results\":{\"report\":{\"content_list\":[{\"type\":\"text\",\"page_idx\":0,\"text\":\"x\"}]}}}"));

        // when
        httpClientClient.parse(new byte[]{1}, "report.pdf");

        // then
        LinkedMultiValueMap<String, Object> body = captureCreateRequest();
        assertEquals(List.of("http://models:8000"), body.get("model_server_url"),
                "*-http-client 后端应提交 model_server_url");
        assertNull(body.get("lang_list"), "*-http-client 后端不应提交 lang_list");
        assertNull(body.get("parse_method"), "*-http-client 后端不应提交 parse_method");
        assertNull(body.get("effort"), "*-http-client 后端不应提交 effort");
    }

    /**
     * 场景：构造时 API 地址为空白。
     * 预期：构造即抛 {@link IllegalArgumentException}。
     */
    @Test
    void should_throwException_when_constructor_given_blankApiBaseUrl() {
        // given / when / then
        assertThrows(IllegalArgumentException.class, () -> new MineruClient("  ", restTemplate));
    }

    /**
     * 捕获创建任务请求体，验证表单字段映射。
     *
     * @return multipart 表单体
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private LinkedMultiValueMap<String, Object> captureCreateRequest() {
        ArgumentCaptor<HttpEntity> captor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(eq(TASKS_URL), eq(HttpMethod.POST), captor.capture(), eq(String.class));
        return (LinkedMultiValueMap<String, Object>) captor.getValue().getBody();
    }

    /**
     * 备份并临时改小轮询间隔，以便多轮轮询单测快速完成（不设总时长上限，无超时分支可触发）。
     */
    private void shrinkPollInterval() {
        originalPollIntervalMs = MineruClient.TASK_POLL_INTERVAL_MS;
        MineruClient.TASK_POLL_INTERVAL_MS = POLL_INTERVAL_TINY_MS;
    }

    /**
     * 将轮询间隔恢复为测试前的原始值。
     */
    private void restorePollInterval() {
        MineruClient.TASK_POLL_INTERVAL_MS = originalPollIntervalMs;
    }
}