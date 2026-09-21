package com.linkroa.deepdataagent.knowledgebase.domain.model;

import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.ChunkContentType;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.ChunkSource;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;

import java.time.OffsetDateTime;
import java.time.ZoneId;

/**
 * 切片聚合根。
 * <p>表示文档分块后的最小检索单元，与 chunk_vector / chunk_tsv 1:1 关联。
 * 来源标识（{@link ChunkSource}）由两条写入工厂各自打标、写入即定：
 * {@link #create} 恒为「人工新增」、{@link #createForRebuild} 恒为「解析产生」，
 * 编辑等后续操作 MUST NOT 改写该标识。</p>
 *
 * @param id               主键
 * @param kbId             所属知识库ID（冗余加速库级检索）
 * @param documentId       所属文档ID
 * @param sequence         块在文档内的序号
 * @param tokens           块的 token 数量
 * @param chunkContent     块内容（套模板后的最终文本）
 * @param originalItem     多模态原始信息 JSON
 * @param chunkContentType 内容形态
 * @param sourceFileName   来源文件名（冗余，便于引用展示）
 * @param s3File           S3 多模态（图片、视频、音频等文件）信息
 * @param sourceType       分块来源标识（null 兜底为「解析产生」——未打标即按可拒绝删除的
 *                         安全方向处理，拒绝删除优于误删）
 * @param createdAt        创建时间
 * @param updatedAt        更新时间
 */
public record Chunk(
        Long id,
        Long kbId,
        Long documentId,
        Integer sequence,
        Integer tokens,
        String chunkContent,
        String originalItem,
        ChunkContentType chunkContentType,
        String sourceFileName,
        String s3File,
        ChunkSource sourceType,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {

    /**
     * 紧凑构造器：不变量校验与字段兜底。
     */
    public Chunk {
        if (kbId == null) {
            throw new IllegalArgumentException("切片必须关联知识库");
        }
        if (documentId == null) {
            throw new IllegalArgumentException("切片必须关联文档");
        }
        if (StringUtils.isBlank(chunkContent)) {
            throw new IllegalArgumentException("切片内容不能为空");
        }
        if (chunkContentType == null) {
            chunkContentType = ChunkContentType.TEXT;
        }
        if (ObjectUtils.isEmpty(sourceType)) {
            sourceType = ChunkSource.PARSED;
        }
    }

    /**
     * 人工新增切片工厂：人工入口不携带多模态图片引用，{@code s3File} 固定为 null；
     * 来源标识恒打「人工新增」（该来源不产生图谱贡献，是人工删除入口的准入依据）。
     */
    public static Chunk create(Long kbId, Long documentId, Integer sequence,
                               Integer tokens, String chunkContent, String originalItem,
                               ChunkContentType chunkContentType, String sourceFileName) {
        OffsetDateTime now = OffsetDateTime.now(ZoneId.of("Asia/Shanghai"));
        return new Chunk(null, kbId, documentId, sequence, tokens, chunkContent,
                originalItem, chunkContentType, sourceFileName, null, ChunkSource.MANUAL, now, now);
    }

    /**
     * 整篇重建场景创建切片：携带该切片的图片引用落一等列（与 {@link #create} 的差异即
     * s3File 与来源标识）；来源标识恒打「解析产生」，由摄入管线整篇替换落库专用。
     *
     * @param s3File 多模态图片对象存储引用 JSON（{@code {objectKey}}，桶概念已退役），无图片引用传 null
     */
    public static Chunk createForRebuild(Long kbId, Long documentId, Integer sequence,
                                         Integer tokens, String chunkContent, String originalItem,
                                         ChunkContentType chunkContentType, String sourceFileName,
                                         String s3File) {
        OffsetDateTime now = OffsetDateTime.now(ZoneId.of("Asia/Shanghai"));
        return new Chunk(null, kbId, documentId, sequence, tokens, chunkContent,
                originalItem, chunkContentType, sourceFileName, s3File, ChunkSource.PARSED, now, now);
    }

    /**
     * 从数据库恢复（含 s3_file 一等列与来源标识列）。
     *
     * @param s3File     多模态图片对象存储引用 JSON，非多模态切片为 null
     * @param sourceType 来源标识（{@code chunk.source_type} 列值，null 由紧凑构造器兜底为「解析产生」）
     */
    public static Chunk restore(Long id, Long kbId, Long documentId, Integer sequence,
                                Integer tokens, String chunkContent, String originalItem,
                                ChunkContentType chunkContentType, String sourceFileName,
                                String s3File, ChunkSource sourceType, OffsetDateTime createdAt,
                                OffsetDateTime updatedAt) {
        return new Chunk(id, kbId, documentId, sequence, tokens, chunkContent,
                originalItem, chunkContentType, sourceFileName, s3File, sourceType, createdAt, updatedAt);
    }

    /**
     * 更新切片内容：图片引用（s3File）与来源标识原样保留（编辑 MUST NOT 改写来源）。
     */
    public Chunk withContent(String newContent, Integer newTokens) {
        return new Chunk(id, kbId, documentId, sequence, newTokens, newContent,
                originalItem, chunkContentType, sourceFileName,
                s3File, sourceType, createdAt, OffsetDateTime.now(ZoneId.of("Asia/Shanghai")));
    }
}