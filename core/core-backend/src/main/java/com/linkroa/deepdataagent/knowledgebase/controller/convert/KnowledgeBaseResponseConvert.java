package com.linkroa.deepdataagent.knowledgebase.controller.convert;

import com.linkroa.deepdataagent.knowledgebase.application.result.DedupHit;
import com.linkroa.deepdataagent.knowledgebase.application.result.UploadDocumentResult;
import com.linkroa.deepdataagent.knowledgebase.controller.response.ChunkResponse;
import com.linkroa.deepdataagent.knowledgebase.controller.response.DocumentResponse;
import com.linkroa.deepdataagent.knowledgebase.controller.response.DuplicateDocumentResponse;
import com.linkroa.deepdataagent.knowledgebase.controller.response.KnowledgeBaseResponse;
import com.linkroa.deepdataagent.knowledgebase.controller.response.KnowledgeBaseStatsResponse;
import com.linkroa.deepdataagent.knowledgebase.controller.response.UploadDocumentResponse;
import com.linkroa.deepdataagent.knowledgebase.domain.model.Chunk;
import com.linkroa.deepdataagent.knowledgebase.domain.model.Document;
import com.linkroa.deepdataagent.knowledgebase.domain.model.KnowledgeBase;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;
import org.mapstruct.factory.Mappers;

import java.util.List;
import java.util.Map;

/**
 * 知识库上下文领域模型 → 响应 DTO 转换器。
 * <p>领域模型中的枚举字段（生命周期状态、文件格式、文档状态等）在响应中以枚举名字符串暴露，
 * 由于记录（record）目标类型需按位置传参，此处统一使用 default 方法显式完成枚举 → 字符串转换，
 * 避免依赖隐式映射带来的歧义。</p>
 */
@Mapper(unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface KnowledgeBaseResponseConvert {

    KnowledgeBaseResponseConvert INSTANCE = Mappers.getMapper(KnowledgeBaseResponseConvert.class);

    /** 统计结果中知识库总数的键名 */
    String STATS_KEY_TOTAL_KNOWLEDGE_BASES = "totalKnowledgeBases";

    /** 统计结果中文档总数的键名 */
    String STATS_KEY_TOTAL_DOCUMENTS = "totalDocuments";

    /** 统计结果中切片总数的键名 */
    String STATS_KEY_TOTAL_CHUNKS = "totalChunks";

    /**
     * 知识库聚合根 → 响应 DTO。
     * <p>生命周期三态（ACTIVE / DELETING / DELETE_FAILED）与删除失败留痕 {@code error_message}
     * 原样回显（删除中 / 删除失败库对用户可见可操作）。</p>
     *
     * @param knowledgeBase 知识库聚合根，可为 {@code null}
     * @return 知识库响应，入参为 {@code null} 时返回 {@code null}
     */
    default KnowledgeBaseResponse toResponse(KnowledgeBase knowledgeBase) {
        if (knowledgeBase == null) {
            return null;
        }
        return new KnowledgeBaseResponse(
                knowledgeBase.id(),
                knowledgeBase.name(),
                knowledgeBase.description(),
                knowledgeBase.language(),
                enumToName(knowledgeBase.lifecycleStatus()),
                knowledgeBase.errorMessage(),
                knowledgeBase.ragEngineConfig(),
                knowledgeBase.dedupPolicy(),
                knowledgeBase.retrievalStrategy(),
                knowledgeBase.embeddingConfig(),
                knowledgeBase.multiModelConfig(),
                knowledgeBase.entityTypeConfig(),
                knowledgeBase.createdAt(),
                knowledgeBase.updatedAt()
        );
    }

    /**
     * 文档聚合根 → 响应 DTO。
     *
     * @param document 文档聚合根，可为 {@code null}
     * @return 文档响应，入参为 {@code null} 时返回 {@code null}
     */
    default DocumentResponse toResponse(Document document) {
        if (document == null) {
            return null;
        }
        return new DocumentResponse(
                document.id(),
                document.kbId(),
                document.fileName(),
                enumToName(document.fileType()),
                enumToName(document.status()),
                document.errorMessage(),
                document.fileSize(),
                document.chunkCount(),
                document.sourceFileProfile(),
                enumToName(document.importType()),
                document.s3File(),
                document.fileContentHash(),
                document.chunkStrategy(),
                document.createdAt(),
                document.updatedAt()
        );
    }

    /**
     * 切片聚合根 → 响应 DTO。
     *
     * @param chunk 切片聚合根，可为 {@code null}
     * @return 切片响应，入参为 {@code null} 时返回 {@code null}
     */
    default ChunkResponse toResponse(Chunk chunk) {
        if (chunk == null) {
            return null;
        }
        return new ChunkResponse(
                chunk.id(),
                chunk.kbId(),
                chunk.documentId(),
                chunk.sequence(),
                chunk.tokens(),
                chunk.chunkContent(),
                chunk.originalItem(),
                enumToName(chunk.chunkContentType()),
                chunk.sourceFileName(),
                enumToName(chunk.sourceType()),
                chunk.createdAt(),
                chunk.updatedAt()
        );
    }

    /**
     * 统计结果 → 总览响应 DTO。
     *
     * @param stats 统计结果，键为 {@code totalKnowledgeBases} / {@code totalDocuments} /
     *              {@code totalChunks}，可为 {@code null}
     * @return 统计响应，缺失的键按 0 处理
     */
    default KnowledgeBaseStatsResponse toStatsResponse(Map<String, Long> stats) {
        return new KnowledgeBaseStatsResponse(
                readCount(stats, STATS_KEY_TOTAL_KNOWLEDGE_BASES),
                readCount(stats, STATS_KEY_TOTAL_DOCUMENTS),
                readCount(stats, STATS_KEY_TOTAL_CHUNKS)
        );
    }

    /**
     * 知识库列表 → 响应 DTO 列表。
     *
     * @param knowledgeBases 知识库列表
     * @return 响应 DTO 列表，入参为 {@code null} 时返回空列表
     */
    default List<KnowledgeBaseResponse> toKnowledgeBaseResponseList(List<KnowledgeBase> knowledgeBases) {
        if (knowledgeBases == null) {
            return List.of();
        }
        return knowledgeBases.stream().map(this::toResponse).toList();
    }

    /**
     * 文档列表 → 响应 DTO 列表。
     *
     * @param documents 文档列表
     * @return 响应 DTO 列表，入参为 {@code null} 时返回空列表
     */
    default List<DocumentResponse> toDocumentResponseList(List<Document> documents) {
        if (documents == null) {
            return List.of();
        }
        return documents.stream().map(this::toResponse).toList();
    }

    /**
     * 切片列表 → 响应 DTO 列表。
     *
     * @param chunks 切片列表
     * @return 响应 DTO 列表，入参为 {@code null} 时返回空列表
     */
    default List<ChunkResponse> toChunkResponseList(List<Chunk> chunks) {
        if (chunks == null) {
            return List.of();
        }
        return chunks.stream().map(this::toResponse).toList();
    }

    /**
     * 上传应用层结果 → 上传响应 DTO。
     * <p>覆盖换版路径携带的旧文档快照列表原样映射为 {@code overwritten} 字段，
     * 供前端展示中间态。</p>
     *
     * @param result 上传结果（登记 / 跳过 / 覆盖换版），不可为 {@code null}
     * @return 携带 skipped 标记、文档详情与覆盖换版旧文档列表的上传响应
     */
    default UploadDocumentResponse toUploadResponse(UploadDocumentResult result) {
        return new UploadDocumentResponse(result.skipped(), toResponse(result.document()),
                toDocumentResponseList(result.overwritten()));
    }

    /**
     * 判重命中列表 → 重复文档摘要响应列表。
     *
     * @param hits 判重命中列表，可为 {@code null}
     * @return 摘要列表，入参为 {@code null} 时返回空列表
     */
    default List<DuplicateDocumentResponse> toDuplicateResponseList(List<DedupHit> hits) {
        if (hits == null) {
            return List.of();
        }
        return hits.stream().map(this::toDuplicateResponse).toList();
    }

    /**
     * 单条判重命中 → 重复文档摘要响应 DTO。
     *
     * @param hit 判重命中，不可为 {@code null}
     * @return 重复文档摘要
     */
    default DuplicateDocumentResponse toDuplicateResponse(DedupHit hit) {
        return new DuplicateDocumentResponse(hit.document().id(), hit.document().fileName(), hit.matchAxes());
    }

    /**
     * 枚举 → 枚举名字符串。
     *
     * @param value 枚举值，可为 {@code null}
     * @return 枚举名，入参为 {@code null} 时返回 {@code null}
     */
    private static String enumToName(Enum<?> value) {
        return value == null ? null : value.name();
    }

    /**
     * 从统计结果安全读取计数。
     *
     * @param stats 统计结果，可为 {@code null}
     * @param key   键名
     * @return 计数值，统计结果或键缺失时返回 0
     */
    private static long readCount(Map<String, Long> stats, String key) {
        if (stats == null) {
            return 0L;
        }
        Long value = stats.get(key);
        return value == null ? 0L : value;
    }
}
