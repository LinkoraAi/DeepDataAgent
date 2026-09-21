package com.linkroa.deepdataagent.rag.infrastructure.client;

import com.linkroa.deepdataagent.rag.domain.enums.CacheType;
import com.linkroa.deepdataagent.rag.domain.model.LlmCacheEntry;
import com.linkroa.deepdataagent.rag.domain.port.LlmCacheKeyProvider;
import com.linkroa.deepdataagent.rag.domain.port.LlmChatRequest;
import com.linkroa.deepdataagent.rag.domain.port.LlmChatResult;
import com.linkroa.deepdataagent.rag.domain.port.LlmClient;
import com.linkroa.deepdataagent.rag.domain.repository.ChunkExtractCacheRepository;
import com.linkroa.deepdataagent.rag.domain.repository.LlmCacheRepository;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.LongAdder;

/**
 * 带 {@code llm_cache} 缓存的 LLM 客户端（生产默认装配）。
 * <p>缓存键 = {@code md5(model + prompt + 归一化参数)}（不含 kb_id，跨库内容相同的调用
 * 可生成同键）；命中查询按复合唯一键 {@code (kb_id, cache_type, cache_key)} 走库级与
 * 分类双重隔离——回放只在同库同分类内发生，避免跨库泄露抽取内容。
 * <p>缓存分区由调用点经
 * {@link LlmChatRequest#cacheType()} 显式声明（弃隐式传递）：摄入期产物——实体/关系抽取
 * （首轮与续抽）与多模态媒体描述——显式挂 {@link CacheType#EXTRACT} 分区，实体/关系描述
 * 摘要归 {@link CacheType#ENTITY_DESC}，查询答案归 {@link CacheType#ANSWER}；
 * 未显式声明的通用调用回落 {@link CacheType#ANSWER}。回放只在同分区内发生、互不串扰，
 * 读、写两侧三元组一致才能命中回放。
 * 未命中执行真实调用后经 {@code saveIfAbsent}（ON CONFLICT DO NOTHING）回写，
 * 并发重复调用最多损失一次冗余写入。</p>
 * <p>多模态请求在键要素串尾按图片顺序追加每张图的
 * {@code sha256:<内容十六进制摘要>}，确保「同文本不同图」不会互串回放；无图请求的
 * 键计算与引入图片维度前逐字节一致，既有缓存条目继续可命中。</p>
 * <p><b>键算法单一来源</b>：键计算委托 {@link LlmCacheKeyProvider}（实现见
 * {@link DefaultLlmCacheKeyProvider}），本类不再自带任何键计算代码——
 * 归属登记、补登记等旁路场景与缓存读写因此使用同一处算法，键值恒等、无漂移可能。</p>
 *
 * <p><b>抽取缓存归属登记</b>（design D2）：请求携带归因字段
 * {@link LlmChatRequest#attributionChunkId()} 时，本客户端在<b>命中回放与未命中回写两条路径</b>
 * 都向 {@link ChunkExtractCacheRepository} 登记「该分块使用过该缓存行」的归属关系——
 * 回放路径最容易漏（命中即返回、缓存侧不产生任何信号），故两路径统一在本类单点登记。
 * 未携带归因字段的调用（查询答案、实体描述摘要等）不登记任何归属。</p>
 * <p><b>归属登记不改变缓存语义</b>：只新增归属行，MUST NOT 触碰键计算、命中判定口径
 * 与缓存行内容，也 MUST NOT 触发对缓存行的更新（缓存行保持「首次写入即定形、
 * 重复写入不覆盖」的不可变性质，重建依赖的响应可复现性据此成立）。登记失败（仓储异常）
 * 只记 WARN 与失败计数，MUST NOT 影响本次 LLM 调用结果的返回。</p>
 */
@Primary
@Component
public class CachingLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(CachingLlmClient.class);

    private final LlmClient delegate;
    private final LlmCacheRepository llmCacheRepository;
    private final ModelProfileAccess modelProfileAccess;
    private final ChunkExtractCacheRepository chunkExtractCacheRepository;
    private final LlmCacheKeyProvider llmCacheKeyProvider;

    /** 归属登记累计失败次数（观测指标；登记失败不影响 LLM 调用结果返回） */
    private final LongAdder attributionFailureCount = new LongAdder();

    /**
     * 构造带缓存的 LLM 客户端。
     *
     * @param delegate                    被缓存的真实 LLM 客户端（OpenAI 兼容实现）
     * @param llmCacheRepository          LLM 缓存仓储（命中回放与未命中回写）
     * @param modelProfileAccess          模型配置解析端口（解析回写缓存行的模型名）
     * @param chunkExtractCacheRepository 抽取缓存归属仓储（分块 → 缓存行的归属登记）
     * @param llmCacheKeyProvider         缓存键只读计算端口（键算法单一来源，与旁路登记同源同值）
     */
    public CachingLlmClient(@Qualifier("openAiCompatibleLlmClient") LlmClient delegate,
                            LlmCacheRepository llmCacheRepository,
                            ModelProfileAccess modelProfileAccess,
                            ChunkExtractCacheRepository chunkExtractCacheRepository,
                            LlmCacheKeyProvider llmCacheKeyProvider) {
        this.delegate = delegate;
        this.llmCacheRepository = llmCacheRepository;
        this.modelProfileAccess = modelProfileAccess;
        this.chunkExtractCacheRepository = chunkExtractCacheRepository;
        this.llmCacheKeyProvider = llmCacheKeyProvider;
    }

    @Override
    public LlmChatResult chat(LlmChatRequest request) {
        if (ObjectUtils.isEmpty(request)) {
            throw new IllegalArgumentException("LLM 请求不能为空");
        }
        String modelName = modelProfileAccess.resolve(request.modelProfileId()).modelName();
        String cacheKey = llmCacheKeyProvider.cacheKeyOf(request);
        // 缓存分区由请求显式声明：读、写两侧三元组必须一致才能命中回放
        CacheType cacheType = ObjectUtils.defaultIfNull(request.cacheType(), CacheType.ANSWER);
        Optional<LlmCacheEntry> cached =
                llmCacheRepository.findByKbIdAndCacheKey(request.kbId(), cacheType, cacheKey);
        if (cached.isPresent()) {
            LlmCacheEntry entry = cached.get();
            log.debug("llm_cache 命中回放: kbId={}, cacheType={}, cacheKey={}",
                    request.kbId(), cacheType, cacheKey);
            // 命中回放路径同样登记归属（design D2）：回放不触达回写，缓存侧无信号，此处是唯一登记点
            registerAttribution(request, cacheType, cacheKey);
            return new LlmChatResult(entry.response(),
                    ObjectUtils.defaultIfNull(entry.totalTokens(), 0), true);
        }
        LlmChatResult result = delegate.chat(request);
        llmCacheRepository.saveIfAbsent(LlmCacheEntry.create(request.kbId(), cacheKey, cacheType, modelName,
                composePromptText(request), result.text(), result.totalTokens()));
        // 未命中路径在回写之后登记归属：登记只新增映射行，不改变刚写入的缓存行内容
        registerAttribution(request, cacheType, cacheKey);
        return result;
    }

    /**
     * 归属登记累计失败次数（观测指标，供监控与告警消费）。
     * <p>登记失败被本类捕获并降级为 WARN，计数用于观察仓储可用性；
     * 计数增长 MUST NOT 影响任何 LLM 调用的返回值。</p>
     *
     * @return 累计失败次数（进程内计数，重启归零）
     */
    public long attributionFailureCount() {
        return attributionFailureCount.sum();
    }

    /**
     * 登记「本次调用使用的分块」与「本次缓存行」的归属关系（命中与未命中两条路径的统一入口）。
     * <p>登记条件：请求携带归因字段 {@link LlmChatRequest#attributionChunkId()} 且缓存分类非空；
     * 未携带归因字段的调用（答案生成、描述摘要等）直接跳过，不产生任何登记。</p>
     * <p><b>失败只降级不阻断</b>：归属登记属旁路观测职责，仓储异常时输出 WARN 并累加失败计数，
     * MUST NOT 向上抛出，以免影响 LLM 调用结果的返回；也 MUST NOT 触发任何缓存行更新
     * （{@code registerAll} 只落归属映射行，幂等由数据库唯一键承担）。</p>
     *
     * @param request   本次 LLM 请求
     * @param cacheType 生效缓存分类（与本次读、写命中的分区一致）
     * @param cacheKey  本次请求的缓存键
     */
    private void registerAttribution(LlmChatRequest request, CacheType cacheType, String cacheKey) {
        Long chunkId = request.attributionChunkId();
        if (ObjectUtils.isEmpty(chunkId) || ObjectUtils.isEmpty(cacheType) || StringUtils.isBlank(cacheKey)) {
            return;
        }
        try {
            chunkExtractCacheRepository.registerAll(request.kbId(), chunkId, cacheType, List.of(cacheKey));
        } catch (RuntimeException failed) {
            attributionFailureCount.increment();
            log.warn("抽取缓存归属登记失败（不影响本次调用返回）: kbId={}, chunkId={}, cacheType={}, "
                            + "cacheKey={}, 累计失败次数={}",
                    request.kbId(), chunkId, cacheType, cacheKey, attributionFailureCount.sum(), failed);
        }
    }

    /**
     * 缓存 prompt 列内容：系统 + 用户提示词全文（仅用于排查回放差异，不参与键计算）。
     */
    private String composePromptText(LlmChatRequest request) {
        if (StringUtils.isBlank(request.systemPrompt())) {
            return request.userPrompt();
        }
        return request.systemPrompt() + "\n\n" + request.userPrompt();
    }
}
