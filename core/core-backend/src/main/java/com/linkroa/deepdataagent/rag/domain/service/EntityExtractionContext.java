package com.linkroa.deepdataagent.rag.domain.service;

import com.linkroa.deepdataagent.knowledgebase.domain.model.EntityType;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/**
 * 实体抽取执行上下文值对象（单次文档级抽取的全部输入与运行参数）。
 * <p>承载抽取流程所需的最小上下文：库/文档身份（LLM 缓存隔离键与溯源键）、
 * 抽取模型引用、Prompt 语言、知识库可配置实体类型清单（抽取结果按此过滤）、
 * 输出模式开关（文本分隔符模式 / JSON 模式）、补漏（gleaning）轮数上限、
 * chunk 级并发度、多模态主实体名（可空），以及可协作取消检查（用户取消解析时中断抽取，
 * 见）。</p>
 *
 * @param kbId                      所属知识库ID（LLM 缓存库级隔离维度，必填）
 * @param documentId                来源文档ID（溯源归属键，必填）
 * @param extractionModelProfileId  抽取 LLM 模型 profileId（CHAT 类型，必填）
 * @param language                  知识库语言全名（兼容历史裸语言码；空白时按英文处理，
 * @param entityTypes               知识库可配置实体类型清单（null 视为空清单，仅按内置类型过滤）
 * @param jsonMode                  是否使用 JSON 输出模式（true 走 *_json_* 模板变体）
 * @param maxGleaning               补漏（gleaning）最大轮数 N（0 表示不补漏，负数归零）；
 *                                  实际续抽至多 N 轮，且最近响应含完成信号或本轮解析零新增时任一条件即提前停止
 * @param concurrency               chunk 级并发度（非正数回退 {@link #DEFAULT_CONCURRENCY}）
 * @param filePath                  来源文件路径（写入节点/边属性，可空）
 * @param cancellationCheck         取消检查器（null 视为永不取消）
 * @param mediaPrimaryEntityName    多模态主实体名（VLM 识别并在描述服务处一次定形的
 *                                  「裸名 + 内容类型后缀」，可空；sidecar 归属边注入唯一消费该值，
 *                                  空白时跳过注入，主实体节点由摄入管线唯一构造）
 * @param llmCacheHits              LLM 缓存命中计数器（可空；非空时抽取每次缓存回放
 *                                  递增一次，供摄入收尾结构化日志汇总）
 * @author DeepDataAgent
 */
public record EntityExtractionContext(
        Long kbId,
        Long documentId,
        String extractionModelProfileId,
        String language,
        List<EntityType> entityTypes,
        boolean jsonMode,
        int maxGleaning,
        int concurrency,
        String filePath,
        BooleanSupplier cancellationCheck,
        String mediaPrimaryEntityName,
        AtomicInteger llmCacheHits) {

    /** chunk 级并发度默认值（与图合并阶段默认并发口径一致） */
    public static final int DEFAULT_CONCURRENCY = 4;

    /**
     * 紧凑构造器：不变量校验与兜底。
     */
    public EntityExtractionContext {
        if (ObjectUtils.isEmpty(kbId)) {
            throw new IllegalArgumentException("实体抽取上下文 kbId 不能为空");
        }
        if (ObjectUtils.isEmpty(documentId)) {
            throw new IllegalArgumentException("实体抽取上下文 documentId 不能为空");
        }
        if (StringUtils.isBlank(extractionModelProfileId)) {
            throw new IllegalArgumentException("实体抽取上下文 extractionModelProfileId 不能为空");
        }
        language = StringUtils.isBlank(language) ? "en" : language;
        entityTypes = ObjectUtils.isEmpty(entityTypes)
                ? List.of()
                : entityTypes.stream().filter(Objects::nonNull).toList();
        maxGleaning = Math.max(maxGleaning, 0);
        concurrency = concurrency <= 0 ? DEFAULT_CONCURRENCY : concurrency;
    }

    /**
     * 便捷构造器：不挂缓存命中计数器（抽取不统计回放次数）。
     *
     * @param kbId                   所属知识库ID
     * @param documentId             来源文档ID
     * @param extractionModelProfileId 抽取模型 profileId
     * @param language               知识库语言全名（兼容历史裸语言码）
     * @param entityTypes            实体类型清单
     * @param jsonMode               是否 JSON 输出模式
     * @param maxGleaning            补漏最大轮数
     * @param concurrency            chunk 级并发度
     * @param filePath               来源文件路径
     * @param cancellationCheck      取消检查器
     * @param mediaPrimaryEntityName 多模态主实体名
     */
    public EntityExtractionContext(
            Long kbId, Long documentId, String extractionModelProfileId, String language,
            List<EntityType> entityTypes, boolean jsonMode, int maxGleaning, int concurrency,
            String filePath, BooleanSupplier cancellationCheck, String mediaPrimaryEntityName) {
        this(kbId, documentId, extractionModelProfileId, language, entityTypes, jsonMode,
                maxGleaning, concurrency, filePath, cancellationCheck, mediaPrimaryEntityName, null);
    }

    /**
     * 是否已被请求取消（检查器抛出的异常原样上抛，由调用方统一兜底）。
     *
     * @return 已取消返回 true
     */
    public boolean isCancelled() {
        return ObjectUtils.isNotEmpty(cancellationCheck) && cancellationCheck.getAsBoolean();
    }
}
