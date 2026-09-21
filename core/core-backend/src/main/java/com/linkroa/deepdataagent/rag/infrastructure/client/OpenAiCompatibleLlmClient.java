package com.linkroa.deepdataagent.rag.infrastructure.client;

import com.linkroa.deepdataagent.rag.domain.port.LlmChatRequest;
import com.linkroa.deepdataagent.rag.domain.port.LlmChatResult;
import com.linkroa.deepdataagent.rag.domain.port.LlmClient;
import com.linkroa.deepdataagent.rag.domain.port.LlmImage;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelCreationContext;
import io.agentscope.core.model.ModelRegistry;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.function.Function;

/**
 * OpenAI 兼容对话客户端（无缓存裸实现，经 AgentScope 框架模型能力真实发起调用）。
 * <p>生产链路默认被 {@link CachingLlmClient}（{@code @Primary}）包装；本类只负责
 * 凭证解析 → 框架模型解析 → 消息组装 → 同步收口 → 回复提取。模型解析与 runtime BC
 * 的 {@code AgentscopeHarnessAgentFactory} 同范式：{@link ModelRegistry#resolve(String,
 * ModelCreationContext)} 注入台账凭证与端点，天然支持提供方前缀路由（如
 * {@code dashscope:qwen-plus}）。</p>
 * <p>带图片的请求以 {@link TextBlock} + {@link ImageBlock}
 * （{@link Base64Source} 承载 base64）组装多模态 user 消息，图片仅进入 user message，
 * 不污染 system；对外端口与结果语义（文本聚合、total tokens 记账）与裸 HTTP 通道时期一致。</p>
 * <p>刻意<b>不下发</b> {@code user}/{@code n} 等破坏确定性的字段、固定 {@code stream=false}
 * 且关闭框架重试（{@code maxAttempts=1}），保持「同 prompt 同结果、失败即降级」的既有语义。</p>
 *
 * @author DeepDataAgent
 */
@Component("openAiCompatibleLlmClient")
public class OpenAiCompatibleLlmClient implements LlmClient {

    /** 单次调用超时（摄入为后台任务，放宽到分钟级；经框架 ExecutionConfig 承载） */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(180);

    /** 同步收口外框上限（略大于框架内部超时，防止响应流逃逸导致调用线程悬挂） */
    private static final Duration BLOCK_TIMEOUT = REQUEST_TIMEOUT.plusSeconds(5);

    private final ModelProfileAccess modelProfileAccess;

    /** 框架模型解析缝（单测注入 Mock；生产走 AgentScope 模型注册表） */
    private final Function<ModelProfileAccess.ResolvedEndpoint, Model> modelFactory;

    /**
     * 构造对话客户端（生产装配入口）。
     *
     * @param modelProfileAccess 模型配置解析端口
     */
    @Autowired
    public OpenAiCompatibleLlmClient(ModelProfileAccess modelProfileAccess) {
        this(modelProfileAccess, OpenAiCompatibleLlmClient::resolveByRegistry);
    }

    /**
     * 测试缝构造器：注入指定模型解析函数。
     *
     * @param modelProfileAccess 模型配置解析端口
     * @param modelFactory       端点 → 框架模型解析函数
     */
    OpenAiCompatibleLlmClient(ModelProfileAccess modelProfileAccess,
                              Function<ModelProfileAccess.ResolvedEndpoint, Model> modelFactory) {
        this.modelProfileAccess = modelProfileAccess;
        this.modelFactory = modelFactory;
    }

    /**
     * 经 AgentScope 模型注册表解析框架模型（与 runtime BC 装配范式一致）。
     * <p>台账凭证/端点经 {@link ModelCreationContext} 注入；两者皆空时退化为注册表默认解析。</p>
     *
     * @param endpoint 已解析的模型端点
     * @return 框架模型实例
     */
    static Model resolveByRegistry(ModelProfileAccess.ResolvedEndpoint endpoint) {
        if (StringUtils.isBlank(endpoint.apiKey()) && StringUtils.isBlank(endpoint.baseUrl())) {
            return ModelRegistry.resolve(endpoint.modelName());
        }
        ModelCreationContext.Builder context = ModelCreationContext.builder();
        if (StringUtils.isNotBlank(endpoint.apiKey())) {
            context.apiKey(endpoint.apiKey());
        }
        if (StringUtils.isNotBlank(endpoint.baseUrl())) {
            context.baseUrl(endpoint.baseUrl());
        }
        return ModelRegistry.resolve(endpoint.modelName(), context.build());
    }

    @Override
    public LlmChatResult chat(LlmChatRequest request) {
        if (ObjectUtils.isEmpty(request)) {
            throw new IllegalArgumentException("LLM 请求不能为空");
        }
        ModelProfileAccess.ResolvedEndpoint endpoint = modelProfileAccess.resolve(request.modelProfileId());
        Model model = modelFactory.apply(endpoint);
        List<ChatResponse> responses;
        try {
            responses = model.stream(buildMessages(request), List.of(), buildOptions(request))
                    .collectList()
                    .block(BLOCK_TIMEOUT);
        } catch (RuntimeException e) {
            throw new RuntimeException("模型接口调用失败: " + e.getMessage(), e);
        }
        return toChatResult(responses);
    }

    /**
     * 组装框架消息序列：system（可空省略）+ user。
     * <p>无图时 user 为纯文本消息；有图时 user 内容块序列固定为
     * 「文本块在前、图片块按列表顺序追加」，图片以标准 Base64 编码进
     * {@link Base64Source}（mediaType 即图片 contentType）。</p>
     * <p>包级可见以便单测直接断言消息形态（不改变公开行为）。</p>
     *
     * @param request 对话请求
     * @return 框架消息列表
     */
    static List<Msg> buildMessages(LlmChatRequest request) {
        List<Msg> messages = new ArrayList<>(2);
        if (StringUtils.isNotBlank(request.systemPrompt())) {
            messages.add(Msg.builder().role(MsgRole.SYSTEM).textContent(request.systemPrompt()).build());
        }
        Msg.Builder user = Msg.builder().role(MsgRole.USER);
        List<LlmImage> images = request.images();
        if (CollectionUtils.isEmpty(images)) {
            user.textContent(request.userPrompt());
        } else {
            List<ContentBlock> blocks = new ArrayList<>(1 + images.size());
            blocks.add(TextBlock.builder().text(request.userPrompt()).build());
            for (LlmImage image : images) {
                blocks.add(ImageBlock.builder()
                        .source(Base64Source.builder()
                                .mediaType(image.contentType())
                                .data(Base64.getEncoder().encodeToString(image.content()))
                                .build())
                        .build());
            }
            user.content(blocks);
        }
        messages.add(user.build());
        return messages;
    }

    /**
     * 组装生成选项：非流式、超时 180s、关闭重试（保持「失败即降级」现语义）、
     * 温度仅在显式给出时下发（缺省沿用提供方默认值）。
     * <p>包级可见以便单测直接断言选项形态。</p>
     *
     * @param request 对话请求
     * @return 框架生成选项
     */
    static GenerateOptions buildOptions(LlmChatRequest request) {
        GenerateOptions.Builder options = GenerateOptions.builder()
                .stream(false)
                .executionConfig(ExecutionConfig.builder()
                        .timeout(REQUEST_TIMEOUT)
                        .maxAttempts(1)
                        .build());
        if (ObjectUtils.isNotEmpty(request.temperature())) {
            options.temperature(request.temperature());
        }
        return options.build();
    }

    /**
     * 聚合框架响应：文本按分片顺序拼接各 {@link TextBlock}；token 取最后一个非空
     * usage 的 totalTokens（缺失记 0，与裸通道时代 {@code asInt(0)} 语义一致）。
     * <p>包级可见以便单测覆盖聚合与空响应分支。</p>
     *
     * @param responses 框架响应分片列表（可空，表示响应流无数据）
     * @return 对话结果
     * @throws RuntimeException 响应为空（等价于旧「模型响应缺少 choices」协议异常）
     */
    static LlmChatResult toChatResult(List<ChatResponse> responses) {
        if (CollectionUtils.isEmpty(responses)) {
            throw new RuntimeException("模型响应缺少 choices: 空响应流");
        }
        StringBuilder text = new StringBuilder();
        int totalTokens = 0;
        for (ChatResponse response : responses) {
            List<ContentBlock> blocks = response.getContent();
            if (CollectionUtils.isNotEmpty(blocks)) {
                for (ContentBlock block : blocks) {
                    if (block instanceof TextBlock textBlock) {
                        text.append(StringUtils.defaultString(textBlock.getText()));
                    }
                }
            }
            ChatUsage usage = response.getUsage();
            if (ObjectUtils.isNotEmpty(usage) && usage.getTotalTokens() > 0) {
                totalTokens = usage.getTotalTokens();
            }
        }
        return new LlmChatResult(text.toString(), totalTokens);
    }
}
