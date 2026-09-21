package com.linkroa.deepdataagent.rag.domain.port;

import com.linkroa.deepdataagent.rag.domain.enums.CacheType;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;

import java.util.List;

/**
 * LLM 对话调用请求值对象（RAG 摄入统一入参）。
 * <p>{@code kbId} 用于 {@code llm_cache} 的库级隔离维度；{@code modelProfileId} 引用
 * agent BC 模型注册表（CHAT 类型），明文凭证只在基础设施层解析、不进入本值对象；
 * {@code temperature} 为空表示使用提供方默认值；{@code cacheType} 为缓存分区归位维度
 * （显式参数声明，弃用 ThreadLocal 隐式传递），
 * 为空回落通用分类 {@link CacheType#ANSWER}。</p>
 * <p>{@code images} 为多模态图片载荷：仅进入 user message
 * （与用户提示词同处 parts 数组，不污染 system），且每张图片内容参与缓存键摘要
 * （同文本不同图必不同键，见 {@code CachingLlmClient}）；为空表示纯文本请求，
 * 请求报文与缓存键计算与无该组件时逐字节一致。数量与单图字节上限见
 * {@link MultimodalConstraints}，由调用侧装载前校验。</p>
 * <p>{@code attributionChunkId} 为可空的归因字段（分块标识，{@code Long chunkId} 语义）：
 * <b>仅用于缓存归属登记，不参与缓存键计算、不影响命中判定</b>。携带该字段的请求由
 * {@code CachingLlmClient} 在命中回放与未命中回写两条路径统一登记「分块 → 缓存行」归属，
 * 供文档删除时按引用计数精确回收缓存行；为空表示该调用不属于摄入期抽取
 * （查询答案、实体描述摘要等），不登记任何归属。字段取值对缓存键、回放结果与缓存行内容
 * 零影响——同一提示词的请求无论归因字段是否为空、取值是否相同，键与命中判定完全一致。</p>
 *
 * @param kbId               所属知识库ID（缓存键隔离维度，必填）
 * @param modelProfileId     模型配置 profileId（必填）
 * @param systemPrompt       系统提示词（可空）
 * @param userPrompt         用户提示词（必填）
 * @param temperature        采样温度（可空，参与缓存键归一化）
 * @param cacheType          缓存分区类型（可空，空回落 {@link CacheType#ANSWER}）
 * @param images             多模态图片载荷（可空，空回落不可变空列表；仅进 user message 且参与缓存键摘要）
 * @param attributionChunkId 缓存归属登记用的分块标识（可空；仅登记归属，不参与缓存键计算与命中判定）
 * @author DeepDataAgent
 */
public record LlmChatRequest(
        Long kbId,
        String modelProfileId,
        String systemPrompt,
        String userPrompt,
        Double temperature,
        CacheType cacheType,
        List<LlmImage> images,
        Long attributionChunkId
) {

    /**
     * 紧凑构造器：不变量校验、缓存分区缺省归一与图片列表不可变归一。
     * <p>{@code attributionChunkId} 为纯归因字段，不做任何归一与校验（可空即不登记归属）。</p>
     */
    public LlmChatRequest {
        if (ObjectUtils.isEmpty(kbId)) {
            throw new IllegalArgumentException("LLM 请求 kbId 不能为空");
        }
        if (StringUtils.isBlank(modelProfileId)) {
            throw new IllegalArgumentException("LLM 请求 modelProfileId 不能为空");
        }
        if (StringUtils.isBlank(userPrompt)) {
            throw new IllegalArgumentException("LLM 请求 userPrompt 不能为空");
        }
        if (ObjectUtils.isEmpty(cacheType)) {
            cacheType = CacheType.ANSWER;
        }
        if (CollectionUtils.isEmpty(images)) {
            images = List.of();
        } else {
            images = List.copyOf(images);
        }
    }

    /**
     * 便捷构造器：携带图片但不登记归属的既有形态（引入归因字段前的七参规范构造器签名，
     * 既有调用点零改动；归因字段归一为空表示不登记归属）。
     *
     * @param kbId           所属知识库ID
     * @param modelProfileId 模型配置 profileId
     * @param systemPrompt   系统提示词（可空）
     * @param userPrompt     用户提示词
     * @param temperature    采样温度（可空）
     * @param cacheType      缓存分区类型（可空，空回落 {@link CacheType#ANSWER}）
     * @param images         多模态图片载荷（可空，空回落不可变空列表）
     */
    public LlmChatRequest(Long kbId, String modelProfileId, String systemPrompt,
                          String userPrompt, Double temperature, CacheType cacheType, List<LlmImage> images) {
        this(kbId, modelProfileId, systemPrompt, userPrompt, temperature, cacheType, images, null);
    }

    /**
     * 便捷构造器：纯文本请求（图片列表为空表，行为与引入多模态组件前完全一致）。
     *
     * @param kbId           所属知识库ID
     * @param modelProfileId 模型配置 profileId
     * @param systemPrompt   系统提示词（可空）
     * @param userPrompt     用户提示词
     * @param temperature    采样温度（可空）
     * @param cacheType      缓存分区类型（可空，空回落 {@link CacheType#ANSWER}）
     */
    public LlmChatRequest(Long kbId, String modelProfileId, String systemPrompt,
                          String userPrompt, Double temperature, CacheType cacheType) {
        this(kbId, modelProfileId, systemPrompt, userPrompt, temperature, cacheType, List.of(), null);
    }

    /**
     * 便捷构造器：不显式声明缓存分区（回落通用分类 {@link CacheType#ANSWER}）。
     *
     * @param kbId           所属知识库ID
     * @param modelProfileId 模型配置 profileId
     * @param systemPrompt   系统提示词（可空）
     * @param userPrompt     用户提示词
     * @param temperature    采样温度（可空）
     */
    public LlmChatRequest(Long kbId, String modelProfileId, String systemPrompt,
                          String userPrompt, Double temperature) {
        this(kbId, modelProfileId, systemPrompt, userPrompt, temperature, CacheType.ANSWER, List.of(), null);
    }
}
