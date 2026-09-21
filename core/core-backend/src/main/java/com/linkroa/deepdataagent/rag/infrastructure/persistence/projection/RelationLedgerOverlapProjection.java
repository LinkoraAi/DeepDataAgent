package com.linkroa.deepdataagent.rag.infrastructure.persistence.projection;

import lombok.Data;

/**
 * 关系条目「账本重叠命中」投影（重建分类阶段①只读取数承载，组 4）。
 * <p>取数骨架与 {@link EntityLedgerOverlapProjection} 同构：账本权威为向量表
 * {@code chunk_ids}（重叠谓词 SQL 侧展开判定），语义原值 LEFT JOIN 图边行
 * {@code properties}（双向 OR 匹配兼容历史反向行，命中多行时 SQL 侧按（源、目标）升序，
 * 仓储层归一后同一条目只出一个快照）。{@code sourceName}/{@code targetName} 为向量行
 * 落库方向（字典序归一对），仓储层仍做一次无向归一兜底。</p>
 *
 * @author DeepDataAgent
 */
@Data
public class RelationLedgerOverlapProjection {

    /** 源实体名称（向量行落库方向） */
    private String sourceName;

    /** 目标实体名称（向量行落库方向） */
    private String targetName;

    /** 向量行当前向量化内容（可为 null） */
    private String content;

    /** 向量行当前向量字面量（形如 {@code "[0.1,0.2]"}，可为 null） */
    private String contentVector;

    /** 向量账本 JSON 数组文本（{@code v.chunk_ids}，账本权威） */
    private String chunkIdsRaw;

    /** 图边行属性 JSON 文本（首个方向行的 {@code g.properties}，图行缺失时为 null） */
    private String propertiesRaw;

    /** 图行缺失标志（双向 OR 均 LEFT JOIN 未命中） */
    private Boolean graphMissing;
}
