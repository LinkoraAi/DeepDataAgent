package com.linkroa.deepdataagent.rag.infrastructure.client;

import com.linkroa.deepdataagent.rag.domain.enums.CacheType;
import com.linkroa.deepdataagent.rag.domain.port.LlmChatRequest;
import com.linkroa.deepdataagent.rag.domain.port.LlmChatResult;
import com.linkroa.deepdataagent.rag.domain.port.LlmImage;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.Source;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelException;
import io.agentscope.core.model.transport.HttpRequest;
import io.agentscope.core.model.transport.HttpResponse;
import io.agentscope.core.model.transport.HttpTransport;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link OpenAiCompatibleLlmClient} 单元测试（传输通道重写后）。
 * <p>报文形态断言基线由「逐字节 JSON」迁移为「框架消息/选项对象」：
 * ① {@code buildMessages} 钉死 system/user 角色、无图文本形态、有图时
 * 「文本块在前、图片块按序追加」且 base64 与 mediaType 正确；
 * ② {@code buildOptions} 钉死 {@code stream=false}、温度仅显式给出时下发、
 * 超时 180s 且 {@code maxAttempts=1}（关闭重试）；
 * ③ {@code toChatResult} 钉死分片聚合与「最后一个非空 usage 记 token」语义。</p>
 * <p>远程真实 HTTP 不触达：常规用例 Mock {@link Model}；另有一例以真实
 * {@link OpenAIChatModel} + 捕获式 {@link HttpTransport} 钉死出站 OpenAI 报文
 * （图片必须序列化为 {@code image_url} data URI 形态）。</p>
 *
 * @author DeepDataAgent
 */
@ExtendWith(MockitoExtension.class)
class OpenAiCompatibleLlmClientTest {

    /** 解析后的模型名称 */
    private static final String MODEL_NAME = "gpt-4o-mini";

    /** 测试系统提示词 */
    private static final String SYSTEM_PROMPT = "SYS";

    /** 测试用户提示词 */
    private static final String USER_PROMPT = "USER PROMPT";

    /** 测试 profileId */
    private static final String PROFILE_ID = "chat-profile-1";

    /** 真实模型出站断言用的对话补全响应报文 */
    private static final String CANNED_COMPLETION = "{\"id\":\"c1\",\"object\":\"chat.completion\","
            + "\"model\":\"" + MODEL_NAME + "\",\"choices\":[{\"index\":0,"
            + "\"message\":{\"role\":\"assistant\",\"content\":\"HELLO\"},\"finish_reason\":\"stop\"}],"
            + "\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":2,\"total_tokens\":7}}";

    /** 模型配置解析端口 Mock */
    @Mock
    private ModelProfileAccess modelProfileAccess;

    /** 框架模型 Mock（编排响应流） */
    @Mock
    private Model model;

    /**
     * 场景：纯文本请求（含系统提示词）。
     * 预期：消息序列为 SYSTEM + USER，且均为纯文本形态。
     */
    @Test
    void should_produceSystemAndUserTextMessages_when_buildMessages_given_plainTextRequest() {
        // given / when
        List<Msg> messages = OpenAiCompatibleLlmClient.buildMessages(request(SYSTEM_PROMPT, USER_PROMPT, null));

        // then
        assertEquals(2, messages.size());
        assertEquals(MsgRole.SYSTEM, messages.get(0).getRole());
        assertEquals(SYSTEM_PROMPT, messages.get(0).getTextContent());
        assertEquals(MsgRole.USER, messages.get(1).getRole());
        assertEquals(USER_PROMPT, messages.get(1).getTextContent());
        assertFalse(messages.get(1).hasContentBlocks(ImageBlock.class), "无图请求不得含图片块");
    }

    /**
     * 场景：系统提示词为空白。
     * 预期：省略 SYSTEM 消息，仅保留 USER（温度缺省沿用提供方默认值）。
     */
    @Test
    void should_omitSystemMessage_when_buildMessages_given_blankSystemPrompt() {
        // given / when
        List<Msg> messages = OpenAiCompatibleLlmClient.buildMessages(request("  ", USER_PROMPT, null));

        // then
        assertEquals(1, messages.size());
        assertEquals(MsgRole.USER, messages.get(0).getRole());
    }

    /**
     * 场景：两图多模态请求（含系统提示词）。
     * 预期：user 内容块序列固定「文本块在前、图片块按列表顺序追加」，base64 与 mediaType 正确；
     * system 消息保持纯文本、不含图片。
     */
    @Test
    void should_keepTextFirstAndImageOrderInUserMessage_when_buildMessages_given_twoImages() {
        // given
        byte[] first = new byte[]{(byte) 0x89, 'P', 'N', 'G'};
        byte[] second = new byte[]{2, 2};
        LlmChatRequest request = new LlmChatRequest(1L, PROFILE_ID, SYSTEM_PROMPT, USER_PROMPT, null,
                CacheType.ANSWER, List.of(new LlmImage("image/png", first), new LlmImage("image/webp", second)));

        // when
        List<Msg> messages = OpenAiCompatibleLlmClient.buildMessages(request);

        // then
        assertFalse(messages.get(0).hasContentBlocks(ImageBlock.class), "system 消息不参与多模态组装");
        List<ContentBlock> blocks = messages.get(1).getContent();
        assertEquals(3, blocks.size());
        assertInstanceOf(TextBlock.class, blocks.get(0));
        assertEquals(USER_PROMPT, ((TextBlock) blocks.get(0)).getText());
        Base64Source firstSource = sourceOf(blocks.get(1));
        assertEquals("image/png", firstSource.getMediaType());
        assertEquals(Base64.getEncoder().encodeToString(first), firstSource.getData());
        Base64Source secondSource = sourceOf(blocks.get(2));
        assertEquals("image/webp", secondSource.getMediaType());
        assertEquals(Base64.getEncoder().encodeToString(second), secondSource.getData());
    }

    /**
     * 场景：生成选项组装。
     * 预期：固定非流式、超时 180s、maxAttempts=1（关闭重试）、温度仅显式给出时下发。
     */
    @Test
    void should_disableStreamAndRetry_when_buildOptions_given_explicitTemperature() {
        // given / when
        GenerateOptions options = OpenAiCompatibleLlmClient.buildOptions(request(SYSTEM_PROMPT, USER_PROMPT, 0.2));

        // then
        assertEquals(Boolean.FALSE, options.getStream());
        assertEquals(Double.valueOf(0.2), options.getTemperature());
        ExecutionConfig executionConfig = options.getExecutionConfig();
        assertEquals(Duration.ofSeconds(180), executionConfig.getTimeout());
        assertEquals(Integer.valueOf(1), executionConfig.getMaxAttempts());
    }

    /**
     * 场景：温度缺省。
     * 预期：选项不携带温度（沿用提供方默认值）。
     */
    @Test
    void should_omitTemperature_when_buildOptions_given_nullTemperature() {
        // given / when
        GenerateOptions options = OpenAiCompatibleLlmClient.buildOptions(request(null, USER_PROMPT, null));

        // then
        assertNull(options.getTemperature(), "温度缺省时不得下发");
    }

    /**
     * 场景：多分片响应聚合。
     * 预期：文本按分片顺序拼接，token 取最后一个非空 usage。
     */
    @Test
    void should_concatenateChunksAndTakeLastUsage_when_toChatResult_given_multiChunkResponses() {
        // given
        List<ChatResponse> responses = List.of(
                chatResponse("HE", new ChatUsage(5, 2, 0.0)),
                chatResponse("LLO", null),
                chatResponse("!", new ChatUsage(8, 4, 0.0)));

        // when
        LlmChatResult result = OpenAiCompatibleLlmClient.toChatResult(responses);

        // then
        assertEquals("HELLO!", result.text());
        assertEquals(12, result.totalTokens());
    }

    /**
     * 场景：所有分片均无 usage。
     * 预期：token 记 0（与裸通道时代 asInt(0) 语义一致）。
     */
    @Test
    void should_returnZeroTokens_when_toChatResult_given_usageAbsent() {
        // given / when
        LlmChatResult result = OpenAiCompatibleLlmClient.toChatResult(List.of(chatResponse("X", null)));

        // then
        assertEquals(0, result.totalTokens());
    }

    /**
     * 场景：响应流无任何数据（block 收口后空列表）。
     * 预期：抛协议异常（等价旧「模型响应缺少 choices」）。
     */
    @Test
    void should_throwRuntimeException_when_toChatResult_given_emptyResponses() {
        // given / when / then
        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> OpenAiCompatibleLlmClient.toChatResult(List.of()));
        assertTrue(exception.getMessage().contains("choices"), "异常信息需保持既有口径: " + exception.getMessage());
    }

    /**
     * 场景：完整对话主流程（Mock 模型返回单帧响应）。
     * 预期：端点进入解析缝、消息与选项原样传给框架模型、结果聚合正确。
     */
    @Test
    void should_returnAggregatedResult_when_chat_given_successfulModelStream() {
        // given
        when(modelProfileAccess.resolve(PROFILE_ID)).thenReturn(endpoint());
        when(model.stream(anyList(), anyList(), any(GenerateOptions.class)))
                .thenReturn(Flux.just(chatResponse("HELLO", new ChatUsage(5, 2, 0.0))));
        OpenAiCompatibleLlmClient client = newClient();

        // when
        LlmChatResult result = client.chat(request(SYSTEM_PROMPT, USER_PROMPT, null));

        // then
        assertEquals("HELLO", result.text());
        assertEquals(7, result.totalTokens());
        ArgumentCaptor<List<Msg>> messagesCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<GenerateOptions> optionsCaptor = ArgumentCaptor.forClass(GenerateOptions.class);
        verify(model).stream(messagesCaptor.capture(), anyList(), optionsCaptor.capture());
        assertEquals(MsgRole.SYSTEM, messagesCaptor.getValue().get(0).getRole());
        assertEquals(Boolean.FALSE, optionsCaptor.getValue().getStream());
    }

    /**
     * 场景：框架模型流以异常终止（提供方调用失败）。
     * 预期：折算为既有口径 {@code RuntimeException("模型接口调用失败: ...")}，供降级与任务失败语义复用。
     */
    @Test
    void should_wrapModelErrorWithLegacyPrefix_when_chat_given_modelStreamFails() {
        // given
        when(modelProfileAccess.resolve(PROFILE_ID)).thenReturn(endpoint());
        when(model.stream(anyList(), anyList(), any(GenerateOptions.class)))
                .thenReturn(Flux.error(new ModelException("provider exploded", MODEL_NAME, "openai")));
        OpenAiCompatibleLlmClient client = newClient();

        // when
        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> client.chat(request(null, USER_PROMPT, null)));

        // then
        assertTrue(exception.getMessage().startsWith("模型接口调用失败: "),
                "异常消息需保持既有前缀: " + exception.getMessage());
    }

    /**
     * 场景：block 收口超时（以超时同款异常形态模拟，不占真实 185s 墙钟）。
     * 预期：折算为既有口径运行时异常，调用线程不悬挂。
     */
    @Test
    void should_wrapBlockingTimeout_when_chat_given_streamNeverTerminates() {
        // given
        when(modelProfileAccess.resolve(PROFILE_ID)).thenReturn(endpoint());
        when(model.stream(anyList(), anyList(), any(GenerateOptions.class)))
                .thenReturn(Flux.error(new IllegalStateException("Timeout on blocking read for 185000000000 NS")));
        OpenAiCompatibleLlmClient client = newClient();

        // when
        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> client.chat(request(null, USER_PROMPT, null)));

        // then
        assertTrue(exception.getMessage().contains("Timeout on blocking read"),
                "超时信息需透传: " + exception.getMessage());
    }

    /**
     * 场景：模型解析缝抛异常（如注册表无法解析模型名）。
     * 预期：运行时异常上抛，不吞错。
     */
    @Test
    void should_propagateResolveFailure_when_chat_given_modelFactoryThrows() {
        // given
        when(modelProfileAccess.resolve(PROFILE_ID)).thenReturn(endpoint());
        OpenAiCompatibleLlmClient client = new OpenAiCompatibleLlmClient(modelProfileAccess, resolved -> {
            throw new IllegalStateException("无法解析模型: " + resolved.modelName());
        });

        // when / then
        assertThrows(IllegalStateException.class, () -> client.chat(request(null, USER_PROMPT, null)));
    }

    /**
     * 场景：入参请求为 null。
     * 预期：快速失败抛 {@link IllegalArgumentException}，不触达模型解析端口。
     */
    @Test
    void should_throwIllegalArgument_when_chat_given_nullRequest() {
        // given
        OpenAiCompatibleLlmClient client = newClient();

        // when / then
        assertThrows(IllegalArgumentException.class, () -> client.chat(null));
        verifyNoInteractions(modelProfileAccess);
    }

    /**
     * 场景（钉死点）：真实 {@link OpenAIChatModel} + 捕获式框架传输发起带图对话。
     * 预期：出站 URL 为版本化 base 去重拼接的 chat/completions，请求体含
     * {@code image_url} data URI 形态与文本分片；响应聚合为 HELLO / 7 tokens。
     */
    @Test
    void should_produceOpenAiWireBodyWithImageUrl_when_chat_given_realChatModel() {
        // given
        CapturingHttpTransport capturingTransport = new CapturingHttpTransport(
                HttpResponse.builder().statusCode(200).body(CANNED_COMPLETION).build());
        Model chatModel = OpenAIChatModel.builder()
                .apiKey("sk-x")
                .baseUrl("https://api.example/v1")
                .modelName(MODEL_NAME)
                .stream(false)
                .httpTransport(capturingTransport)
                .build();
        when(modelProfileAccess.resolve(PROFILE_ID)).thenReturn(endpoint());
        OpenAiCompatibleLlmClient client = new OpenAiCompatibleLlmClient(modelProfileAccess, resolved -> chatModel);
        byte[] png = new byte[]{(byte) 0x89, 'P', 'N', 'G'};
        LlmChatRequest request = new LlmChatRequest(1L, PROFILE_ID, SYSTEM_PROMPT, USER_PROMPT, null,
                CacheType.ANSWER, List.of(new LlmImage("image/png", png)));

        // when
        LlmChatResult result = client.chat(request);

        // then：URL 归一化（版本段不重复）与多模态出站形态由框架 formatter 承担
        HttpRequest httpRequest = capturingTransport.captured;
        assertEquals("https://api.example/v1/chat/completions", httpRequest.getUrl());
        assertTrue(httpRequest.getBody().contains("\"image_url\""), "图片须序列化为 image_url 形态");
        assertTrue(httpRequest.getBody().contains("data:image/png;base64," + Base64.getEncoder().encodeToString(png)),
                "图片以 data URI 内联 base64");
        assertFalse(httpRequest.getBody().contains("\"user\":"), "不得下发破坏确定性的 user 字段");
        assertFalse(httpRequest.getBody().contains("\"n\":"), "不得下发破坏确定性的 n 字段");
        assertFalse(httpRequest.getBody().contains("\"stream\":true"), "非流式请求不得声明 stream=true");
        assertEquals("HELLO", result.text());
        assertEquals(7, result.totalTokens());
    }

    /**
     * 构造被测客户端（模型解析缝注入 Mock 模型）。
     */
    private OpenAiCompatibleLlmClient newClient() {
        return new OpenAiCompatibleLlmClient(modelProfileAccess, resolved -> model);
    }

    /**
     * 构造对话请求。
     */
    private static LlmChatRequest request(String systemPrompt, String userPrompt, Double temperature) {
        return new LlmChatRequest(1L, PROFILE_ID, systemPrompt, userPrompt, temperature, CacheType.ANSWER);
    }

    /**
     * 构造解析后的模型端点。
     */
    private static ModelProfileAccess.ResolvedEndpoint endpoint() {
        return new ModelProfileAccess.ResolvedEndpoint("https://api.example/v1", "sk-x", MODEL_NAME, null);
    }

    /**
     * 构造带文本块与可选 usage 的框架响应分片。
     */
    private static ChatResponse chatResponse(String text, ChatUsage usage) {
        return ChatResponse.builder()
                .content(List.of(TextBlock.builder().text(text).build()))
                .usage(usage)
                .build();
    }

    /**
     * 取图片块的 Base64 内容源。
     */
    private static Base64Source sourceOf(ContentBlock block) {
        assertInstanceOf(ImageBlock.class, block);
        Source source = ((ImageBlock) block).getSource();
        assertInstanceOf(Base64Source.class, source);
        return (Base64Source) source;
    }

    /**
     * 捕获出站请求并回放预置响应的框架传输（无真实网络）。
     */
    private static final class CapturingHttpTransport implements HttpTransport {

        /** 最近一次出站请求 */
        private HttpRequest captured;

        /** 回放响应 */
        private final HttpResponse response;

        private CapturingHttpTransport(HttpResponse response) {
            this.response = response;
        }

        @Override
        public HttpResponse execute(HttpRequest request) {
            this.captured = request;
            return response;
        }

        @Override
        public Flux<String> stream(HttpRequest request) {
            this.captured = request;
            return Flux.empty();
        }

        @Override
        public void close() {
            // 测试内无需释放
        }
    }
}
