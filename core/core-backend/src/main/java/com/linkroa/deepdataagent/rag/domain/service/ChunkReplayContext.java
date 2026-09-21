package com.linkroa.deepdataagent.rag.domain.service;

import com.linkroa.deepdataagent.knowledgebase.domain.model.EntityType;
import org.apache.commons.lang3.ObjectUtils;

import java.util.List;
import java.util.Objects;

/**
 * 分块抽取缓存重放执行上下文（{@link EntityExtractionService#replayExtractedChunks} 的库级输入）。
 * <p>只承载<b>响应解析方向</b>真实消费的库级配置：缓存隔离键（kbId）、实体类型清单过滤所用
 * 类型允许集（entityTypes）、双模式解析分发开关（jsonMode）。与
 * {@link EntityExtractionContext} 刻意分离——后者是摄入期一次性上下文
 * （documentId、模型 profileId、语言、提示词目录、补漏轮数、并发度、取消检查器、缓存命中计数器），
 * 删除期不可得；且语言与提示词目录只参与请求构建、不参与解析，重放（纯读缓存 + 解析）无需该状态。
 * 这是 design D5「重放口径与抽取同形」在入参侧的落点：解析消费的配置子集与抽取一致，
 * 因此重放记录与当初抽取产出同形。</p>
 *
 * @param kbId        所属知识库ID（缓存库级隔离维度，必填；删除期由被删文档的 kbId 直接可得）
 * @param entityTypes 知识库可配置实体类型清单（null 视为空清单，仅按内置类型放行；
 *                    删除期从知识库当前配置读取，与抽取期同一数据源）
 * @param jsonMode    是否 JSON 输出模式（决定重放走 JSON 解析还是文本分隔符解析；
 *                    系统配置项，与抽取期取同一配置值——该值变更属全库重灌场景，与
 *                    design「截断/归一不一致导致反复重建」风险的消解口径一致）
 * @author DeepDataAgent
 */
public record ChunkReplayContext(
        Long kbId,
        List<EntityType> entityTypes,
        boolean jsonMode) {

    /**
     * 紧凑构造器：不变量校验与兜底（口径对齐 {@link EntityExtractionContext}）。
     */
    public ChunkReplayContext {
        if (ObjectUtils.isEmpty(kbId)) {
            throw new IllegalArgumentException("分块重放上下文 kbId 不能为空");
        }
        entityTypes = ObjectUtils.isEmpty(entityTypes)
                ? List.of()
                : entityTypes.stream().filter(Objects::nonNull).toList();
    }
}
