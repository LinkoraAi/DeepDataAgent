package com.linkroa.deepdataagent.rag.domain.service;

import com.linkroa.deepdataagent.knowledgebase.api.KnowledgeBaseApi;
import com.linkroa.deepdataagent.knowledgebase.domain.model.Chunk;
import com.linkroa.deepdataagent.knowledgebase.domain.model.RerankModelConfig;
import com.linkroa.deepdataagent.knowledgebase.domain.model.RetrievalStrategyConfig;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.RetrievalStrategyType;
import com.linkroa.deepdataagent.rag.application.contract.RetrievalQuery;
import com.linkroa.deepdataagent.rag.domain.enums.CacheType;
import com.linkroa.deepdataagent.rag.domain.model.KgContext;
import com.linkroa.deepdataagent.rag.domain.model.KgSearchResult;
import com.linkroa.deepdataagent.rag.domain.model.LlmCacheEntry;
import com.linkroa.deepdataagent.rag.domain.model.RankedChunk;
import com.linkroa.deepdataagent.rag.domain.port.LlmChatRequest;
import com.linkroa.deepdataagent.rag.domain.port.LlmChatResult;
import com.linkroa.deepdataagent.rag.domain.port.LlmClient;
import com.linkroa.deepdataagent.rag.domain.port.LlmImage;
import com.linkroa.deepdataagent.rag.domain.repository.LlmCacheRepository;
import com.linkroa.deepdataagent.rag.infrastructure.prompts.catalog.PromptCatalog;
import com.linkroa.deepdataagent.rag.infrastructure.prompts.catalog.PromptTemplates;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 答案生成服务默认实现（Stage 5）。
 *
 * <p><b>缓存双轨</b>：本类自行持有并读写 {@code llm_cache} 的 {@link CacheType#ANSWER} 分类，
 * 因此构造器注入的是<b>裸</b> LLM 客户端（{@code openAiCompatibleLlmClient}）而非
 * {@code @Primary} 的缓存装饰客户端，避免同一请求被两层缓存重复消费；
 * 缓存键按方案的要素串（mode / query / response_type / top_k / chunk_top_k /
 * max_total_tokens / max_entity_tokens / max_relation_tokens / hl_keywords / ll_keywords /
 * user_prompt / enable_rerank / enable_content_headings / llmIdentity）以固定分隔符
 * （换行符 + {@code U+0001} 控制符）连接后取 MD5，与 {@code CachingLlmClient} 的
 * {@code md5(model + prompt + 参数)} 口径互不干扰。</p>
 *
 * <p><b>提示词组织</b>：{@code rag_response} / {@code naive_rag_response}
 * 为系统提示词（Role / Goal / Instructions / Context 全篇），模板正文不含用户问题占位符，
 * 故渲染结果作为 {@link LlmChatRequest#systemPrompt()}，实际送入的用户问题
 * （入参 {@code query}，即改写后 query）作为 {@link LlmChatRequest#userPrompt()}；
 * 温度不显式指定（沿用提供方默认）。缓存条目的 {@code prompt} 列记录渲染后的系统提示词全文，
 * {@code model} 列记录 chat 模型 profileId。</p>
 *
 * <p><b>降级链</b>（任一命中即直出 {@code fail_response} 模板文本，不进缓存、不调 LLM）：
 * ① 策略配置或检索请求缺失；② 送入的问题为空白；③ 知识库未配置 chat 模型 profileId
 * （LLM 不可用，唯一真相源＝{@code knowledge_base.multi_model_config} 的
 * {@code modelProfileId}，不回退任何全局默认值）；
 * ④ {@code resultChunkCount=0}；⑤ 无可利用上下文（contextData 空白且图谱实体/关系/chunk 全空）；
 * ⑥ 提示词渲染或缓存键计算异常；⑦ LLM 调用异常或返回空结果。缓存读取异常不吞（与
     * {@code CachingLlmClient} 一致，仓储故障需向上暴露）；缓存回写异常仅记录 WARN、
     * 答案照常返回。{@code fail_response} 本身渲染失败时记录 ERROR 并返回内置兜底文案常量。</p>
     *
     * <p><b>通路 B：检索命中知识库图片的原图直读（/
     * /）</b>：
     * 仅在 ANSWER 缓存<b>未命中</b>分支（时序：命中回放零对象存储读取）、真正发起 chat 前，
     * 按上下文序从 {@link KgContext#retainedChunkIds()}（token 预算内实际装入的全通道切片，
     * 空表回落图谱有序切片）回取 chunk 并解析 {@code original_item} 的媒体引用，交
     * {@link AnswerImageResolver} 读取原图字节（去重保序 / 上限 2 / 截断与读败跳过均由其承担）；
     * 有图则用现 {@code rag_response}/{@code naive_rag_response} 模板文本 + images 构造多模态请求
     * 直读作答，直读调用异常/空结果回落纯文本二次调用（回落不丢正常产物），
     * 纯文本仍失败才走 {@code fail_response}；无引用 / 未配置视觉模型（profileId 空白，
     * 已由前置校验短路）零对象存储交互。「视觉模型未配置」判定即 profileId 空白
     * （profileId 已配置视为可用）。缓存键恒为 14 要素基线形态（图片第 15 要素在本层休眠，
     * 回写与查询同键）；图片字节维度防串扰由端口层 {@code CachingLlmClient} 的图片摘要键兜底。
     * {@code RetrievalQuery.multimodal.answerImageDirectRead} 为通路 B 显式开关
     * ：false 时 {@code resolveAnswerImages} 入口即返回空表
     * （跳过引用解析与直读、零对象存储交互），下游走既有纯文本作答分支；true（缺省）行为不变。</p>
     *
     * @author DeepDataAgent
     */
@Service
public class DefaultAnswerGenerator implements AnswerGenerator {

    /** 日志器 */
    private static final Logger log = LoggerFactory.getLogger(DefaultAnswerGenerator.class);

    /** 兜底直出模板名 */
    static final String FAIL_RESPONSE_TEMPLATE = PromptTemplates.FAIL_RESPONSE;

    /** 缓存键 enable_content_headings 要素缺省值（本期无该配置项，恒为该占位串） */
    private static final String CONTENT_HEADINGS_ABSENT = "default";

    /** 缓存键要素连接符（与 {@code CachingLlmClient} 同口径，避免拼接歧义导致键碰撞） */
    private static final String KEY_PART_SEPARATOR = "\n\u0001";

    /** 关键词列表要素内部连接符（规范化后以逗号连接） */
    private static final String KEYWORD_JOIN_SEPARATOR = ",";

    /** 布尔要素真值串 */
    private static final String FLAG_TRUE = "true";

    /** 布尔要素假值串 */
    private static final String FLAG_FALSE = "false";

    /** 关闭结果返回的数量取值（resultChunkCount=0 表示不返回任何切片） */
    private static final int RESULT_CHUNK_COUNT_DISABLED = 0;

    /** fail_response 模板渲染失败时的内置兜底文案（与模板正文逐字一致） */
    private static final String FAIL_RESPONSE_FALLBACK =
            "Sorry, I'm not able to provide an answer to that question.[no-context]";

    /** 图片内容摘要片段连接符（缓存键图片要素内部逐图 MD5 之间的连接符，避免拼接歧义） */
    private static final String IMAGE_DIGEST_JOINER = ";";

    /** 裸 LLM 客户端（不含缓存装饰，缓存由本类按键口径自持） */
    private final LlmClient llmClient;

    /** LLM 调用缓存仓储（ANSWER 分类的读写入口） */
    private final LlmCacheRepository llmCacheRepository;

    /**
     * 知识库跨 BC 只读契约（通路 B 按 chunkId 回取 {@code original_item} 解析原图引用；
     * 并按 kbId 只读投影 chat 模型 profileId——与查询理解服务同源，是本 BC 对话模型选型唯一真相源）
     */
    private final KnowledgeBaseApi knowledgeBaseApi;

    /** 作答侧原图直读解析器（引用 → 读取原图字节 → 图片载荷，通路 B） */
    private final AnswerImageResolver answerImageResolver;

    /** JSON 解析器（chunk {@code original_item} → 媒体引用键） */
    private final ObjectMapper objectMapper;

    /**
     * 构造答案生成器。
     *
     * @param llmClient           裸 LLM 客户端（显式限定 {@code openAiCompatibleLlmClient}）
     * @param llmCacheRepository  LLM 调用缓存仓储
     * @param knowledgeBaseApi    知识库跨 BC 只读契约（回取 chunk {@code original_item}、
     *                            按 kbId 投影 chat 模型 profileId）
     * @param answerImageResolver 作答侧原图直读解析器
     * @param objectMapper        JSON 解析器（解析 {@code original_item}）
     */
    public DefaultAnswerGenerator(@Qualifier("openAiCompatibleLlmClient") LlmClient llmClient,
                                  LlmCacheRepository llmCacheRepository,
                                  KnowledgeBaseApi knowledgeBaseApi,
                                  AnswerImageResolver answerImageResolver,
                                  ObjectMapper objectMapper) {
        this.llmClient = llmClient;
        this.llmCacheRepository = llmCacheRepository;
        this.knowledgeBaseApi = knowledgeBaseApi;
        this.answerImageResolver = answerImageResolver;
        this.objectMapper = objectMapper;
    }

    /**
     * {@inheritDoc}
     * <p>执行顺序：降级前置校验 → 模板选择与变量装配 → 渲染 → 缓存键计算 → 缓存读 → LLM 生成 →
     * 缓存回写。缓存键的 {@code user_prompt} 要素为<b>渲染后的提示词全文</b>，
     * 故必须先渲染再算键；模板内的 {@code {user_prompt}} 为「附加指令」槽位，
     * 本期无来源，填充占位串 {@code n/a}。</p>
     */
    @Override
    public String generate(String query, KgContext context, RetrievalStrategyConfig cfg, RetrievalQuery q) {
        return generate(query, context, cfg, q, null);
    }

    /**
     * 生成最终答案（带 LLM 缓存命中计数口径）：语义与四参重载一致，
     * {@code ANSWER} 缓存命中回放时对 {@code cacheHits} 递增一次。
     *
     * @param query     用户查询文本（改写后 query）
     * @param context   上下文产物（contextData / referenceList / kgResult）
     * @param cfg       检索策略配置（strategyType 决定模板选择）
     * @param q         检索内部执行请求（缓存键要素与降级判断来源；其 kbId 是 chat 模型
     *                  profileId 的唯一真相源定位键）
     * @param cacheHits LLM 缓存命中计数器（命中递增；传 null 时不统计）
     * @return 答案文本（失败时可为 {@code fail_response} 字面量）
     */
    @Override
    public String generate(String query, KgContext context, RetrievalStrategyConfig cfg, RetrievalQuery q,
                           AtomicInteger cacheHits) {
        if (ObjectUtils.isEmpty(cfg) || ObjectUtils.isEmpty(q)) {
            log.warn("答案生成入参缺失策略配置或检索请求，直出 fail_response");
            return renderFailResponse();
        }
        if (StringUtils.isBlank(query)) {
            log.warn("送入答案生成的问题为空白，直出 fail_response: kbId={}", q.kbId());
            return renderFailResponse();
        }
        String chatProfileId = knowledgeBaseApi.findMediaModelProfileIdByKbId(q.kbId());
        if (StringUtils.isBlank(chatProfileId)) {
            log.warn("知识库未配置 CHAT 模型 profileId，LLM 不可用，直出 fail_response: kbId={}", q.kbId());
            return renderFailResponse();
        }
        if (isResultChunkDisabled(cfg)) {
            log.info("检索策略结果返回数量为 0，直出 fail_response: kbId={}", q.kbId());
            return renderFailResponse();
        }
        if (!hasUsableContext(context)) {
            log.info("无可利用上下文，直出 fail_response: kbId={}", q.kbId());
            return renderFailResponse();
        }

        // 模板形态（回答模板名 + 默认变量表 + 内容槽变量名）由 AnswerProfile 单点供给，
        // 与 Stage 4 上下文骨架同源；本层判据仍为策略类型，取值口径不变
        AnswerProfile profile = AnswerProfile.forKgMode(RetrievalStrategyType.MIX.equals(cfg.strategyType()));
        String templateName = profile.responseTemplateName();
        String renderedPrompt;
        String cacheKey;
        try {
            renderedPrompt = PromptCatalog.render(templateName, RetrievalConstants.DEFAULT_LANGUAGE,
                    buildTemplateVars(profile, context));
            // 缓存键以空图片列表计算——图片要素（第 15 要素）
            // 在本层自然休眠，原图解析延后至缓存未命中分支执行，命中路径零对象存储读取
            cacheKey = buildCacheKey(cfg, q, query, renderedPrompt, List.of(), chatProfileId);
        } catch (RuntimeException e) {
            log.warn("答案提示词渲染或缓存键计算失败，降级直出 fail_response: template={}", templateName, e);
            return renderFailResponse();
        }

        Optional<LlmCacheEntry> cached =
                llmCacheRepository.findByKbIdAndCacheKey(q.kbId(), CacheType.ANSWER, cacheKey);
        if (cached.isPresent()) {
            if (ObjectUtils.isNotEmpty(cacheHits)) {
                cacheHits.incrementAndGet();
            }
            log.debug("ANSWER 缓存命中回放: kbId={}, cacheKey={}", q.kbId(), cacheKey);
            return StringUtils.defaultString(cached.get().response());
        }

        // 通路 B：仅缓存未命中才解析上下文原图引用（profileId 已在前置校验保证非空白=视觉可用；
        // 无引用/异常均回落空表，零对象存储交互；answerImageDirectRead=false 时入口即短路）；
        // 回写与查询共用上方同一 cacheKey，开关关闭后键不追加图片要素、与历史纯文本键逐字节一致
        List<LlmImage> answerImages = resolveAnswerImages(context, q);
        LlmChatResult result = null;
        // 有可直读原图时先发起多模态作答，失败/空结果回落纯文本二次调用（回落不影响正常回答产物）
        if (CollectionUtils.isNotEmpty(answerImages)) {
            try {
                result = llmClient.chat(new LlmChatRequest(q.kbId(), chatProfileId, renderedPrompt, query,
                        null, CacheType.ANSWER, answerImages));
            } catch (RuntimeException e) {
                log.warn("原图直读作答调用失败，回落纯文本作答: kbId={}, imageCount={}", q.kbId(),
                        answerImages.size(), e);
                result = null;
            }
            if (ObjectUtils.isEmpty(result)) {
                log.warn("原图直读作答返回空结果，回落纯文本作答: kbId={}", q.kbId());
            }
        }
        if (ObjectUtils.isEmpty(result)) {
            try {
                result = llmClient.chat(new LlmChatRequest(
                        q.kbId(), chatProfileId, renderedPrompt, query, null, CacheType.ANSWER));
            } catch (RuntimeException e) {
                log.warn("LLM 答案生成失败，降级直出 fail_response: kbId={}, profileId={}", q.kbId(), chatProfileId, e);
                return renderFailResponse();
            }
        }
        if (ObjectUtils.isEmpty(result)) {
            log.warn("LLM 返回空结果，降级直出 fail_response: kbId={}", q.kbId());
            return renderFailResponse();
        }

        // 缓存回写失败仅告警，不影响已生成答案返回，避免仓储故障连带丢弃有效答案
        try {
            llmCacheRepository.saveIfAbsent(LlmCacheEntry.create(q.kbId(), cacheKey, CacheType.ANSWER,
                    chatProfileId, renderedPrompt, result.text(), result.totalTokens()));
        } catch (RuntimeException e) {
            log.warn("ANSWER 缓存回写失败，答案仍正常返回: kbId={}, cacheKey={}", q.kbId(), cacheKey, e);
        }
        return result.text();
    }

    /**
     * 装配答案模板占位符变量：默认变量（{@code response_type} / {@code user_prompt}）
     * 与内容槽变量名均由 {@link AnswerProfile} 供给（MIX 用 {@code {context_data}}、
     * NAIVE 用 {@code {content_data}}），两者取值均为上下文构建产物
     * {@link KgContext#contextData()}。
     *
     * @param profile 当前模板形态（判据来自策略类型）
     * @param context 上下文产物（前置校验保证非空且含可用内容）
     * @return 模板变量表
     */
    private Map<String, String> buildTemplateVars(AnswerProfile profile, KgContext context) {
        return profile.responseVars(StringUtils.defaultString(context.contextData()));
    }

    /**
     * 构建 ANSWER 缓存键（要素串，逐要素以固定分隔符连接后取 MD5）。
     * <p>关键词要素口径：过滤空白项后 {@code trim}，以逗号连接，{@code null} 列表归一为空串；
     * {@code llmIdentity} 口径：本次请求按 kbId 从知识库 {@code rag_engine_config} 现取的
     * CHAT 模型 profileId（与查询理解服务、实际送入 LLM 的模型同源，保证键的可回放性）。
     * 要素组成与顺序与改动前逐字一致，仅取值来源由全局配置改为知识库配置。</p>
     *
     * @param cfg            检索策略配置（mode / enable_rerank 要素来源）
     * @param q              检索内部执行请求（top_k / chunk_top_k / 各 token 预算 / 关键词要素来源）
     * @param query          实际送入 LLM 的用户问题（query 要素）
     * @param renderedPrompt 渲染后的提示词全文（user_prompt 要素）
     * @param images         通路 B 实际直读的原图载荷（为空表示纯文本请求，不追加图片要素，保持基线键逐字节不变）
     * @param chatProfileId  知识库 CHAT 模型 profileId（llmIdentity 要素）
     * @return 32 位 MD5 hex 缓存键
     */
    private String buildCacheKey(RetrievalStrategyConfig cfg, RetrievalQuery q, String query,
                                 String renderedPrompt, List<LlmImage> images, String chatProfileId) {
        List<String> parts = new ArrayList<>(15);
        // 1. mode：检索策略类型（MIX / NAIVE）
        parts.add(ObjectUtils.isEmpty(cfg.strategyType()) ? StringUtils.EMPTY : cfg.strategyType().name());
        // 2. query：实际送入 LLM 的用户问题
        parts.add(StringUtils.defaultString(query));
        // 3. response_type：答案形态（本期恒为默认值，与 AnswerProfile 默认变量同源）
        parts.add(AnswerProfile.DEFAULT_RESPONSE_TYPE);
        // 4. top_k：每通道向量召回数量
        parts.add(stringifyNumber(q.topK()));
        // 5. chunk_top_k：切片召回数量
        parts.add(stringifyNumber(q.chunkTopK()));
        // 6. max_total_tokens：上下文总 token 预算
        parts.add(stringifyNumber(q.maxTotalTokens()));
        // 7. max_entity_tokens：实体上下文 token 预算
        parts.add(stringifyNumber(q.maxEntityTokens()));
        // 8. max_relation_tokens：关系上下文 token 预算
        parts.add(stringifyNumber(q.maxRelationTokens()));
        // 9. hl_keywords：高层关键词规范化串
        parts.add(normalizeKeywords(q.hlKeywords()));
        // 10. ll_keywords：低层关键词规范化串
        parts.add(normalizeKeywords(q.llKeywords()));
        // 11. user_prompt：渲染后的提示词全文
        parts.add(StringUtils.defaultString(renderedPrompt));
        // 12. enable_rerank：重排开关（无重排配置视为关闭）
        parts.add(resolveRerankFlag(cfg));
        // 13. enable_content_headings：本期无该配置项，恒为缺省占位
        parts.add(CONTENT_HEADINGS_ABSENT);
        // 14. llmIdentity：CHAT 模型 profileId
        parts.add(StringUtils.defaultString(chatProfileId));
        // 15.（可选）图片集合摘要：通路 B 带图作答时追加，防止与无图/不同图集合互串缓存；无图时不追加
        if (CollectionUtils.isNotEmpty(images)) {
            parts.add(imageSetDigest(images));
        }
        String raw = String.join(KEY_PART_SEPARATOR, parts);
        return DigestUtils.md5DigestAsHex(raw.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 计算带图作答的图片集合摘要（缓存键第 15 要素）：逐张图片内容取 MD5 后按序拼接再整体取 MD5，
     * 保证「同文本不同图集合不同键」且与图片字节强相关（同一引用重摄后图片变化亦换键）。
     *
     * @param images 实际直读的图片载荷列表（非空）
     * @return 图片集合摘要串（作为缓存键的独立要素）
     */
    private String imageSetDigest(List<LlmImage> images) {
        StringBuilder digests = new StringBuilder();
        for (LlmImage image : images) {
            digests.append(DigestUtils.md5DigestAsHex(image.content())).append(IMAGE_DIGEST_JOINER);
        }
        return DigestUtils.md5DigestAsHex(digests.toString().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 解析检索上下文中可直读的原图载荷（通路 B）。
     * <p>入口先消费多模态开关 {@code answerImageDirectRead}：
     * 显式 false 时立即返回空表——跳过 chunk 回取与引用提取（零对象存储交互），无论上下文是否含
     * 可解析图片引用，下游按既有纯文本作答分支执行；为 true 时行为与基线逐字节一致。</p>
     * <p>true 分支：先按上下文相关性序提取 chunk 的媒体引用（无引用则零对象存储交互直接返回空表），
     * 再交 {@link AnswerImageResolver} 读取原图字节。整段以 {@link RuntimeException} 兜底：
     * 引用提取（KB 回取 / JSON 解析）或图片读取任一异常均视为「无可用图片」，返回空表回落纯文本作答，
     * 不阻断正常回答。</p>
     *
     * @param context 上下文产物（承载 chunkId 序，可为空）
     * @param q       检索内部执行请求（知识库主键与多模态开关来源，非空由调用方前置校验保证）
     * @return 可直读的原图载荷列表；开关关闭 / 无引用 / 全部读取失败 / 解析异常时为空列表
     */
    private List<LlmImage> resolveAnswerImages(KgContext context, RetrievalQuery q) {
        Long kbId = q.kbId();
        if (!q.multimodal().answerImageDirectRead()) {
            // 通路 B 显式关闭：零解析、零对象存储读取，与通路 A 开关及回落语义互不感知
            return List.of();
        }
        try {
            List<AnswerImageResolver.MediaReference> references = extractMediaReferences(context, kbId);
            if (CollectionUtils.isEmpty(references)) {
                return List.of();
            }
            List<LlmImage> images = answerImageResolver.resolve(references);
            return CollectionUtils.isEmpty(images) ? List.of() : images;
        } catch (RuntimeException e) {
            log.warn("解析检索上下文原图引用失败，回落纯文本作答: kbId={}", kbId, e);
            return List.of();
        }
    }

    /**
     * 从上下文装入切片中提取原图媒体引用（
     * 候选源切换）。
     * <p>候选 chunkId 序由 {@link #resolveCandidateChunkIds} 双源确定（装入集全通道优先、
     * 空表回落图谱有序切片）。{@link KgContext} 仅以 chunkId 承载切片身份、不携带
     * {@code original_item}；故按候选序回取 chunk 正文（{@link KnowledgeBaseApi#
     * findChunksByKbIdAndChunkIds}，与精排/上下文构建同源只读通道，对任意通道 id 通用），
     * 逐块解析 {@code original_item} 的 {@code mediaObjectKey} 键组装引用
     * （仅对象键，桶概念已退役）。</p>
     * <p>退化：候选集为空、chunk 无正文回取结果、{@code original_item} 缺失媒体键或解析失败，
     * 均按「无图片引用」处理（跳过该块，不产生对象存储交互）。去重 / 上限 / 截断由
     * {@link AnswerImageResolver} 原样承担。</p>
     *
     * @param context 上下文产物（可为空）
     * @param kbId    知识库主键
     * @return 按上下文序排列的媒体引用列表（仅含两键齐备的块）；无引用时为空列表
     */
    private List<AnswerImageResolver.MediaReference> extractMediaReferences(KgContext context, Long kbId) {
        if (ObjectUtils.isEmpty(context)) {
            return List.of();
        }
        List<Long> orderedChunkIds = resolveCandidateChunkIds(context);
        if (CollectionUtils.isEmpty(orderedChunkIds)) {
            return List.of();
        }
        Map<Long, Chunk> chunkById = knowledgeBaseApi.findChunksByKbIdAndChunkIds(kbId, orderedChunkIds).stream()
                .filter(chunk -> ObjectUtils.isNotEmpty(chunk) && ObjectUtils.isNotEmpty(chunk.id()))
                .collect(Collectors.toMap(Chunk::id, chunk -> chunk, (first, second) -> first));
        List<AnswerImageResolver.MediaReference> references = new ArrayList<>(orderedChunkIds.size());
        for (Long chunkId : orderedChunkIds) {
            Chunk chunk = chunkById.get(chunkId);
            if (ObjectUtils.isEmpty(chunk)) {
                continue;
            }
            AnswerImageResolver.MediaReference reference = parseMediaReference(chunkId, chunk.originalItem());
            if (ObjectUtils.isNotEmpty(reference)) {
                references.add(reference);
            }
        }
        return references;
    }

    /**
     * 解析通路 B 直读候选的有序 chunkId 集（候选源唯一集中点）。
     * <p>双源回退：{@link KgContext#retainedChunkIds()}（token 预算内实际装入上下文的全通道 chunkId，
     * 按上下文序）非空时直接取自它——NAIVE/BM25/VECTOR/GRAPH 任一通道命中的装入切片均获直读资格；
     * 空表（接口三参 {@code build} 退化路径、Stage 4 异常降级路径等）回落图谱结果
     * {@code kgResult().chunks()} 的有序 chunkId，行为与基线逐字一致。</p>
     *
     * @param context 上下文产物（非空由调用方保证）
     * @return 候选 chunkId 有序列表（过滤空元素）；两源均无有效 id 时为空列表
     */
    private List<Long> resolveCandidateChunkIds(KgContext context) {
        if (CollectionUtils.isNotEmpty(context.retainedChunkIds())) {
            return context.retainedChunkIds().stream()
                    .filter(ObjectUtils::isNotEmpty)
                    .toList();
        }
        if (ObjectUtils.isEmpty(context.kgResult()) || CollectionUtils.isEmpty(context.kgResult().chunks())) {
            return List.of();
        }
        return context.kgResult().chunks().stream()
                .filter(ObjectUtils::isNotEmpty)
                .map(RankedChunk::chunkId)
                .filter(ObjectUtils::isNotEmpty)
                .toList();
    }

    /**
     * 解析单条 chunk 的 {@code original_item} JSON，提取媒体图片引用（对象键有效才返回）。
     *
     * @param chunkId      来源 chunk 主键（引用携带，用于读取失败时的 WARN 定位）
     * @param originalItem 多模态原始信息 JSON 串（可为空 / 非法）
     * @return 媒体引用；JSON 空白、非对象、缺媒体键或解析失败时返回 {@code null}
     */
    private AnswerImageResolver.MediaReference parseMediaReference(Long chunkId, String originalItem) {
        if (StringUtils.isBlank(originalItem)) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(originalItem);
            String objectKey = textValue(root, MultimodalMetaKeys.META_KEY_MEDIA_OBJECT_KEY);
            if (StringUtils.isBlank(objectKey)) {
                return null;
            }
            return new AnswerImageResolver.MediaReference(chunkId, objectKey);
        } catch (JacksonException e) {
            log.debug("chunk original_item 解析失败，按无图片引用跳过: chunkId={}", chunkId, e);
            return null;
        }
    }

    /**
     * 读取 JSON 节点的文本字段值。
     *
     * @param root  JSON 根节点
     * @param field 字段名
     * @return 字段文本值；字段缺失或非文本时返回 {@code null}
     */
    private String textValue(JsonNode root, String field) {
        JsonNode value = root.path(field);
        return value.isTextual() ? value.asText() : null;
    }

    /**
     * 判断是否关闭结果返回（{@code resultChunkCount=0}）；字段为空表示未限制，不视为关闭。
     *
     * @param cfg 检索策略配置
     * @return 关闭返回返回 true
     */
    private boolean isResultChunkDisabled(RetrievalStrategyConfig cfg) {
        Integer resultChunkCount = cfg.resultChunkCount();
        return ObjectUtils.isNotEmpty(resultChunkCount) && resultChunkCount == RESULT_CHUNK_COUNT_DISABLED;
    }

    /**
     * 判断是否存在可供 LLM 利用的上下文：contextData 非空白，或图谱结果含实体 / 关系 / chunk 任一。
     *
     * @param context 上下文产物，可为空
     * @return 有可用上下文返回 true
     */
    private boolean hasUsableContext(KgContext context) {
        if (ObjectUtils.isEmpty(context)) {
            return false;
        }
        if (StringUtils.isNotBlank(context.contextData())) {
            return true;
        }
        KgSearchResult kgResult = context.kgResult();
        if (ObjectUtils.isEmpty(kgResult)) {
            return false;
        }
        return CollectionUtils.isNotEmpty(kgResult.entities())
                || CollectionUtils.isNotEmpty(kgResult.relations())
                || CollectionUtils.isNotEmpty(kgResult.chunks());
    }

    /**
     * 渲染 fail_response 模板（无占位符）；渲染失败记录 ERROR 并返回内置兜底文案。
     *
     * @return 兜底答案文本
     */
    private String renderFailResponse() {
        try {
            return PromptCatalog.render(FAIL_RESPONSE_TEMPLATE, RetrievalConstants.DEFAULT_LANGUAGE, Map.of());
        } catch (RuntimeException e) {
            log.error("fail_response 模板渲染失败，已返回内置兜底文案", e);
            return FAIL_RESPONSE_FALLBACK;
        }
    }

    /**
     * 重排开关归一化为缓存键要素串。
     *
     * @param cfg 检索策略配置
     * @return 启用返回 {@code true} 字面量串，否则 {@code false} 字面量串
     */
    private String resolveRerankFlag(RetrievalStrategyConfig cfg) {
        RerankModelConfig rerankConfig = cfg.rerankConfig();
        boolean enabled = ObjectUtils.isNotEmpty(rerankConfig) && rerankConfig.isEnabled();
        return enabled ? FLAG_TRUE : FLAG_FALSE;
    }

    /**
     * 数值要素归一化为缓存键串（空值转空串，避免 {@code "null"} 字面量入键）。
     *
     * @param value 数值，可为空
     * @return 数值字符串；空值返回空串
     */
    private String stringifyNumber(Integer value) {
        if (ObjectUtils.isEmpty(value)) {
            return StringUtils.EMPTY;
        }
        return String.valueOf(value);
    }

    /**
     * 关键词列表规范化为缓存键要素串：过滤空白项、逐项 trim、逗号连接；空列表返回空串。
     *
     * @param keywords 关键词列表，可为空
     * @return 规范化后的关键词串
     */
    private String normalizeKeywords(List<String> keywords) {
        if (CollectionUtils.isEmpty(keywords)) {
            return StringUtils.EMPTY;
        }
        List<String> normalized = keywords.stream()
                .filter(StringUtils::isNotBlank)
                .map(String::trim)
                .toList();
        return String.join(KEYWORD_JOIN_SEPARATOR, normalized);
    }
}
