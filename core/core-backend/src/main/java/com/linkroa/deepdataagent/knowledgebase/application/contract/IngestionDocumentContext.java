package com.linkroa.deepdataagent.knowledgebase.application.contract;

import com.linkroa.deepdataagent.knowledgebase.domain.model.DocumentParseConfig;
import com.linkroa.deepdataagent.knowledgebase.domain.model.EmbeddingModelConfig;
import com.linkroa.deepdataagent.knowledgebase.domain.model.EntityType;
import com.linkroa.deepdataagent.knowledgebase.domain.model.MultiModelConfig;
import com.linkroa.deepdataagent.knowledgebase.domain.model.RagEngineConfig;
import com.linkroa.deepdataagent.knowledgebase.domain.model.S3File;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.DocumentStatus;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;

import java.util.List;

/**
 * 摄入文档上下文（跨 BC 发布语言）：RAG 摄入管线处理单个文档所需的全部知识库侧输入。
 * <p>由 {@link com.linkroa.deepdataagent.knowledgebase.api.IngestionSourceReader#readContext(Long)} 一次性组装：
 * 文档侧字段取自
 * document 表（含 s3_file / chunk_strategy JSON 的解析结果），库侧配置取自
 * knowledge_base 表的五段 JSON 配置（rag_engine_config / embedding_config /
 * multi_model_config / entity_type_config）。消费方拿到本上下文后即可独立完成
 * 解析 → 分块 → 向量化，无需再回查知识库侧任何表。</p>
 *
 * @param documentId                文档主键
 * @param kbId                      所属知识库ID
 * @param fileName                  文件名
 * @param status                    文档当前处理状态（读取时刻快照）
 * @param s3File                    源文件对象存储引用；可为 null（s3_file 列空白，
 *                                  表示无源文件可取，由消费方判定 FAILED 并回写原因）
 * @param documentChunkStrategyJson 文档级分块策略 JSON 原文；NULL / 空白表示继承库级策略
 * @param ragEngineConfig           RAG 引擎配置值对象（engineType 便利投影）；可为 null（库未配置）
 * @param parseConfig               文档解析引擎配置（rag_engine_config.parseEngine 段）；可为 null（未配置）
 * @param embeddingConfig           嵌入模型配置；可为 null（库未配置，由消费方回落默认模型）
 * @param multiModelConfig          多模态模型配置；可为 null（库未配置）
 * @param entityTypes               实体类型自定义清单；解析失败或未配置时为空清单（非 null）
 * @param ragEngineConfigJson       rag_engine_config 列 JSON 原文（无损保留）：分块策略
 *                                  chunkStrategy.modeConfig.params 与图合并段等嵌套配置块
 *                                  的权威来源，值对象字段仅为便利投影
 * @param language                  知识库语言（真相源 = {@code knowledge_base.language} 列的归一语言全名，
 *                                  11 全名枚举见 {@code KbLanguage}，兼容历史裸语言码原样透传；
 *                                  {@code null} = 未配置，消费方回落全局默认语言，；
 *                                  不再取
 *                                  {@code rag_engine_config} JSONB 的 language 键）
 */
public record IngestionDocumentContext(
        Long documentId,
        Long kbId,
        String fileName,
        DocumentStatus status,
        S3File s3File,
        String documentChunkStrategyJson,
        RagEngineConfig ragEngineConfig,
        DocumentParseConfig parseConfig,
        EmbeddingModelConfig embeddingConfig,
        MultiModelConfig multiModelConfig,
        List<EntityType> entityTypes,
        String ragEngineConfigJson,
        String language
) {

    /**
     * 紧凑构造器：关键字段不变量校验与实体类型清单兜底。
     *
     * @throws IllegalArgumentException 文档ID / 知识库ID / 文件名缺失
     */
    public IngestionDocumentContext {
        if (ObjectUtils.isEmpty(documentId) || ObjectUtils.isEmpty(kbId)) {
            throw new IllegalArgumentException("文档ID与知识库ID不能为空");
        }
        if (StringUtils.isBlank(fileName)) {
            throw new IllegalArgumentException("文件名不能为空");
        }
        if (ObjectUtils.isEmpty(entityTypes)) {
            entityTypes = List.of();
        } else {
            entityTypes = List.copyOf(entityTypes);
        }
    }
}
