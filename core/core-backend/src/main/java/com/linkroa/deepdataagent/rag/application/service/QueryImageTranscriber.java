package com.linkroa.deepdataagent.rag.application.service;

import com.linkroa.deepdataagent.knowledgebase.api.KnowledgeBaseApi;
import com.linkroa.deepdataagent.rag.domain.enums.CacheType;
import com.linkroa.deepdataagent.rag.domain.port.LlmChatRequest;
import com.linkroa.deepdataagent.rag.domain.port.LlmChatResult;
import com.linkroa.deepdataagent.rag.domain.port.LlmClient;
import com.linkroa.deepdataagent.rag.domain.port.LlmImage;
import com.linkroa.deepdataagent.rag.domain.service.RetrievalConstants;
import com.linkroa.deepdataagent.rag.infrastructure.prompts.catalog.PromptCatalog;
import com.linkroa.deepdataagent.rag.infrastructure.prompts.catalog.PromptTemplates;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 检索附图转译器（通路 A：把提问附带的图片转写为描述文本并入查询，
 *
 * <p><b>编排位置</b>：Stage 0 查询理解（问题改写 / 双层关键词提取）之前。产出的增强查询
 * 只喂检索链路（改写、关键词、召回、精排、上下文），<b>原始 query 由编排层保留</b>用于
 * 最终作答与语言规则，本类不改变原始问题文本。</p>
 *
 * <p><b>逐图转译</b>：每张图单独发起一次多模态请求（system={@link PromptTemplates#QUERY_IMAGE_ANALYST_SYSTEM}、
 * user={@link PromptTemplates#QUERY_IMAGE_DESCRIPTION}），数量上限已由入参校验层锁定为
 * {@code MultimodalConstraints.MAX_IMAGES_PER_QUERY_REQUEST}。请求经 {@code @Primary} 的
 * {@code CachingLlmClient} 发出，缓存分区归 {@link CacheType#QUERY_IMAGE_TRANSCRIBE}，
 * 图片内容摘要参与缓存键，故相同图片二次提问直接回放、不重复调模型。</p>
 *
 * <p><b>回退矩阵</b>（回退一律返回原始 query，并输出含分类原因的 WARN 留痕，主流程不中断）：</p>
 * <ul>
 *   <li>无附件 / 原始问题空白 → 原样返回，<b>零 LLM 调用</b>；</li>
 *   <li>库未配置多模态模型 profileId（含读取失败）→ 回退分类「未配置」；</li>
 *   <li>单图转译异常或返回空 → 跳过该图，其余继续（不做部分丢弃以外的降级）；</li>
 *   <li>全部图片均转译失败 → 回退分类「调用失败」。</li>
 * </ul>
 *
 * <p><b>零持久化</b>：本类只读知识库配置与调用 LLM，MUST NOT 写对象存储 / 知识库切片 / 图谱
 * （附件生命周期归调用方，见 specs「附件不持久化不入知识库」）。</p>
 *
 * @author DeepDataAgent
 */
@Component
public class QueryImageTranscriber {

    /** 日志器 */
    private static final Logger log = LoggerFactory.getLogger(QueryImageTranscriber.class);

    /** 增强查询段落分隔符（原问题与附图描述、附图描述与尾部指导句之间） */
    private static final String PARAGRAPH_SEPARATOR = "\n\n";

    /** 增强查询附图描述段标签 */
    private static final String DESCRIPTION_LABEL = "附图描述：";

    /** 多张图描述之间的连接符 */
    private static final String DESCRIPTION_JOINER = "；";

    /** LLM 对话端口（生产装配为 @Primary 的 CachingLlmClient，转译缓存由其内嵌键管理） */
    private final LlmClient llmClient;

    /** 知识库跨 BC 只读契约（读库 VLM 配置与库语言） */
    private final KnowledgeBaseApi knowledgeBaseApi;

    /**
     * 构造附图转译器。
     *
     * @param llmClient        LLM 对话端口（@Primary 缓存装饰客户端）
     * @param knowledgeBaseApi 知识库跨 BC 只读契约
     */
    public QueryImageTranscriber(LlmClient llmClient,
                                 KnowledgeBaseApi knowledgeBaseApi) {
        this.llmClient = llmClient;
        this.knowledgeBaseApi = knowledgeBaseApi;
    }

    /**
     * 将检索附图转译为增强查询文本。
     *
     * @param kbId          所属知识库ID（缓存库级隔离与库配置读取维度，必填）
     * @param originalQuery 用户原始问题（空白或附件为空时原样返回）
     * @param images        已校验解码的附图列表（可空；数量上限由入参校验层保证）
     * @return 增强查询文本（原问题 + 附图描述 + 尾部指导句）；无附件、VLM 未配置或全部转译
     *         失败时返回原始问题（永不为 {@code null}，除非入参 {@code originalQuery} 为空）
     */
    public String transcribe(Long kbId, String originalQuery, List<LlmImage> images) {
        if (StringUtils.isBlank(originalQuery) || CollectionUtils.isEmpty(images)) {
            return originalQuery;
        }
        String vlmProfileId = resolveMediaProfileId(kbId);
        if (StringUtils.isBlank(vlmProfileId)) {
            log.warn("通路 A 附图转译回退（原因=未配置）：知识库未配置多模态模型 profileId，"
                    + "按原始查询继续检索: kbId={}, imageCount={}", kbId, images.size());
            return originalQuery;
        }
        String language = resolveLanguage(kbId);
        String systemPrompt = PromptCatalog.render(PromptTemplates.QUERY_IMAGE_ANALYST_SYSTEM, language, Map.of());
        String userPrompt = PromptCatalog.render(PromptTemplates.QUERY_IMAGE_DESCRIPTION, language, Map.of());
        List<String> descriptions = transcribeEach(kbId, vlmProfileId, systemPrompt, userPrompt, images);
        if (CollectionUtils.isEmpty(descriptions)) {
            log.warn("通路 A 附图转译回退（原因=调用失败）：{} 张附图全部转译失败或返回空，按原始查询继续检索: kbId={}",
                    images.size(), kbId);
            return originalQuery;
        }
        return enhance(originalQuery, descriptions, language);
    }

    /**
     * 逐图发起多模态转译：单图异常或空响应仅跳过该图（其余继续），不向上抛出。
     *
     * @param kbId         所属知识库ID
     * @param vlmProfileId 多模态模型 profileId（调用方已保证非空白）
     * @param systemPrompt 已渲染的图片分析系统提示词
     * @param userPrompt   已渲染的图片描述用户提示词
     * @param images       附图列表（调用方已保证非空）
     * @return 成功转译的图片描述列表（顺序与附图一致；全部失败时为空列表）
     */
    private List<String> transcribeEach(Long kbId, String vlmProfileId, String systemPrompt, String userPrompt,
                                        List<LlmImage> images) {
        List<String> descriptions = new ArrayList<>(images.size());
        for (int i = 0; i < images.size(); i++) {
            int ordinal = i + 1;
            try {
                LlmChatResult result = llmClient.chat(new LlmChatRequest(kbId, vlmProfileId, systemPrompt,
                        userPrompt, null, CacheType.QUERY_IMAGE_TRANSCRIBE, List.of(images.get(i))));
                String description = ObjectUtils.isEmpty(result)
                        ? null : StringUtils.trimToEmpty(result.text());
                if (StringUtils.isBlank(description)) {
                    log.warn("通路 A 第 {} 张附图转译返回空，跳过该图: kbId={}", ordinal, kbId);
                    continue;
                }
                descriptions.add(description);
            } catch (RuntimeException e) {
                log.warn("通路 A 第 {} 张附图转译失败，跳过该图: kbId={}, 原因={}", ordinal, kbId, e.getMessage(), e);
            }
        }
        return descriptions;
    }

    /**
     * 拼装增强查询：原问题 + 附图描述段 + {@link PromptTemplates#QUERY_ENHANCEMENT_SUFFIX} 尾部指导句。
     * <p>尾部模板正文首尾无换行，段落前导换行由本方法显式给出（见模板文件登记偏离说明）。</p>
     *
     * @param originalQuery 用户原始问题
     * @param descriptions  成功转译的图片描述列表（非空）
     * @param language      知识库语言全名（模板套选择口径）
     * @return 增强查询文本
     */
    private String enhance(String originalQuery, List<String> descriptions, String language) {
        String suffix = StringUtils.trim(
                PromptCatalog.render(PromptTemplates.QUERY_ENHANCEMENT_SUFFIX, language, Map.of()));
        return originalQuery + PARAGRAPH_SEPARATOR + DESCRIPTION_LABEL
                + String.join(DESCRIPTION_JOINER, descriptions)
                + PARAGRAPH_SEPARATOR + suffix;
    }

    /**
     * 读取库级多模态（视觉）模型 profileId；读取失败（库配置数据损坏 / 瞬时异常）按
     * 「未配置」同类回退处理——附图转译属增强路径，不得因其故障中断检索主流程。
     *
     * @param kbId 所属知识库ID
     * @return profileId；未配置或读取失败返回 {@code null}
     */
    private String resolveMediaProfileId(Long kbId) {
        try {
            return knowledgeBaseApi.findMediaModelProfileIdByKbId(kbId);
        } catch (RuntimeException e) {
            log.warn("知识库多模态模型配置读取失败，按未配置回退原始查询: kbId={}", kbId, e);
            return null;
        }
    }

    /**
     * 解析转译提示词语言（与检索侧语言同源口径）：优先知识库语言全名，
     * 库未配置或读取失败回落 {@link RetrievalConstants#DEFAULT_LANGUAGE}。
     *
     * @param kbId 所属知识库ID
     * @return 语言全名（非空白）
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
}
