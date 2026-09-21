package com.linkroa.deepdataagent.rag.infrastructure.persistence.projection;

import lombok.Data;

/**
 * 实体条目「账本重叠命中」投影（重建分类阶段①只读取数承载，组 4）。
 * <p>取数骨架：<b>账本权威为向量表</b>（{@code chunkIdsRaw} = {@code v.chunk_ids} 原文，
 * 重叠谓词在 SQL 侧以 {@code jsonb_array_elements} 展开判定）；<b>展示与语义原值为图表</b>
 * （{@code propertiesRaw} = 图行 {@code properties} JSON 文本，LEFT JOIN，图行缺失时为 null，
 * {@code graphMissing} 标志为真）。{@code content}/{@code contentVector} 取向量行当前值，
 * 供重建路径判断「期望内容未变则复用旧向量」。</p>
 * <p>JSON 文本与向量字面量一律由仓储层经 {@code RagGraphPersistenceConvert} 消化。</p>
 *
 * @author DeepDataAgent
 */
@Data
public class EntityLedgerOverlapProjection {

    /** 实体名称（条目身份） */
    private String entityName;

    /** 向量行当前向量化内容（可为 null） */
    private String content;

    /** 向量行当前向量字面量（形如 {@code "[0.1,0.2]"}，可为 null） */
    private String contentVector;

    /** 向量账本 JSON 数组文本（{@code v.chunk_ids}，账本权威） */
    private String chunkIdsRaw;

    /** 图行属性 JSON 文本（{@code g.properties}，图行缺失时为 null） */
    private String propertiesRaw;

    /** 图行缺失标志（LEFT JOIN 未命中，摄入收敛中间态） */
    private Boolean graphMissing;
}
