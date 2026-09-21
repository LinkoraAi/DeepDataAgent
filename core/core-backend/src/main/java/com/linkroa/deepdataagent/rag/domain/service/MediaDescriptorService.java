package com.linkroa.deepdataagent.rag.domain.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.linkroa.deepdataagent.knowledgebase.application.port.KbAssetStoragePort;
import com.linkroa.deepdataagent.rag.domain.enums.CacheType;
import com.linkroa.deepdataagent.rag.domain.model.ContentBlockVO;
import com.linkroa.deepdataagent.rag.domain.model.MediaDescriptionVO;
import com.linkroa.deepdataagent.rag.domain.port.LlmCacheKeyProvider;
import com.linkroa.deepdataagent.rag.domain.port.LlmChatRequest;
import com.linkroa.deepdataagent.rag.domain.port.LlmChatResult;
import com.linkroa.deepdataagent.rag.domain.port.LlmClient;
import com.linkroa.deepdataagent.rag.domain.port.LlmImage;
import com.linkroa.deepdataagent.rag.domain.port.MultimodalConstraints;
import com.linkroa.deepdataagent.rag.infrastructure.prompts.catalog.PromptCatalog;
import com.linkroa.deepdataagent.rag.infrastructure.prompts.catalog.PromptTemplates;
import io.agentscope.extensions.model.openai.OpenAIClient;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 媒体描述生成服务（多模态 7-Stage 管线 Stage1）。
 * <p>按 {@link ContentBlockVO#getType()} 分流为 image / table / equation / generic 四类：</p>
 * <ul>
 *   <li>image / table / equation：渲染系统提示（7-1）+ 分析用户提示（7-2/7-3/7-4，
 *       meta 存在邻近文本时切 {@code *_with_context} 变体），经 {@link LlmClient} 端口调用
 *       （缓存在 CachingLlmClient 装饰器内嵌，本服务不重复实现），响应文本经
 *       {@link MultimodalJsonParser} 五级容错链解析出描述字段；</li>
 *   <li>generic：内容已在块原文中、无图片引用语义，直接走确定性本地兜底描述
 *       （不调 LLM），行为可离线复现。</li>
 * </ul>
 * <p><b>媒体描述携带原图（真看图）</b>：
 * 块 meta 携带可解析的对象存储引用（{@code mediaObjectKey}，桶概念已退役，
 * Stage 1a 图片持久化产物）时，经 {@link KbAssetStoragePort} 读取原图字节，
 * 以 {@link LlmImage} 多模态请求（单次至多 1 图，上限见 {@link MultimodalConstraints}）
 * 同时携带原图与模板渲染文本调用 VLM；三级降级链保证描述生成永不被图片链路阻断：</p>
 * <ol>
 *   <li>无引用键（存量数据或图片持久化失败）→ 静默维持纯文本请求，行为与升级前一致；</li>
 *   <li>有引用但对象读取失败/内容为空/超单图字节上限 → 跳过该图回落纯文本请求并 WARN 留痕
 *       （降级分类：读取失败 / 超限）；</li>
 *   <li>LLM 调用异常、空响应或 JSON 解析链全失败（解析失败但文本非空时保留整段
 *       文本作详描）时，主实体类型固定取块类型小写，主实体名按
 *       「meta 预置 → 题注截断 → 文件名 → 类型+页码」确定性派生，保证 Stage2 模板装配永不为空。</li>
 * </ol>
 * <p><b>主实体命名一次定形</b>：无论模型识别名还是兜底派生名，主实体名在本服务的描述产物
 * 构建处一次定形为「裸名 + 内容类型后缀」（{@code 裸名 (image/table/equation/generic)}）——
 * 后缀取内容块类型而非模型自报的实体类型（本服务已刻意不采信模型返回的类型标签，后缀同源
 * 才自洽），使主实体与正文抽取到的同名实体在图谱中天然区隔；名称长度截断在后缀拼接之前完成
 * 并为其留位，最终名永不因截断丢失类型后缀。全链路只消费该定形后的 {@code entityName}。</p>
 *
 * <p><b>缓存键出参（媒体归属延迟登记的前提）</b>：媒体描述发生在切片落库之前，调用点此刻
 * 拿不到分块主键，故本服务不就地登记缓存归属，而是经 {@link LlmCacheKeyProvider}
 * <b>只读</b>算出本次描述所用的缓存键并写入调用方传入的出参引用（{@code cacheKeyRef}，
 * 可为空表示不采集），由摄入管线在落库拿到真实分块主键后补登记。该计算不触发任何缓存读写、
 * 不改变命中判定；{@code LlmChatRequest} 的归因字段在媒体路径上恒为空。</p>
 *
 * @author DeepDataAgent
 */
@Component
public class MediaDescriptorService {

    /** 日志器 */
    private static final Logger log = LoggerFactory.getLogger(MediaDescriptorService.class);

    /** 用户模板：图片分析 */
    static final String TEMPLATE_VISION_PROMPT = PromptTemplates.VISION_PROMPT;
    /** 用户模板：图片分析（含邻近上下文） */
    static final String TEMPLATE_VISION_PROMPT_WITH_CONTEXT = PromptTemplates.VISION_PROMPT_WITH_CONTEXT;
    /** 用户模板：表格分析 */
    static final String TEMPLATE_TABLE_PROMPT = PromptTemplates.TABLE_PROMPT;
    /** 用户模板：表格分析（含邻近上下文） */
    static final String TEMPLATE_TABLE_PROMPT_WITH_CONTEXT = PromptTemplates.TABLE_PROMPT_WITH_CONTEXT;
    /** 用户模板：公式分析 */
    static final String TEMPLATE_EQUATION_PROMPT = PromptTemplates.EQUATION_PROMPT;
    /** 用户模板：公式分析（含邻近上下文） */
    static final String TEMPLATE_EQUATION_PROMPT_WITH_CONTEXT = PromptTemplates.EQUATION_PROMPT_WITH_CONTEXT;

    /** 系统模板：图片分析（有图可看） */
    static final String SYSTEM_IMAGE_ANALYSIS = PromptTemplates.IMAGE_ANALYSIS_SYSTEM;
    /** 系统模板：图片分析（无图可看，基于文本信息推断） */
    static final String SYSTEM_IMAGE_ANALYSIS_FALLBACK = PromptTemplates.IMAGE_ANALYSIS_FALLBACK_SYSTEM;
    /** 系统模板：表格分析 */
    static final String SYSTEM_TABLE_ANALYSIS = PromptTemplates.TABLE_ANALYSIS_SYSTEM;
    /** 系统模板：公式分析 */
    static final String SYSTEM_EQUATION_ANALYSIS = PromptTemplates.EQUATION_ANALYSIS_SYSTEM;

    /** 占位符变量名：主实体名 */
    private static final String VAR_ENTITY_NAME = "entity_name";
    /** 占位符变量名：章节路径 */
    private static final String VAR_SECTION_PATH = "section_path";
    /** 占位符变量名：图片路径 */
    private static final String VAR_IMAGE_PATH = "image_path";
    /** 占位符变量名：图片题注 */
    private static final String VAR_CAPTIONS = "captions";
    /** 占位符变量名：图片脚注 */
    private static final String VAR_FOOTNOTES = "footnotes";
    /** 占位符变量名：邻近上下文 */
    private static final String VAR_CONTEXT = "context";
    /** 占位符变量名：表格截图路径 */
    private static final String VAR_TABLE_IMG_PATH = "table_img_path";
    /** 占位符变量名：表格题注 */
    private static final String VAR_TABLE_CAPTION = "table_caption";
    /** 占位符变量名：表格结构体 */
    private static final String VAR_TABLE_BODY = "table_body";
    /** 占位符变量名：表格脚注 */
    private static final String VAR_TABLE_FOOTNOTE = "table_footnote";
    /** 占位符变量名：公式原文 */
    private static final String VAR_EQUATION_TEXT = "equation_text";
    /** 占位符变量名：公式格式 */
    private static final String VAR_EQUATION_FORMAT = "equation_format";

    /** 变量缺失时的模板占位值（Prompt 渲染 fail-fast，禁止注入 null/空串歧义） */
    private static final String MISSING_VALUE_PLACEHOLDER = "N/A";
    /** 公式默认格式 */
    private static final String DEFAULT_EQUATION_FORMAT = "latex";
    /** 兜底实体名的默认页码 */
    private static final String DEFAULT_PAGE = "0";
    /** 题注派生主实体名的最大长度 */
    private static final int ENTITY_NAME_MAX_LENGTH = 60;
    /** 兜底实体名连接符（类型 + 页码） */
    private static final String ENTITY_NAME_PAGE_CONNECTOR = "_p";
    /** 主实体名内容类型后缀模板（前空格、括号包裹块类型小写，与上游 RAG-Anything 命名一致） */
    private static final String ENTITY_TYPE_SUFFIX_TEMPLATE = " (%s)";

    /** 原图 contentType 缺省值（对象元数据不可得且后缀无法识别时按二进制流传递） */
    private static final String CONTENT_TYPE_DEFAULT = "application/octet-stream";
    /** 对象键扩展名分隔符 */
    private static final String FILE_EXTENSION_SEPARATOR = ".";
    /** 图片后缀 → contentType 映射（与 Stage 1a 持久化侧 {@code MediaImagePersistenceService} 同表，保证读写一致） */
    private static final Map<String, String> CONTENT_TYPE_BY_EXTENSION = Map.of(
            "png", "image/png",
            "jpg", "image/jpeg",
            "jpeg", "image/jpeg",
            "gif", "image/gif",
            "webp", "image/webp");

    /** LLM 对话端口（内嵌缓存回放） */
    private final LlmClient llmClient;

    /** 知识库对象资产存储访问端口（跨 BC 消费）：按媒体引用读取原图字节（真看图） */
    private final KbAssetStoragePort kbAssetStoragePort;

    /** 缓存键只读计算端口（归属登记所需的键由本服务算出并带出，落库后补登记） */
    private final LlmCacheKeyProvider llmCacheKeyProvider;

    /**
     * 构造描述生成服务。
     *
     * @param llmClient          LLM 对话端口
     * @param kbAssetStoragePort 对象资产存储访问端口（读取媒体引用原图；进程内实现恒有 bean）
     * @param llmCacheKeyProvider 缓存键只读计算端口（与缓存客户端同一处算法）
     */
    public MediaDescriptorService(LlmClient llmClient, KbAssetStoragePort kbAssetStoragePort,
                                  LlmCacheKeyProvider llmCacheKeyProvider) {
        this.llmClient = llmClient;
        this.kbAssetStoragePort = kbAssetStoragePort;
        this.llmCacheKeyProvider = llmCacheKeyProvider;
    }

    /**
     * 为多模态内容块生成结构化描述（Stage1 入口）。
     *
     * @param kbId           所属知识库ID（缓存键隔离维度，必填）
     * @param modelProfileId 模型配置 profileId（必填）
     * @param language       提示词语言码（zh 中文、其余英文，必填）
     * @param block          多模态内容块（IMAGE / TABLE / EQUATION / GENERIC）
     * @return 媒体描述（永不为空，主实体名/类型经确定性兜底）
     * @throws IllegalArgumentException 入参非法或块类型非多模态（TEXT / 未知）
     */
    public MediaDescriptionVO describe(Long kbId, String modelProfileId, String language, ContentBlockVO block) {
        return describe(kbId, modelProfileId, language, block, null, null);
    }

    /**
     * 为多模态内容块生成结构化描述（可挂 LLM 缓存命中计数器）。
     *
     * @param kbId           所属知识库ID（缓存键隔离维度，必填）
     * @param modelProfileId 模型配置 profileId（必填）
     * @param language       提示词语言码（zh 中文、其余英文，必填）
     * @param block          多模态内容块（IMAGE / TABLE / EQUATION / GENERIC）
     * @param llmCacheHits   LLM 缓存命中计数器（可空；非空时每次缓存回放递增一次，
     *                       供摄入收尾结构化日志汇总）
     * @return 媒体描述（永不为空，主实体名/类型经确定性兜底）
     * @throws IllegalArgumentException 入参非法或块类型非多模态（TEXT / 未知）
     */
    public MediaDescriptionVO describe(Long kbId, String modelProfileId, String language,
                                       ContentBlockVO block, AtomicInteger llmCacheHits) {
        return describe(kbId, modelProfileId, language, block, null, llmCacheHits);
    }

    /**
     * 为多模态内容块生成结构化描述（携带缓存键出参 + 可挂 LLM 缓存命中计数器）。
     * <p><b>缓存键出参</b>：{@code cacheKeyRef} 非空时，本服务在构建出描述请求后经
     * {@link LlmCacheKeyProvider} 只读算出本次调用所用的缓存键并写入该引用；
     * 该计算不查询 / 不写入任何缓存行、不改变命中判定，仅供调用方在拿到真实分块主键后
     * 补登记「分块 → 缓存行」归属（媒体描述发生在切片落库之前，此刻尚无分块主键）。
     * 为空表示调用方不采集键值（此时不发起任何键计算）。</p>
     * <p><b>归因字段恒空</b>：媒体路径不携带 {@link LlmChatRequest#attributionChunkId()}
     * （描述调用点无分块主键），请求构造经不携带归因的便捷构造器完成。</p>
     *
     * @param kbId           所属知识库ID（缓存键隔离维度，必填）
     * @param modelProfileId 模型配置 profileId（必填）
     * @param language       提示词语言码（zh 中文、其余英文，必填）
     * @param block          多模态内容块（IMAGE / TABLE / EQUATION / GENERIC）
     * @param cacheKeyRef    缓存键出参（可空；非空时写入本次调用所用的缓存键，供落库后补登记归属）
     * @param llmCacheHits   LLM 缓存命中计数器（可空；非空时每次缓存回放递增一次，
     *                       供摄入收尾结构化日志汇总）
     * @return 媒体描述（永不为空，主实体名/类型经确定性兜底）
     * @throws IllegalArgumentException 入参非法或块类型非多模态（TEXT / 未知）
     */
    public MediaDescriptionVO describe(Long kbId, String modelProfileId, String language, ContentBlockVO block,
                                       AtomicReference<String> cacheKeyRef, AtomicInteger llmCacheHits) {
        if (ObjectUtils.isEmpty(kbId)) {
            throw new IllegalArgumentException("kbId 不能为空");
        }
        if (StringUtils.isBlank(modelProfileId)) {
            throw new IllegalArgumentException("modelProfileId 不能为空");
        }
        if (StringUtils.isBlank(language)) {
            throw new IllegalArgumentException("language 不能为空");
        }
        if (ObjectUtils.isEmpty(block)) {
            throw new IllegalArgumentException("内容块不能为空");
        }
        String type = block.type();
        if (ContentBlockVO.TYPE_GENERIC.equals(type)) {
            return genericFallbackDescription(block);
        }
        if (!block.isMultimodal()) {
            throw new IllegalArgumentException("非多模态内容块不支持描述生成: " + type);
        }
        String neighborText = MultimodalMetaKeys.metaString(block, MultimodalMetaKeys.META_KEY_NEIGHBOR_TEXT);
        // 实际附图解析提前到系统模板选择之前，
        // 模板跟随真实载荷（读失败/超限降级为 null 时选无图 fallback 系），同一请求内不再重复读对象
        LlmImage describedImage = resolveDescribedImage(block);
        Map<String, String> vars = buildPromptVars(type, block, neighborText);
        String userPrompt = PromptCatalog.render(selectUserTemplate(type, neighborText), language, vars);
        String systemPrompt = PromptCatalog.render(selectSystemTemplate(type, describedImage), language, Map.of());
        return describeViaLlm(kbId, modelProfileId, type, block, systemPrompt, userPrompt, describedImage,
                cacheKeyRef, llmCacheHits);
    }

    /**
     * 调用 LLM 并解析响应，异常/空响应/解析失败均走确定性兜底。
     * <p>原图引用解析已在入口完成（真看图，+  模板一致性）：
     * 引用可解析且字节合规时以多模态请求携带原图（单次至多 1 图）；无引用/读取失败/超限
     * 按类回落纯文本请求（详见 {@link #resolveDescribedImage}），图片链路任何失败均不阻断描述生成。</p>
     *
     * @param kbId               知识库ID
     * @param modelProfileId     模型引用
     * @param type               块类型
     * @param block              内容块
     * @param systemPrompt       系统提示词
     * @param userPrompt         用户提示词
     * @param describedImage     入口预解析的实际附图载荷（可为 {@code null}，null 即纯文本请求）
     * @param cacheKeyRef        缓存键出参（可空；非空时写入本次调用所用的缓存键）
     * @param llmCacheHits       LLM 缓存命中计数器（可空）
     * @return 媒体描述
     */
    private MediaDescriptionVO describeViaLlm(Long kbId, String modelProfileId, String type,
                                              ContentBlockVO block, String systemPrompt, String userPrompt,
                                              LlmImage describedImage, AtomicReference<String> cacheKeyRef,
                                              AtomicInteger llmCacheHits) {
        LlmChatRequest request = buildChatRequest(kbId, modelProfileId, systemPrompt, userPrompt, describedImage);
        LlmChatResult result;
        try {
            // 键采集与调用同处一个 try：模型引用不可解析等异常同样走确定性兜底，不因旁路采集改变失败语义
            captureCacheKey(request, cacheKeyRef);
            result = llmClient.chat(request);
        } catch (RuntimeException e) {
            log.warn("媒体描述 LLM 调用失败，走确定性兜底：type=[{}] msg=[{}]", type, e.getMessage());
            return deterministicFallbackDescription(type, block);
        }
        if (ObjectUtils.isNotEmpty(result) && result.cacheHit() && ObjectUtils.isNotEmpty(llmCacheHits)) {
            llmCacheHits.incrementAndGet();
        }
        if (ObjectUtils.isEmpty(result) || StringUtils.isBlank(result.text())) {
            log.warn("媒体描述 LLM 返回空，走确定性兜底：type=[{}]", type);
            return deterministicFallbackDescription(type, block);
        }
        return toDescription(type, block, result.text());
    }

    /**
     * 采集本次描述调用所用的缓存键（只读，旁路出参）。
     * <p>{@code cacheKeyRef} 为空时直接返回，不发起任何键计算（不触达模型配置解析）；
     * 非空时经 {@link LlmCacheKeyProvider} 以与缓存客户端同一处算法算出键值写入引用——
     * 该键仅用于调用方在落库后补登记归属，不参与本次调用的命中判定。</p>
     *
     * @param request     本次描述请求（非空）
     * @param cacheKeyRef 缓存键出参（可空）
     */
    private void captureCacheKey(LlmChatRequest request, AtomicReference<String> cacheKeyRef) {
        if (ObjectUtils.isEmpty(cacheKeyRef)) {
            return;
        }
        cacheKeyRef.set(llmCacheKeyProvider.cacheKeyOf(request));
    }

    /**
     * 构造描述请求：入口预解析出原图载荷时走多模态形态（文本 + 1 图），否则维持纯文本形态
     * （同一描述请求内不再重复读取对象）。
     * <p>请求经不携带归因字段的便捷构造器构建：媒体描述发生在切片落库之前，
     * 此刻无分块主键可供归因，其归属改由摄入管线在落库后依据 {@code cacheKeyRef} 带出的键补登记
     * （见 {@link #captureCacheKey}）。</p>
     *
     * @param kbId           知识库ID
     * @param modelProfileId 模型引用
     * @param systemPrompt   系统提示词
     * @param userPrompt     用户提示词
     * @param image          入口预解析的实际附图载荷（可为 {@code null}）
     * @return LLM 对话请求
     */
    private LlmChatRequest buildChatRequest(Long kbId, String modelProfileId, String systemPrompt,
                                            String userPrompt, LlmImage image) {
        if (ObjectUtils.isEmpty(image)) {
            return new LlmChatRequest(kbId, modelProfileId, systemPrompt, userPrompt, null, CacheType.EXTRACT);
        }
        // 单次描述至多携带 1 图（MultimodalConstraints.MAX_IMAGES_PER_DESCRIBE_REQUEST），避免描述归属互串
        return new LlmChatRequest(kbId, modelProfileId, systemPrompt, userPrompt, null, CacheType.EXTRACT,
                List.of(image));
    }

    /**
     * 解析多模态块携带的原图引用并读取为图片载荷（三级降级的第 1、2 级在此实现）。
     * <ul>
     *   <li>meta 缺 {@code mediaObjectKey} 键（存量数据或持久化失败）
     *       → 返回 null，静默维持升级前纯文本行为（不 WARN，避免存量噪音）；</li>
     *   <li>引用读取抛异常 / 对象不存在 / 字节为空 → WARN（降级分类：读取失败）后返回 null；</li>
     *   <li>字节超 {@link MultimodalConstraints#MAX_IMAGE_BYTES} → WARN（降级分类：超限）后返回 null。</li>
     * </ul>
     *
     * @param block 多模态内容块
     * @return 图片载荷；无引用或降级时为 {@code null}（调用方回落纯文本请求）
     */
    private LlmImage resolveDescribedImage(ContentBlockVO block) {
        String objectKey = MultimodalMetaKeys.metaString(block, MultimodalMetaKeys.META_KEY_MEDIA_OBJECT_KEY);
        if (StringUtils.isBlank(objectKey)) {
            return null;
        }
        byte[] content;
        try (InputStream in = kbAssetStoragePort.open(objectKey)
                .map(KbAssetStoragePort.OpenedObject::content).orElse(null)) {
            if (ObjectUtils.isEmpty(in)) {
                log.warn("媒体描述原图读取失败（对象不存在或流缺失），降级为纯文本描述：objectKey=[{}]", objectKey);
                return null;
            }
            // 有界读取——至多读「上限 + 1」字节即判超限，超大对象不再全量缓冲入堆
            content = in.readNBytes((int) (MultimodalConstraints.MAX_IMAGE_BYTES + 1));
        } catch (IOException | RuntimeException e) {
            log.warn("媒体描述原图读取失败，降级为纯文本描述：objectKey=[{}] msg=[{}]", objectKey, e.getMessage());
            return null;
        }
        if (ObjectUtils.isEmpty(content)) {
            log.warn("媒体描述原图读取失败（对象内容为空），降级为纯文本描述：objectKey=[{}]", objectKey);
            return null;
        }
        if (content.length > MultimodalConstraints.MAX_IMAGE_BYTES) {
            log.warn("媒体描述原图超单图字节上限（{} bytes），跳过该图降级为纯文本描述：objectKey=[{}]",
                    MultimodalConstraints.MAX_IMAGE_BYTES, objectKey);
            return null;
        }
        return new LlmImage(resolveImageContentType(objectKey), content);
    }

    /**
     * 解析原图 contentType：按对象键后缀推断（不再读取对象存储元数据，
     * 与 Stage 1a 持久化侧写入类型映射同表，读写口径一致）。
     *
     * @param objectKey 对象键
     * @return contentType（永非空白；无法识别为 {@link #CONTENT_TYPE_DEFAULT}）
     */
    private String resolveImageContentType(String objectKey) {
        String extension = StringUtils.lowerCase(
                StringUtils.substringAfterLast(objectKey, FILE_EXTENSION_SEPARATOR));
        if (StringUtils.isBlank(extension)) {
            return CONTENT_TYPE_DEFAULT;
        }
        return CONTENT_TYPE_BY_EXTENSION.getOrDefault(extension, CONTENT_TYPE_DEFAULT);
    }

    /**
     * 响应文本 → 描述值对象：容错解析成功按字段映射，失败但文本非空时整段保留为详描。
     *
     * @param type    块类型
     * @param block   内容块
     * @param rawText LLM 响应原文
     * @return 媒体描述
     */
    private MediaDescriptionVO toDescription(String type, ContentBlockVO block, String rawText) {
        Optional<JsonNode> parsed = MultimodalJsonParser.parse(rawText);
        if (parsed.isEmpty()) {
            log.warn("媒体描述 JSON 容错解析失败，整段文本保留为详描：type=[{}]", type);
            // 解析失败仍走命名定形链：兜底派生名同样带内容类型后缀
            return new MediaDescriptionVO(applyTypeSuffix(type, deriveEntityName(type, block)),
                    typeLabel(type), StringUtils.EMPTY, rawText, true);
        }
        JsonNode root = parsed.get();
        JsonNode entityInfo = root.path(MultimodalJsonParser.FIELD_ENTITY_INFO);
        String entityName = StringUtils.trimToNull(textOf(entityInfo, MultimodalJsonParser.FIELD_ENTITY_NAME));
        if (StringUtils.isBlank(entityName)) {
            entityName = deriveEntityName(type, block);
        }
        // 主实体类型不采信模型返回值，固定取块类型小写；名称在此一次定形（裸名 + 内容类型后缀）
        return new MediaDescriptionVO(applyTypeSuffix(type, entityName), typeLabel(type),
                StringUtils.defaultString(textOf(entityInfo, MultimodalJsonParser.FIELD_SUMMARY)),
                StringUtils.defaultString(textOf(root, MultimodalJsonParser.FIELD_DETAILED_DESCRIPTION)),
                true);
    }

    /**
     * 读取 JSON 文本字段值。
     *
     * @param node  宿主节点（可为 MissingNode）
     * @param field 字段名
     * @return 字段文本；非文本或缺失返回 {@code null}
     */
    private String textOf(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isTextual()) {
            return value.asText();
        }
        return null;
    }

    /**
     * 组装用户模板变量（全部归一为非空白值，适配渲染端口 fail-fast 契约）。
     *
     * @param type        块类型
     * @param block       内容块
     * @param neighborText 邻近文本（可空）
     * @return 模板变量表
     */
    private Map<String, String> buildPromptVars(String type, ContentBlockVO block, String neighborText) {
        Map<String, String> vars = new HashMap<>();
        putVar(vars, VAR_ENTITY_NAME, deriveEntityName(type, block));
        putVar(vars, VAR_SECTION_PATH,
                MultimodalMetaKeys.metaString(block, MultimodalMetaKeys.META_KEY_SECTION_PATH));
        if (StringUtils.isNotBlank(neighborText)) {
            putVar(vars, VAR_CONTEXT, neighborText);
        }
        switch (type) {
            case ContentBlockVO.TYPE_IMAGE -> buildImageVars(vars, block);
            case ContentBlockVO.TYPE_TABLE -> buildTableVars(vars, block);
            default -> buildEquationVars(vars, block);
        }
        return vars;
    }

    /**
     * 图片块专属变量：image_path / captions / footnotes。
     *
     * @param vars  变量表
     * @param block 内容块
     */
    private void buildImageVars(Map<String, String> vars, ContentBlockVO block) {
        putVar(vars, VAR_IMAGE_PATH, MultimodalMetaKeys.metaString(block,
                MultimodalMetaKeys.META_KEY_IMG_PATH, MultimodalMetaKeys.META_KEY_IMAGE_PATH));
        putVar(vars, VAR_CAPTIONS, MultimodalMetaKeys.metaString(block,
                MultimodalMetaKeys.META_KEY_IMAGE_CAPTION, MultimodalMetaKeys.META_KEY_IMAGE_CAPTIONS));
        putVar(vars, VAR_FOOTNOTES, MultimodalMetaKeys.metaString(block,
                MultimodalMetaKeys.META_KEY_IMAGE_FOOTNOTE));
    }

    /**
     * 表格块专属变量：table_img_path / table_caption / table_body / table_footnote。
     *
     * @param vars  变量表
     * @param block 内容块
     */
    private void buildTableVars(Map<String, String> vars, ContentBlockVO block) {
        putVar(vars, VAR_TABLE_IMG_PATH, MultimodalMetaKeys.metaString(block,
                MultimodalMetaKeys.META_KEY_IMG_PATH));
        putVar(vars, VAR_TABLE_CAPTION, MultimodalMetaKeys.metaString(block,
                MultimodalMetaKeys.META_KEY_TABLE_CAPTION));
        putVar(vars, VAR_TABLE_BODY, StringUtils.defaultIfBlank(
                MultimodalMetaKeys.metaString(block, MultimodalMetaKeys.META_KEY_TABLE_BODY), block.text()));
        putVar(vars, VAR_TABLE_FOOTNOTE, MultimodalMetaKeys.metaString(block,
                MultimodalMetaKeys.META_KEY_TABLE_FOOTNOTE));
    }

    /**
     * 公式块专属变量：equation_text / equation_format。
     *
     * @param vars  变量表
     * @param block 内容块
     */
    private void buildEquationVars(Map<String, String> vars, ContentBlockVO block) {
        putVar(vars, VAR_EQUATION_TEXT, StringUtils.defaultIfBlank(
                MultimodalMetaKeys.metaString(block, MultimodalMetaKeys.META_KEY_TEXT,
                        MultimodalMetaKeys.META_KEY_LATEX), block.text()));
        putVar(vars, VAR_EQUATION_FORMAT, StringUtils.defaultIfBlank(
                MultimodalMetaKeys.metaString(block, MultimodalMetaKeys.META_KEY_FORMAT),
                DEFAULT_EQUATION_FORMAT));
    }

    /**
     * 写入归一化变量：空白/ null 统一替换为缺失占位值。
     *
     * @param vars  变量表
     * @param key   变量名
     * @param value 变量原始值（可空）
     */
    private void putVar(Map<String, String> vars, String key, String value) {
        vars.put(key, StringUtils.defaultIfBlank(value, MISSING_VALUE_PLACEHOLDER));
    }

    /**
     * 选择用户分析模板：存在邻近文本上下文时用 {@code *_with_context} 变体。
     *
     * @param type         块类型
     * @param neighborText 邻近文本（可空）
     * @return 模板名
     */
    private String selectUserTemplate(String type, String neighborText) {
        boolean withContext = StringUtils.isNotBlank(neighborText);
        if (ContentBlockVO.TYPE_IMAGE.equals(type)) {
            return withContext ? TEMPLATE_VISION_PROMPT_WITH_CONTEXT : TEMPLATE_VISION_PROMPT;
        }
        if (ContentBlockVO.TYPE_TABLE.equals(type)) {
            return withContext ? TEMPLATE_TABLE_PROMPT_WITH_CONTEXT : TEMPLATE_TABLE_PROMPT;
        }
        return withContext ? TEMPLATE_EQUATION_PROMPT_WITH_CONTEXT : TEMPLATE_EQUATION_PROMPT;
    }

    /**
     * 选择系统提示模板：图片块按<b>实际附图结果</b>选择——
     * 附图载荷非空才选「原图解读」系模板，未实际附图（无引用、读取失败、超限降级）选
     * 无图 fallback 系，消除「有图可看」承诺与无图载荷错配；表格/公式模板分支不变。
     *
     * @param type  块类型
     * @param image 入口预解析的实际附图载荷（可为 {@code null}）
     * @return 系统模板名
     */
    private String selectSystemTemplate(String type, LlmImage image) {
        if (ContentBlockVO.TYPE_TABLE.equals(type)) {
            return SYSTEM_TABLE_ANALYSIS;
        }
        if (ContentBlockVO.TYPE_EQUATION.equals(type)) {
            return SYSTEM_EQUATION_ANALYSIS;
        }
        if (ObjectUtils.isEmpty(image)) {
            return SYSTEM_IMAGE_ANALYSIS_FALLBACK;
        }
        return SYSTEM_IMAGE_ANALYSIS;
    }

    /**
     * 确定性兜底描述（LLM 链失败）：详描取块内主要原始信息。
     *
     * @param type  块类型
     * @param block 内容块
     * @return 兜底描述（llmGenerated=false）
     */
    private MediaDescriptionVO deterministicFallbackDescription(String type, ContentBlockVO block) {
        String primary = switch (type) {
            case ContentBlockVO.TYPE_IMAGE -> StringUtils.defaultIfBlank(
                    MultimodalMetaKeys.metaString(block, MultimodalMetaKeys.META_KEY_IMAGE_CAPTION,
                            MultimodalMetaKeys.META_KEY_IMAGE_CAPTIONS),
                    StringUtils.defaultIfBlank(
                            MultimodalMetaKeys.metaString(block, MultimodalMetaKeys.META_KEY_IMG_PATH,
                                    MultimodalMetaKeys.META_KEY_IMAGE_PATH), block.text()));
            case ContentBlockVO.TYPE_TABLE -> StringUtils.defaultIfBlank(
                    MultimodalMetaKeys.metaString(block, MultimodalMetaKeys.META_KEY_TABLE_BODY), block.text());
            default -> StringUtils.defaultIfBlank(
                    MultimodalMetaKeys.metaString(block, MultimodalMetaKeys.META_KEY_TEXT,
                            MultimodalMetaKeys.META_KEY_LATEX), block.text());
        };
        return new MediaDescriptionVO(applyTypeSuffix(type, deriveEntityName(type, block)),
                typeLabel(type), StringUtils.EMPTY, StringUtils.defaultString(primary), false);
    }

    /**
     * 泛型块本地描述（不调 LLM）：详描直接取块原文。
     *
     * @param block 内容块
     * @return 描述（llmGenerated=false）
     */
    private MediaDescriptionVO genericFallbackDescription(ContentBlockVO block) {
        return new MediaDescriptionVO(
                applyTypeSuffix(ContentBlockVO.TYPE_GENERIC, deriveEntityName(ContentBlockVO.TYPE_GENERIC, block)),
                typeLabel(ContentBlockVO.TYPE_GENERIC), StringUtils.EMPTY,
                StringUtils.defaultString(block.text()), false);
    }

    /**
     * 确定性派生主实体名（不含类型后缀，后缀统一由 {@link #applyTypeSuffix} 拼接）：
     * meta 预置 → 题注截断 → 图片文件名 → 类型+页码。
     *
     * @param type  块类型
     * @param block 内容块
     * @return 主实体名裸名（非空白）
     */
    private String deriveEntityName(String type, ContentBlockVO block) {
        String preset = MultimodalMetaKeys.metaString(block, MultimodalMetaKeys.META_KEY_ENTITY_NAME);
        if (StringUtils.isNotBlank(preset)) {
            return preset;
        }
        String caption = MultimodalMetaKeys.metaString(block, MultimodalMetaKeys.META_KEY_IMAGE_CAPTION,
                MultimodalMetaKeys.META_KEY_TABLE_CAPTION);
        if (StringUtils.isNotBlank(caption)) {
            return StringUtils.abbreviate(caption.trim(), ENTITY_NAME_MAX_LENGTH);
        }
        String imagePath = MultimodalMetaKeys.metaString(block, MultimodalMetaKeys.META_KEY_IMG_PATH,
                MultimodalMetaKeys.META_KEY_IMAGE_PATH);
        if (StringUtils.isNotBlank(imagePath)) {
            return fileNameOf(imagePath);
        }
        String page = StringUtils.defaultIfBlank(
                MultimodalMetaKeys.metaString(block, MultimodalMetaKeys.META_KEY_PAGE,
                        MultimodalMetaKeys.META_KEY_PAGE_IDX), DEFAULT_PAGE);
        return typeLabel(type) + ENTITY_NAME_PAGE_CONNECTOR + page;
    }

    /**
     * 主实体名定形：裸名先经 {@link EntityNameNormalizer} 归一（与抽取解析入口同一管道，
     * 定形即归一——归属边注入与节点构造消费同一值，无需任何再次归一），再拼接内容类型后缀
     * （后缀取块类型小写，与不采信模型自报实体类型的类型口径同源），使多模态块主实体与
     * 正文抽取到的同名实体在图谱身份上天然区隔。
     * <p><b>长度口径</b>：名称截断在后缀拼接之前完成并为后缀留位——裸名超长时按
     * {@code ENTITY_NAME_MAX_LENGTH - 后缀长度} 截断，最终名长度不超过
     * {@code ENTITY_NAME_MAX_LENGTH}，MUST NOT 因截断丢失类型后缀。</p>
     * <p><b>兜底守卫</b>：归一后为空（如派生名恰为短纯数字碎片）时回落 trim 后的原裸名，
     * 保证「主实体名永不为空」的产物不变量不被归一破坏。</p>
     *
     * @param type     块类型（IMAGE / TABLE / EQUATION / GENERIC）
     * @param bareName 主实体裸名（模型识别名或确定性派生名，非空白）
     * @return 定形后的主实体名（归一裸名 + 类型后缀）
     */
    private String applyTypeSuffix(String type, String bareName) {
        String suffix = String.format(ENTITY_TYPE_SUFFIX_TEMPLATE, typeLabel(type));
        String name = EntityNameNormalizer.normalize(bareName);
        if (StringUtils.isBlank(name)) {
            name = StringUtils.trimToEmpty(bareName);
        }
        int reserved = ENTITY_NAME_MAX_LENGTH - suffix.length();
        if (reserved > 0 && name.length() > reserved) {
            name = StringUtils.abbreviate(name, reserved);
        }
        return name + suffix;
    }

    /**
     * 取路径的文件名部分（兼容 / 与 \ 分隔）。
     *
     * @param path 原始路径
     * @return 文件名
     */
    private String fileNameOf(String path) {
        int idx = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return idx >= 0 && idx < path.length() - 1 ? path.substring(idx + 1) : path;
    }

    /**
     * 主实体类型标签：块类型固定小写。
     *
     * @param type 块类型（大写）
     * @return 小写类型标签
     */
    private String typeLabel(String type) {
        return type.toLowerCase(Locale.ROOT);
    }
}
