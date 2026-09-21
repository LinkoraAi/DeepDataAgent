package com.linkroa.deepdataagent.rag.domain.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.linkroa.deepdataagent.knowledgebase.api.KnowledgeBaseApi;
import com.linkroa.deepdataagent.knowledgebase.domain.model.RetrievalStrategyConfig;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.RetrievalStrategyType;
import com.linkroa.deepdataagent.rag.domain.enums.CacheType;
import com.linkroa.deepdataagent.rag.domain.model.KeywordPair;
import com.linkroa.deepdataagent.rag.domain.model.LlmCacheEntry;
import com.linkroa.deepdataagent.rag.domain.port.LlmChatRequest;
import com.linkroa.deepdataagent.rag.domain.port.LlmChatResult;
import com.linkroa.deepdataagent.rag.domain.port.LlmClient;
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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * 查询理解默认实现（Stage 0：问题改写 + 双层关键词提取）。
 *
 * <p>落地规则：</p>
 * <ul>
 *     <li>规则 1：仅 {@code MIX} 策略执行 LLM 关键词提取，{@code NAIVE} 一律返回空关键词对；</li>
 *     <li>规则 2：{@code cfg.rewriteQuestion() == true} 时先经 LLM 问题改写
 *     （模板 {@code query_rewrite}），产物作为后续检索与关键词提取输入；</li>
 *     <li>规则 3：显式关键词（hlKeywords/llKeywords 任一非空）优先，直接返回、不触发 LLM 与缓存；</li>
 *     <li>规则 4：空兜底——{@code query} 长度小于 50 且 ll 为空时回退「整句作为 ll」；
 *     hl、ll 皆空且长度不小于 50 时返回空关键词对，终止判定（fail_response）由编排层负责；</li>
 *     <li>规则 5：LLM 产物经 {@link MultimodalJsonParser} 五级容错链解析，最终失败回退
 *     「整句作为 ll」并告警，不阻塞检索；解析成功后逐关键词按 {@code [，,;\n]+} 切分、
 *     逐项 strip、去空白项完成规范化；</li>
 *     <li>规则 6：改写与提取产物分别按 {@code QUERY_REWRITE} / {@code KEYWORD_EXTRACT}
 *     分类读写 {@code llm_cache}，键 = {@code MD5(model + query + language + llmIdentity)}。</li>
 * </ul>
 *
 * <p><b>缓存双轨说明</b>：本服务注入<b>裸客户端</b>
 * （{@code @Qualifier("openAiCompatibleLlmClient")}）而非 {@code @Primary} 的
 * {@code CachingLlmClient}——后者统一按 {@link CacheType#ANSWER} 分类缓存，与本服务自持的
 * 规格键缓存（QUERY_REWRITE / KEYWORD_EXTRACT 分类）叠加会形成双重缓存，且 ANSWER 键口径
 * （含 prompt 全文）与规格键（model+query+language+llmIdentity）不一致，
 * 故缓存读写完全由本服务按规格键自行管理。</p>
 *
 * <p><b>缓存键口径</b>：
 * {@code MD5(model + query + language + llmIdentity)}，其中 model 取<b>知识库级 LLM 模型
 * profileId</b>（经 {@link KnowledgeBaseApi#findMediaModelProfileIdByKbId(Long)} 按 kbId
 * 只读投影现取，即 {@code multi_model_config.modelProfileId}，与作答生成同源）、
 * language 取<b>知识库语言全名</b>（经 {@link KnowledgeBaseApi#findLanguageByKbId(Long)} 只读投影
 * 获取，与 {@code {language}} 注入及模板套选择同源；库未配置回落
 * {@link RetrievalConstants#DEFAULT_LANGUAGE}）；本实现采用模型
 * profile 标识（与 model 同值），读写同口径保证确定性命中。各要素以 {@code \n\u0001} 连接
 * （对齐 {@code CachingLlmClient} 的键组成部分连接符先例），避免拼接歧义碰撞。</p>
 *
 * <p><b>降级口径</b>：LLM 模型 profileId 未配置（知识库 {@code multi_model_config} 的
 * {@code modelProfileId} 缺失或空白，不回退任何全局默认值）视为 LLM 不可用——
 * rewrite 原样返回、extract 走空兜底规则；模板渲染、LLM 调用或缓存回写异常均记录告警后按
 * 上述同口径降级，全程不向编排层抛出异常。</p>
 *
 * @author DeepDataAgent
 */
@Service
public class DefaultQueryUnderstandingService implements QueryUnderstandingService {

    private static final Logger log = LoggerFactory.getLogger(DefaultQueryUnderstandingService.class);

    /** Prompt 模板名：问题改写（集中定义见 PromptTemplates） */
    private static final String TEMPLATE_QUERY_REWRITE = PromptTemplates.QUERY_REWRITE;

    /** Prompt 模板名：双层关键词提取（集中定义见 PromptTemplates） */
    private static final String TEMPLATE_KEYWORDS_EXTRACTION = PromptTemplates.KEYWORDS_EXTRACTION;

    /** 模板变量名：用户查询文本 */
    private static final String VAR_QUERY = "query";

    /** 模板变量名：输出语言 */
    private static final String VAR_LANGUAGE = "language";

    /** 模板变量名：输出格式示例 */
    private static final String VAR_EXAMPLES = "examples";

    /** keywords_extraction 模板 examples 变量固定内容（尖括号占位符仅示意结构） */
    private static final String KEYWORDS_EXAMPLES =
            "{\"high_level_keywords\": [\"<high_level_keyword>\"], "
                    + "\"low_level_keywords\": [\"<low_level_keyword>\"]}";

    /** LLM JSON 字段名：高层关键词数组 */
    private static final String FIELD_HIGH_LEVEL_KEYWORDS = "high_level_keywords";

    /** LLM JSON 字段名：低层关键词数组 */
    private static final String FIELD_LOW_LEVEL_KEYWORDS = "low_level_keywords";

    /** 关键词规范化切分正则：顿号/中文逗号/英文逗号/分号/换行 */
    private static final Pattern KEYWORD_SPLIT_PATTERN = Pattern.compile("[、，,;\n]+");

    /** 空关键词兜底的查询长度阈值（短于该长度回退「整句作为 ll」） */
    private static final int SHORT_QUERY_LENGTH_THRESHOLD = 50;

    /** 缓存键组成部分连接符（对齐 {@code CachingLlmClient} 先例，避免拼接歧义导致键碰撞） */
    private static final String KEY_PART_SEPARATOR = "\n\u0001";

    /** 告警日志中 LLM 原文摘要的最大长度（对齐 {@code MultimodalJsonParser} 先例） */
    private static final int LOG_ABBREVIATE_WIDTH = 200;

    /** 裸 LLM 客户端（绕过 @Primary 缓存装饰器，避免双重缓存，见类注释） */
    private final LlmClient llmClient;

    /** LLM 缓存仓储端口（规格键读写，复合唯一键 kb_id + cache_type + cache_key） */
    private final LlmCacheRepository llmCacheRepository;

    /**
     * 知识库服务契约（按 kbId 只读投影获取知识库语言与聊天模型 profileId：语言与摄入端
     * 图谱语言同源，聊天模型 profileId 是本 BC 对话模型选型的唯一真相源）
     */
    private final KnowledgeBaseApi knowledgeBaseApi;

    /**
     * 构造器注入查询理解所需依赖。
     *
     * @param llmClient          裸 LLM 客户端（{@code openAiCompatibleLlmClient}，
     *                           不走 {@code CachingLlmClient} 的 ANSWER 分类缓存，避免双重缓存）
     * @param llmCacheRepository LLM 缓存仓储端口（本服务按规格键自行读写）
     * @param knowledgeBaseApi   知识库服务契约（按 kbId 只读获取知识库语言与聊天模型 profileId）
     */
    public DefaultQueryUnderstandingService(@Qualifier("openAiCompatibleLlmClient") LlmClient llmClient,
                                            LlmCacheRepository llmCacheRepository,
                                            KnowledgeBaseApi knowledgeBaseApi) {
        this.llmClient = llmClient;
        this.llmCacheRepository = llmCacheRepository;
        this.knowledgeBaseApi = knowledgeBaseApi;
    }

    /**
     * 问题改写：开关关闭 / query 空白 / 知识库未配置聊天模型时原样返回；开启时先查
     * {@code QUERY_REWRITE} 缓存，命中直返缓存响应，未命中渲染 {@code query_rewrite} 模板
     * 经 LLM 生成后回写缓存并返回 trim 产物；渲染或调用异常降级返回原始 query。
     *
     * @param query 用户原始问题
     * @param cfg   检索策略配置（rewriteQuestion 开关）
     * @param kbId  所属知识库ID（缓存库级隔离维度，同时是聊天模型与语言配置的真相源）
     * @return 改写后（或原样）的查询文本
     */
    @Override
    public String rewrite(String query, RetrievalStrategyConfig cfg, Long kbId) {
        return rewrite(query, cfg, kbId, null);
    }

    /**
     * 问题改写（带 LLM 缓存命中计数口径）：语义与三参重载一致，
     * {@code QUERY_REWRITE} 缓存命中时对 {@code cacheHits} 递增一次。
     *
     * @param query     用户原始问题
     * @param cfg       检索策略配置（rewriteQuestion 开关）
     * @param kbId      所属知识库ID（缓存库级隔离维度，同时是聊天模型与语言配置的真相源）
     * @param cacheHits LLM 缓存命中计数器（命中递增；传 null 时不统计）
     * @return 改写后（或原样）的查询文本
     */
    @Override
    public String rewrite(String query, RetrievalStrategyConfig cfg, Long kbId, AtomicInteger cacheHits) {
        if (ObjectUtils.isEmpty(cfg) || !Boolean.TRUE.equals(cfg.rewriteQuestion())) {
            return query;
        }
        if (StringUtils.isBlank(query)) {
            return query;
        }
        String chatProfileId = knowledgeBaseApi.findMediaModelProfileIdByKbId(kbId);
        if (StringUtils.isBlank(chatProfileId)) {
            log.warn("知识库未配置LLM模型，问题改写跳过并原样返回 query: kbId={}", kbId);
            return query;
        }
        String language = resolveLanguage(kbId);
        String cacheKey = buildCacheKey(query, language, chatProfileId);
        Optional<LlmCacheEntry> cached =
                llmCacheRepository.findByKbIdAndCacheKey(kbId, CacheType.QUERY_REWRITE, cacheKey);
        if (cached.isPresent()) {
            if (ObjectUtils.isNotEmpty(cacheHits)) {
                cacheHits.incrementAndGet();
            }
            return StringUtils.defaultString(cached.get().response());
        }
        return generateRewrite(query, kbId, cacheKey, language, chatProfileId);
    }

    /**
     * 双层关键词提取：显式关键词优先直返（不触发 LLM 与缓存）；非 MIX 策略返回空关键词对；
     * MIX 时先查 {@code KEYWORD_EXTRACT} 缓存命中回放，未命中渲染 {@code keywords_extraction}
     * 模板经 LLM 生成、容错解析、规范化后回写缓存。返回前统一执行空兜底（规则 4）。
     * <b>终止判定由编排层负责</b>：本方法在「hl、ll 皆空且查询长度 ≥ 50」时仅返回空关键词对，
     * 是否以 {@code fail_response} 终止检索流程由上层编排决定。
     *
     * @param query              用户查询文本（改写后 query）
     * @param explicitHlKeywords 显式高层关键词（任一显式列表非空即跳过 LLM，可空）
     * @param explicitLlKeywords 显式低层关键词（任一显式列表非空即跳过 LLM，可空）
     * @param cfg                检索策略配置（strategyType 决定是否触发 LLM）
     * @param kbId               所属知识库ID（缓存库级隔离维度，同时是聊天模型与语言配置的真相源）
     * @return 双层关键词对（可为空列表，由编排层按兜底语义处理）
     */
    @Override
    public KeywordPair extract(String query, List<String> explicitHlKeywords, List<String> explicitLlKeywords,
                               RetrievalStrategyConfig cfg, Long kbId) {
        return extract(query, explicitHlKeywords, explicitLlKeywords, cfg, kbId, null);
    }

    /**
     * 双层关键词提取（带 LLM 缓存命中计数口径）：语义与五参重载一致，
     * {@code KEYWORD_EXTRACT} 缓存命中回放时对 {@code cacheHits} 递增一次。
     *
     * @param query              用户查询文本（改写后 query）
     * @param explicitHlKeywords 显式高层关键词（任一显式列表非空即跳过 LLM，可空）
     * @param explicitLlKeywords 显式低层关键词（任一显式列表非空即跳过 LLM，可空）
     * @param cfg                检索策略配置（strategyType 决定是否触发 LLM）
     * @param kbId               所属知识库ID（缓存库级隔离维度，同时是聊天模型与语言配置的真相源）
     * @param cacheHits          LLM 缓存命中计数器（命中递增；传 null 时不统计）
     * @return 双层关键词对（可为空列表，由编排层按兜底语义处理）
     */
    @Override
    public KeywordPair extract(String query, List<String> explicitHlKeywords, List<String> explicitLlKeywords,
                               RetrievalStrategyConfig cfg, Long kbId, AtomicInteger cacheHits) {
        if (CollectionUtils.isNotEmpty(explicitHlKeywords) || CollectionUtils.isNotEmpty(explicitLlKeywords)) {
            List<String> hlKeywords = ObjectUtils.defaultIfNull(explicitHlKeywords, Collections.emptyList());
            List<String> llKeywords = ObjectUtils.defaultIfNull(explicitLlKeywords, Collections.emptyList());
            return new KeywordPair(hlKeywords, llKeywords);
        }
        if (ObjectUtils.isEmpty(cfg) || cfg.strategyType() != RetrievalStrategyType.MIX) {
            return new KeywordPair(List.of(), List.of());
        }
        if (StringUtils.isBlank(query)) {
            return new KeywordPair(List.of(), List.of());
        }
        String chatProfileId = knowledgeBaseApi.findMediaModelProfileIdByKbId(kbId);
        if (StringUtils.isBlank(chatProfileId)) {
            log.warn("知识库未配置LLM模型，关键词提取走空兜底规则: kbId={}", kbId);
            return applyEmptyFallback(query, new KeywordPair(List.of(), List.of()));
        }
        String language = resolveLanguage(kbId);
        String cacheKey = buildCacheKey(query, language, chatProfileId);
        Optional<LlmCacheEntry> cached =
                llmCacheRepository.findByKbIdAndCacheKey(kbId, CacheType.KEYWORD_EXTRACT, cacheKey);
        if (cached.isPresent()) {
            if (ObjectUtils.isNotEmpty(cacheHits)) {
                cacheHits.incrementAndGet();
            }
            return replayCachedKeywords(query, cached.get().response());
        }
        return generateKeywords(query, kbId, cacheKey, language, chatProfileId);
    }

    /**
     * 解析生效检索词语言（/ query-understanding 能力）：
     * 知识库语言全名优先（{@link KnowledgeBaseApi#findLanguageByKbId(Long)} 只读投影，
     * 与摄入端图谱语言同源），库未配置或读取失败回落 {@link RetrievalConstants#DEFAULT_LANGUAGE}。
     * <p>读取失败（数据损坏 / 瞬时库异常）记录告警并按缺省语言降级，维持本服务
     * 「不向编排层抛出异常」的既有降级口径。</p>
     *
     * @param kbId 所属知识库ID，可为空
     * @return 生效语言全名（非空白）
     */
    private String resolveLanguage(Long kbId) {
        String kbLanguage;
        try {
            kbLanguage = knowledgeBaseApi.findLanguageByKbId(kbId);
        } catch (RuntimeException e) {
            log.warn("知识库语言配置读取失败，回落全局默认语言: kbId={}", kbId, e);
            kbLanguage = null;
        }
        return StringUtils.defaultIfBlank(kbLanguage, RetrievalConstants.DEFAULT_LANGUAGE);
    }

    /**
     * 未命中缓存时生成改写产物：渲染模板 → LLM 调用 → 回写缓存 → 返回 trim 后文本；
     * 渲染或调用异常记录告警并降级返回原始 query（不阻断检索）。
     *
     * @param query         用户原始问题
     * @param kbId          所属知识库ID
     * @param cacheKey      规格缓存键
     * @param language      生效语言全名（与缓存键语言要素同源）
     * @param chatProfileId 知识库聊天模型 profileId（缓存键模型要素与 LLM 请求同源）
     * @return 改写后文本；异常时回退原始 query
     */
    private String generateRewrite(String query, Long kbId, String cacheKey, String language, String chatProfileId) {
        String prompt;
        LlmChatResult result;
        try {
            prompt = composeRewritePrompt(query, language);
            result = llmClient.chat(new LlmChatRequest(
                    kbId, chatProfileId, null, prompt, null, CacheType.QUERY_REWRITE));
        } catch (RuntimeException e) {
            log.warn("问题改写渲染或 LLM 调用失败，降级返回原始 query: kbId={}, query={}", kbId, query, e);
            return query;
        }
        String rewritten = StringUtils.trim(StringUtils.defaultString(result.text()));
        if (StringUtils.isBlank(rewritten)) {
            log.warn("问题改写 LLM 返回空白，降级返回原始 query: kbId={}", kbId);
            return query;
        }
        saveCache(kbId, cacheKey, CacheType.QUERY_REWRITE, prompt, result, chatProfileId);
        return rewritten;
    }

    /**
     * 渲染 {@code query_rewrite} 模板（变量口径：{query} / {language}）。
     * <p>模板套选择参（render 第二参）与 {@code {language}} 注入值同源取知识库语言全名
     * （{@code query_rewrite} 为双套自研模板，中文库取 zh 套）。</p>
     *
     * @param query    用户原始问题
     * @param language 生效语言全名
     * @return 渲染后的提示词
     */
    private String composeRewritePrompt(String query, String language) {
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put(VAR_QUERY, query);
        vars.put(VAR_LANGUAGE, language);
        return PromptCatalog.render(TEMPLATE_QUERY_REWRITE, language, vars);
    }

    /**
     * 未命中缓存时生成双层关键词：渲染模板 → LLM 调用 → 容错解析 → 规范化 → 回写缓存；
     * 渲染/调用异常或解析彻底失败均回退「整句作为 ll」并告警。
     * 解析失败不回写缓存，避免不可回放内容污染缓存。
     *
     * @param query         用户查询文本
     * @param kbId          所属知识库ID
     * @param cacheKey      规格缓存键
     * @param language      生效语言全名（与缓存键语言要素同源）
     * @param chatProfileId 知识库聊天模型 profileId（缓存键模型要素与 LLM 请求同源）
     * @return 规范化并空兜底后的关键词对
     */
    private KeywordPair generateKeywords(String query, Long kbId, String cacheKey, String language,
                                         String chatProfileId) {
        String prompt;
        LlmChatResult result;
        try {
            prompt = composeKeywordsPrompt(query, language);
            result = llmClient.chat(new LlmChatRequest(
                    kbId, chatProfileId, null, prompt, null, CacheType.KEYWORD_EXTRACT));
        } catch (RuntimeException e) {
            log.warn("关键词提取渲染或 LLM 调用失败，回退整句作为 ll: kbId={}, query={}", kbId, query, e);
            return applyEmptyFallback(query, new KeywordPair(List.of(), List.of()));
        }
        Optional<JsonNode> parsed = MultimodalJsonParser.parse(result.text());
        if (parsed.isEmpty()) {
            log.warn("关键词提取 JSON 容错链解析失败，回退整句作为 ll: kbId={}, 原文前缀=[{}]",
                    kbId, StringUtils.abbreviate(result.text(), LOG_ABBREVIATE_WIDTH));
            return applyEmptyFallback(query, new KeywordPair(List.of(), List.of()));
        }
        KeywordPair pair = normalizePair(parsed.get());
        saveCache(kbId, cacheKey, CacheType.KEYWORD_EXTRACT, prompt, result, chatProfileId);
        return applyEmptyFallback(query, pair);
    }

    /**
     * 渲染 {@code keywords_extraction} 模板（变量口径：
     * {query} / {examples} / {language}，examples 为固定输出格式模板）。
     * <p>{@code {language}} 注入知识库语言全名，指令 LLM 以图谱内容语言产出高低层关键词
     * （锁死「检索词语言 = 图谱语言」）；模板套选择参（render 第二参）与之同源。</p>
     *
     * @param query    用户查询文本
     * @param language 生效语言全名
     * @return 渲染后的提示词
     */
    private String composeKeywordsPrompt(String query, String language) {
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put(VAR_QUERY, query);
        vars.put(VAR_EXAMPLES, KEYWORDS_EXAMPLES);
        vars.put(VAR_LANGUAGE, language);
        return PromptCatalog.render(TEMPLATE_KEYWORDS_EXTRACTION, language, vars);
    }

    /**
     * 缓存命中回放：重新解析缓存中的 LLM 原始文本（容错链同口径）；解析失败同样回退
     * 「整句作为 ll」，保证命中路径与生成路径产物语义一致。
     *
     * @param query        用户查询文本
     * @param cachedResponse 缓存的 LLM 原始回复文本
     * @return 规范化并空兜底后的关键词对
     */
    private KeywordPair replayCachedKeywords(String query, String cachedResponse) {
        Optional<JsonNode> parsed = MultimodalJsonParser.parse(cachedResponse);
        if (parsed.isEmpty()) {
            log.warn("关键词缓存内容不可解析，回退整句作为 ll: query={}", query);
            return applyEmptyFallback(query, new KeywordPair(List.of(), List.of()));
        }
        return applyEmptyFallback(query, normalizePair(parsed.get()));
    }

    /**
     * 解析 JSON 根节点为规范化关键词对（hl/ll 两字段，缺失或非数组按空列表处理）。
     *
     * @param root 解析成功的 JSON 根节点
     * @return 规范化后的关键词对（未执行空兜底）
     */
    private KeywordPair normalizePair(JsonNode root) {
        return new KeywordPair(readKeywords(root, FIELD_HIGH_LEVEL_KEYWORDS),
                readKeywords(root, FIELD_LOW_LEVEL_KEYWORDS));
    }

    /**
     * 读取指定字段的关键词数组并逐项规范化（切分 / strip / 去空白）。
     *
     * @param root  JSON 根节点
     * @param field 字段名
     * @return 规范化关键词列表（字段缺失或非数组时为空列表）
     */
    private List<String> readKeywords(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (ObjectUtils.isEmpty(node) || !node.isArray()) {
            return List.of();
        }
        List<String> keywords = new ArrayList<>();
        for (JsonNode item : node) {
            keywords.addAll(splitAndNormalize(item.asText()));
        }
        return keywords;
    }

    /**
     * 单关键词规范化：按 {@code [，,;\n]+} 切分、逐项 strip、去除空白项。
     *
     * @param keyword LLM 返回的单个关键词项（可能内含多分隔符拼接的复合串）
     * @return 切分规范化后的关键词片段列表
     */
    private List<String> splitAndNormalize(String keyword) {
        if (StringUtils.isBlank(keyword)) {
            return List.of();
        }
        return Arrays.stream(KEYWORD_SPLIT_PATTERN.split(keyword))
                .map(StringUtils::strip)
                .filter(StringUtils::isNotBlank)
                .toList();
    }

    /**
     * 空关键词兜底：ll 为空且查询长度小于阈值时回退「整句作为 ll」；
     * hl、ll 皆空且长度不小于阈值时保持空对返回（终止判定由编排层负责）。
     *
     * @param query 用户查询文本
     * @param pair  规范化后的关键词对
     * @return 兜底处理后的关键词对
     */
    private KeywordPair applyEmptyFallback(String query, KeywordPair pair) {
        if (CollectionUtils.isNotEmpty(pair.ll())) {
            return pair;
        }
        if (query.length() < SHORT_QUERY_LENGTH_THRESHOLD) {
            return new KeywordPair(pair.hl(), List.of(query));
        }
        return pair;
    }

    /**
     * 构建规格缓存键：{@code MD5(model + query + language + llmIdentity)}，
     * model 与 llmIdentity 均取知识库聊天模型 profileId
     * （采用模型 profile 标识，读写同口径保证确定性命中），
     * language 取生效的语言全名（与 {@code {language}} 注入同源，库间语言不同互不串键），
     * 要素间以 {@link #KEY_PART_SEPARATOR} 连接。
     *
     * @param query         用户查询文本
     * @param language      生效语言全名
     * @param chatProfileId 知识库聊天模型 profileId（模型与 llmIdentity 两要素同源值）
     * @return 32 位 MD5 hex 缓存键
     */
    private String buildCacheKey(String query, String language, String chatProfileId) {
        String raw = String.join(KEY_PART_SEPARATOR, chatProfileId, query, language, chatProfileId);
        return DigestUtils.md5DigestAsHex(raw.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 回写缓存条目（prompt 存渲染后全文、response 存 LLM 原始文本，供回放按同口径重解析）；
     * 回写失败仅告警，不影响本次结果返回。
     *
     * @param kbId          所属知识库ID
     * @param cacheKey      规格缓存键（32 位 MD5 hex）
     * @param cacheType     缓存分类（读、写三元组必须与查询侧一致才能命中回放）
     * @param prompt        渲染后的完整提示词
     * @param result        LLM 调用结果
     * @param chatProfileId 知识库聊天模型 profileId（缓存条目 model 列，与键模型要素同源）
     */
    private void saveCache(Long kbId, String cacheKey, CacheType cacheType, String prompt, LlmChatResult result,
                           String chatProfileId) {
        try {
            llmCacheRepository.saveIfAbsent(LlmCacheEntry.create(kbId, cacheKey, cacheType, chatProfileId,
                    prompt, result.text(), result.totalTokens()));
        } catch (Exception e) {
            log.warn("llm_cache 回写失败（不影响本次结果）: kbId={}, cacheType={}, cacheKey={}",
                    kbId, cacheType, cacheKey, e);
        }
    }
}
