package com.linkroa.deepdataagent.rag.infrastructure.client;

import com.linkroa.deepdataagent.agent.domain.model.ModelIndicator;
import com.linkroa.deepdataagent.rag.domain.port.LlmCallContext;
import com.linkroa.deepdataagent.rag.domain.port.LlmCallException;
import com.linkroa.deepdataagent.rag.domain.port.LlmChatRequest;
import com.linkroa.deepdataagent.rag.domain.port.LlmChatResult;
import com.linkroa.deepdataagent.rag.domain.port.LlmClient;
import com.linkroa.deepdataagent.rag.domain.port.LlmFailureKind;
import com.linkroa.deepdataagent.rag.domain.port.LlmImage;
import com.linkroa.deepdataagent.rag.domain.port.LlmStreamClient;
import com.linkroa.deepdataagent.rag.domain.port.LlmStreamDelta;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
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
 * <p>刻意<b>不下发</b> {@code user}/{@code n} 等破坏确定性的字段；固定 {@code stream=true} 流式聚合
 * （SSE 分片按到达顺序拼接为完整文本，结果与非流式等价）；固定下发
 * {@code enable_thinking=false} 全局关闭思考模式（qwen3 系端点默认开启思考，
 * 分钟级耗时根因，见 {@link OpenAiCompatibleLlmClient#PARAM_ENABLE_THINKING}）。</p>
 * <p>超时与重试统一交给 AgentScope 框架默认策略（单次 5 分钟、最多 3 次尝试、指数退避、
 * 仅对超时/连接/429/5xx 重试）；本类不再定义任何私有超时常量，也不在外层设置早于框架超时的
 * 阻塞上限。流式下框架超时的约束对象是<b>分片间静默间隔</b>（{@code Flux.timeout} 每信号重置语义）：
 * 持续产出的长回复不受总时长限制，该超时只检测死连接。每次调用结束输出一行结构化调用级日志，
 * 口径为<b>最小化字段</b>：阶段与文档定位符、模型名、输入体量（字符数）、耗时、状态、尝试次数、
 * 响应体量与 token 用量、输入 MD5 前缀；MUST NOT 输出端点主机、提示词正文与温度等敏感或超长字段
 * （回放原文经 promptMd5 关联 llm_cache），任何情况下不落凭证。</p>
 *
 * @author DeepDataAgent
 */
@Component("openAiCompatibleLlmClient")
public class OpenAiCompatibleLlmClient implements LlmClient, LlmStreamClient {

    /** 调用级日志器 */
    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleLlmClient.class);

    /** 框架默认执行配置（超时、尝试次数、退避基线的唯一来源） */
    private static final ExecutionConfig FRAMEWORK_DEFAULTS = ExecutionConfig.MODEL_DEFAULTS;

    /** 对话调用固定流式标志：生成选项与调用级日志的单一事实源 */
    private static final boolean STREAM = true;

    /** 首字时间戳未设置哨兵（尚无任何文本增量） */
    private static final long FIRST_TOKEN_UNSET = 0L;

    /** 首字延迟缺省值：无文本增量时不适用于观测 */
    private static final long NO_FIRST_TOKEN_MS = -1L;

    /** 调用形态：聚合返回（{@link #chat(LlmChatRequest)}） */
    private static final String MODE_AGGREGATE = "aggregate";

    /** 调用形态：增量直出（{@link #stream(LlmChatRequest)}） */
    private static final String MODE_STREAM = "stream";

    /**
     * OpenAI 兼容协议思考模式开关字段名（请求体 {@code enable_thinking}）。
     */
    private static final String PARAM_ENABLE_THINKING = "enable_thinking";

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
        String modelId = resolveRegistryModelId(endpoint);
        if (StringUtils.isBlank(endpoint.apiKey()) && StringUtils.isBlank(endpoint.baseUrl())) {
            return ModelRegistry.resolve(modelId);
        }
        ModelCreationContext.Builder context = ModelCreationContext.builder();
        if (StringUtils.isNotBlank(endpoint.apiKey())) {
            context.apiKey(endpoint.apiKey());
        }
        if (StringUtils.isNotBlank(endpoint.baseUrl())) {
            context.baseUrl(endpoint.baseUrl());
        }
        return ModelRegistry.resolve(modelId, context.build());
    }

    /**
     * 按 API 格式解析注册表模型标识（集成测试 D14）。
     * <p>复用领域规则 {@link ModelIndicator}：AGENTSCOPE 保持注册表裸名（如 {@code dashscope:qwen-plus}），
     * 其余格式以 {@code 小写apiFormat:modelName} 显式前缀强制对应 provider
     * （如 {@code openai:qwen3.8-flash}）。避免模型名命中其它 provider 白名单被误路由——
     * DashScope provider 的模型名白名单含 {@code qwen3.8} 前缀，会把裸名 {@code qwen3.8-flash}
     * 劫持到原生多模态端点并与含版本段的兼容 base 拼出双重版本段（400）。</p>
     * <p>已携带 provider 前缀（含 {@code :}）的模型名视为显式声明，原样返回不改写；
     * apiFormat 缺失时同样退化为裸名（向后兼容）。</p>
     *
     * @param endpoint 已解析的模型端点
     * @return 注册表模型标识（可能带 provider 前缀）
     */
    static String resolveRegistryModelId(ModelProfileAccess.ResolvedEndpoint endpoint) {
        String modelName = endpoint.modelName();
        if (StringUtils.isBlank(modelName) || modelName.contains(":")) {
            return modelName;
        }
        if (ObjectUtils.isEmpty(endpoint.apiFormat())) {
            return modelName;
        }
        return ModelIndicator.of(endpoint.apiFormat(), modelName).resolved();
    }

    @Override
    public LlmChatResult chat(LlmChatRequest request) {
        if (ObjectUtils.isEmpty(request)) {
            throw new IllegalArgumentException("LLM 请求不能为空");
        }
        ModelProfileAccess.ResolvedEndpoint endpoint = modelProfileAccess.resolve(request.modelProfileId());
        Model model = modelFactory.apply(endpoint);
        long startNanos = System.nanoTime();
        AtomicInteger retryCount = new AtomicInteger();
        List<ChatResponse> responses;
        try {
            responses = openResponseStream(request, model, retryCount)
                    .collectList()
                    .block();
        } catch (RuntimeException e) {
            LlmFailureKind kind = LlmCallFailureClassifier.classify(e);
            logFailure(request, endpoint, startNanos, retryCount.get(), kind, false, NO_FIRST_TOKEN_MS, e);
            throw new LlmCallException(kind, "模型接口调用失败: " + e.getMessage(), e);
        }
        LlmChatResult result;
        try {
            result = toChatResult(responses);
        } catch (RuntimeException e) {
            LlmFailureKind kind = LlmFailureKind.UNKNOWN;
            logFailure(request, endpoint, startNanos, retryCount.get(), kind, false, NO_FIRST_TOKEN_MS, e);
            throw new LlmCallException(kind, "模型接口调用失败: " + e.getMessage(), e);
        }
        logSuccess(request, endpoint, startNanos, retryCount.get(), result, false, NO_FIRST_TOKEN_MS);
        return result;
    }

    /**
     * {@inheritDoc}
     * <p>与 {@link #chat(LlmChatRequest)} 共用同一条框架调用与重试配置，区别仅在于不对分片做聚合：
     * 每个分片映射为一个 {@link LlmStreamDelta} 向下游推送。调用级日志在流终结时输出
     * （成功为 INFO、失败为 WARN），并携带首字延迟与流式标记，便于区分聚合调用。</p>
     * <p>取消语义：调用方 dispose 订阅即中断框架流，不再产生后续分片与日志（不记为失败）。</p>
     */
    @Override
    public Flux<LlmStreamDelta> stream(LlmChatRequest request) {
        if (ObjectUtils.isEmpty(request)) {
            throw new IllegalArgumentException("LLM 请求不能为空");
        }
        ModelProfileAccess.ResolvedEndpoint endpoint = modelProfileAccess.resolve(request.modelProfileId());
        Model model = modelFactory.apply(endpoint);
        long startNanos = System.nanoTime();
        AtomicInteger retryCount = new AtomicInteger();
        AtomicLong firstTokenNanos = new AtomicLong(FIRST_TOKEN_UNSET);
        AtomicInteger totalTokens = new AtomicInteger();
        StringBuilder aggregated = new StringBuilder();
        return openResponseStream(request, model, retryCount)
                .map(response -> toStreamDelta(response, firstTokenNanos, totalTokens))
                .doOnNext(delta -> aggregated.append(delta.text()))
                .doOnComplete(() -> logSuccess(request, endpoint, startNanos, retryCount.get(),
                        new LlmChatResult(aggregated.toString(), totalTokens.get()), true,
                        firstTokenMillis(firstTokenNanos, startNanos)))
                .doOnError(error -> logFailure(request, endpoint, startNanos, retryCount.get(),
                        LlmCallFailureClassifier.classify(error), true,
                        firstTokenMillis(firstTokenNanos, startNanos), error))
                .onErrorMap(error -> new LlmCallException(LlmCallFailureClassifier.classify(error),
                        "模型接口调用失败: " + error.getMessage(), error));
    }

    /**
     * 打开框架响应流（对话与流式两个入口共用的唯一发起点）。
     *
     * @param request    对话请求
     * @param model      已解析的框架模型
     * @param retryCount 重试计数器（框架重试判定命中时递增）
     * @return 框架响应分片流（冷流，订阅时才真正发起调用）
     */
    private static Flux<ChatResponse> openResponseStream(LlmChatRequest request, Model model,
                                                         AtomicInteger retryCount) {
        return model.stream(buildMessages(request), List.of(), buildOptions(request, retryCount));
    }

    /**
     * 单个框架响应分片 → 流式增量（文本块按序拼接，token 用量取该分片的非空 usage）。
     *
     * @param response        框架响应分片
     * @param firstTokenNanos 首字时间戳持有者（首次出现非空文本时写入）
     * @param totalTokens     累计 token 持有者（仅在该分片回传 usage 时更新）
     * @return 流式增量
     */
    private static LlmStreamDelta toStreamDelta(ChatResponse response, AtomicLong firstTokenNanos,
                                                AtomicInteger totalTokens) {
        StringBuilder text = new StringBuilder();
        List<ContentBlock> blocks = response.getContent();
        if (CollectionUtils.isNotEmpty(blocks)) {
            for (ContentBlock block : blocks) {
                if (block instanceof TextBlock textBlock) {
                    text.append(StringUtils.defaultString(textBlock.getText()));
                }
            }
        }
        ChatUsage usage = response.getUsage();
        Integer usageTokens = null;
        if (ObjectUtils.isNotEmpty(usage) && usage.getTotalTokens() > 0) {
            totalTokens.set(usage.getTotalTokens());
            usageTokens = usage.getTotalTokens();
        }
        if (!text.isEmpty()) {
            firstTokenNanos.compareAndSet(FIRST_TOKEN_UNSET, System.nanoTime());
        }
        return new LlmStreamDelta(text.toString(), usageTokens);
    }

    /**
     * 计算首字延迟毫秒数（未出现任何文本时返回 {@link #NO_FIRST_TOKEN_MS}）。
     *
     * @param firstTokenNanos 首字时间戳持有者
     * @param startNanos      调用起始纳秒时间戳
     * @return 首字延迟毫秒；无文本时为哨兵值
     */
    private static long firstTokenMillis(AtomicLong firstTokenNanos, long startNanos) {
        long first = firstTokenNanos.get();
        if (first == FIRST_TOKEN_UNSET) {
            return NO_FIRST_TOKEN_MS;
        }
        return Math.round((first - startNanos) / 1_000_000.0);
    }

    /**
     * 输出调用成功日志（INFO）。
     *
     * @param request      对话请求
     * @param endpoint     解析后的端点
     * @param startNanos   调用起始纳秒时间戳
     * @param retryCount   已发生的重试次数
     * @param result       调用结果
     * @param streaming    是否为增量直出调用（{@code stream} 端口）
     * @param firstTokenMs 首字延迟毫秒（聚合调用或无文本增量时为 {@link #NO_FIRST_TOKEN_MS}）
     */
    private void logSuccess(LlmChatRequest request, ModelProfileAccess.ResolvedEndpoint endpoint,
                            long startNanos, int retryCount, LlmChatResult result,
                            boolean streaming, long firstTokenMs) {
        LlmCallLogSupport.info(log, () -> String.format(
                "llm.call | stage=%s docId=%s seq=%s round=%s model=%s sysChars=%d userChars=%d"
                        + " images=%d stream=%s mode=%s firstTokenMs=%d elapsedMs=%d"
                        + " status=OK attempts=%d respChars=%d totalTokens=%d promptMd5=%s",
                stageOf(request), docIdOf(request), sequenceOf(request), roundOf(request),
                StringUtils.defaultString(endpoint.modelName()),
                LlmCallLogSupport.chars(request.systemPrompt()), LlmCallLogSupport.chars(request.userPrompt()),
                CollectionUtils.size(request.images()), STREAM, modeOf(streaming), firstTokenMs,
                LlmCallLogSupport.elapsedMillis(startNanos),
                attemptsOf(retryCount), LlmCallLogSupport.chars(result.text()), result.totalTokens(),
                LlmCallLogSupport.digest(request.systemPrompt() + request.userPrompt())));
    }

    /**
     * 输出调用失败日志（WARN，附原始异常）。
     *
     * @param request      对话请求
     * @param endpoint     解析后的端点
     * @param startNanos   调用起始纳秒时间戳
     * @param retryCount   已发生的重试次数
     * @param kind         失败分类
     * @param streaming    是否为增量直出调用（{@code stream} 端口）
     * @param firstTokenMs 首字延迟毫秒（聚合调用或无文本增量时为 {@link #NO_FIRST_TOKEN_MS}）
     * @param cause        原始异常
     */
    private void logFailure(LlmChatRequest request, ModelProfileAccess.ResolvedEndpoint endpoint,
                            long startNanos, int retryCount, LlmFailureKind kind,
                            boolean streaming, long firstTokenMs, Throwable cause) {
        LlmCallLogSupport.warn(log, () -> String.format(
                "llm.call | stage=%s docId=%s seq=%s round=%s model=%s sysChars=%d userChars=%d"
                        + " images=%d stream=%s mode=%s firstTokenMs=%d elapsedMs=%d"
                        + " status=FAILED reason=%s attempts=%d promptMd5=%s",
                stageOf(request), docIdOf(request), sequenceOf(request), roundOf(request),
                StringUtils.defaultString(endpoint.modelName()),
                LlmCallLogSupport.chars(request.systemPrompt()), LlmCallLogSupport.chars(request.userPrompt()),
                CollectionUtils.size(request.images()), STREAM, modeOf(streaming), firstTokenMs,
                LlmCallLogSupport.elapsedMillis(startNanos),
                kind, attemptsOf(retryCount),
                LlmCallLogSupport.digest(request.systemPrompt() + request.userPrompt())), cause);
    }

    /**
     * 调用形态展示值（聚合 / 增量直出）。
     *
     * @param streaming 是否为增量直出
     * @return 形态标识
     */
    private static String modeOf(boolean streaming) {
        return streaming ? MODE_STREAM : MODE_AGGREGATE;
    }

    /**
     * 计算尝试次数：首次 + 已发生重试，且不超过框架配置上限。
     *
     * @param retryCount 已发生重试次数
     * @return 尝试次数
     */
    private static int attemptsOf(int retryCount) {
        Integer maxAttempts = FRAMEWORK_DEFAULTS.getMaxAttempts();
        int ceiling = ObjectUtils.isEmpty(maxAttempts) ? retryCount + 1 : maxAttempts;
        return Math.min(retryCount + 1, ceiling);
    }

    /**
     * 调用阶段（缺失回落 OTHER）。
     */
    private static String stageOf(LlmChatRequest request) {
        LlmCallContext context = request.callContext();
        return ObjectUtils.isEmpty(context) ? LlmCallContext.STAGE_OTHER : context.stage();
    }

    /**
     * 文档标识（缺失返回占位符）。
     */
    private static Object docIdOf(LlmChatRequest request) {
        LlmCallContext context = request.callContext();
        return ObjectUtils.isEmpty(context) || ObjectUtils.isEmpty(context.documentId())
                ? "-" : context.documentId();
    }

    /**
     * 分块序号（缺失返回占位符）。
     */
    private static Object sequenceOf(LlmChatRequest request) {
        LlmCallContext context = request.callContext();
        return ObjectUtils.isEmpty(context) || ObjectUtils.isEmpty(context.chunkSequence())
                ? "-" : context.chunkSequence();
    }

    /**
     * 抽取轮次（缺失返回占位符）。
     */
    private static Object roundOf(LlmChatRequest request) {
        LlmCallContext context = request.callContext();
        return ObjectUtils.isEmpty(context) || ObjectUtils.isEmpty(context.round())
                ? "-" : context.round();
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
     * 组装生成选项：流式聚合、全局关闭思考模式、框架默认超时与重试、温度仅在显式给出时下发。
     * <p>执行配置的数值全部取自 {@link ExecutionConfig#MODEL_DEFAULTS}，本类不覆盖任何
     * 超时或尝试次数；仅在默认重试判定外再包一层计数，用于调用级日志的尝试次数统计。</p>
     * <p><b>思考模式</b>：恒定经 {@code additionalBodyParam} 下发
     * {@code enable_thinking=false}（由 openai 扩展的 ChatFormatter 合入请求体）。
     * 单分块耗时被拉长到分钟级；RAG 侧调用（抽取/描述/关键词）均为格式严格的
     * 短答案任务，思考收益为零，故在客户端层全局关闭。</p>
     * <p><b>流式语义</b>：{@code stream=true} 下框架 {@code Flux.timeout} 的 5 分钟约束对象是
     * 相邻分片的静默间隔（每信号重置计时器），不再限制回复总时长；持续产出的长回复合法，
     * 仅挂死的死连接会按超时分类进入既有重试。</p>
     * <p>包级可见以便单测直接断言选项形态。</p>
     *
     * @param request 对话请求
     * @return 框架生成选项
     */
    static GenerateOptions buildOptions(LlmChatRequest request) {
        return buildOptions(request, new AtomicInteger());
    }

    /**
     * 组装生成选项（携带重试计数器）。
     *
     * @param request    对话请求
     * @param retryCount 重试计数器（默认重试判定命中时递增）
     * @return 框架生成选项
     */
    static GenerateOptions buildOptions(LlmChatRequest request, AtomicInteger retryCount) {
        GenerateOptions.Builder options = GenerateOptions.builder()
                .stream(STREAM)
                .additionalBodyParam(PARAM_ENABLE_THINKING, false)
                .executionConfig(defaultExecutionConfig(retryCount));
        if (ObjectUtils.isNotEmpty(request.temperature())) {
            options.temperature(request.temperature());
        }
        return options.build();
    }

    /**
     * 以框架默认值构建执行配置，仅追加重试计数观测。
     *
     * @param retryCount 重试计数器（可空表示不计数）
     * @return 执行配置
     */
    private static ExecutionConfig defaultExecutionConfig(AtomicInteger retryCount) {
        return ExecutionConfig.builder()
                .timeout(FRAMEWORK_DEFAULTS.getTimeout())
                .maxAttempts(FRAMEWORK_DEFAULTS.getMaxAttempts())
                .initialBackoff(FRAMEWORK_DEFAULTS.getInitialBackoff())
                .maxBackoff(FRAMEWORK_DEFAULTS.getMaxBackoff())
                .backoffMultiplier(FRAMEWORK_DEFAULTS.getBackoffMultiplier())
                .retryOn(error -> {
                    // 框架默认判定 + 超时兜底：Flux.timeout 产出的是 ModelException，默认判定
                    // 只认 TimeoutException/IO/HTTP 异常类型，会把框架自身的超时排除在重试之外
                    boolean retryable = ObjectUtils.isEmpty(FRAMEWORK_DEFAULTS.getRetryOn())
                            || FRAMEWORK_DEFAULTS.getRetryOn().test(error)
                            || LlmCallFailureClassifier.classify(error) == LlmFailureKind.TIMEOUT;
                    if (retryable && ObjectUtils.isNotEmpty(retryCount)) {
                        retryCount.incrementAndGet();
                    }
                    return retryable;
                })
                .build();
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
