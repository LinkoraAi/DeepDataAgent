package com.linkroa.deepdataagent.knowledgebase.api;

import org.apache.commons.lang3.ObjectUtils;

import java.util.List;

/**
 * 图谱贡献收敛执行结果契约（跨 BC 发布语言，OpenSpec rebuild-kg-on-document-delete / 组 6）。
 * <p>承载一次 {@link DocumentGraphConvergenceApi#apply} 事务内写回与收口的聚合计数，字段全部为
 * 基础类型与字符串列表（不引用 rag BC 类型）。计数口径以写回事务内「加锁重读后的锁定账本」为
 * 最终裁决（与重建服务的写回纪律同源）：</p>
 * <ul>
 *   <li>{@link #prunedEntries}：收敛删除条目数 = 重建阶段「存活集合为空转删除」+ 兜底账本剔空
 *       物理删（两步处理对象不相交——重建已改写/删除的行对兜底语句零命中，不重复计数）；</li>
 *   <li>{@link #rebuiltEntries}：完成绝对值重算写回的重建条目数；</li>
 *   <li>{@link #degradedEntries}：降级条目数（缓存缺失/不可解析 → 保留原语义字段、仅修账本，
 *       MUST NOT 视为删除失败，design D6/D7）；</li>
 *   <li>{@link #missingEntries}：写回加锁重读时条目已不存在的数量（并发链已回收，幂等空转）；</li>
 *   <li>{@link #reclaimedAttributionRows}：本批归属行回收数（{@code chunk_extract_cache}）；</li>
 *   <li>{@link #reclaimedCacheRows}：引用归零后被物理回收的抽取缓存行数（{@code llm_cache}，
 *       仅统计「追溯到且已归零」集，design D4）。</li>
 * </ul>
 * <p>{@link #pendingVectorSyncEntityNames} / {@link #pendingVectorSyncRelationPairs}：写回时因
 * 并发偏差无法在事务内成对写入（内容,向量）的条目身份清单，MUST 由调用方在事务提交后经
 * {@link DocumentGraphConvergenceApi#reconcilePendingVectorContent} 收口（事务内零远程的必然
 * 产物，design D9）。</p>
 *
 * @param prunedEntries                 收敛删除条目数（实体 + 关系合计）
 * @param rebuiltEntries                重建条目数（绝对值重算写回）
 * @param degradedEntries               降级条目数（计入降级报告，不阻断删除链）
 * @param missingEntries                写回时条目已缺失数（保持不存在、零写入）
 * @param reclaimedAttributionRows      本批归属行回收数
 * @param reclaimedCacheRows            引用归零缓存行回收数
 * @param pendingVectorSyncEntityNames  待提交后向量收口的实体名列表
 * @param pendingVectorSyncRelationPairs 待提交后向量收口的归一端点对列表（每项为长度 2 的有序列表）
 * @author DeepDataAgent
 */
public record DocumentGraphConvergenceResult(
        int prunedEntries,
        int rebuiltEntries,
        int degradedEntries,
        int missingEntries,
        int reclaimedAttributionRows,
        int reclaimedCacheRows,
        List<String> pendingVectorSyncEntityNames,
        List<List<String>> pendingVectorSyncRelationPairs) {

    /**
     * 紧凑构造器：清单空值归一为不可变空列表（下游遍历零防御）。
     */
    public DocumentGraphConvergenceResult {
        pendingVectorSyncEntityNames = ObjectUtils.isEmpty(pendingVectorSyncEntityNames)
                ? List.of() : List.copyOf(pendingVectorSyncEntityNames);
        pendingVectorSyncRelationPairs = ObjectUtils.isEmpty(pendingVectorSyncRelationPairs)
                ? List.of() : List.copyOf(pendingVectorSyncRelationPairs);
    }

    /**
     * 全零结果单例：record 不可变、两份清单经紧凑构造器归一为不可变空列表，可安全共享
     * （紧凑构造器内的 {@code List.copyOf} 只在本常量初始化时执行一次）。
     */
    private static final DocumentGraphConvergenceResult EMPTY =
            new DocumentGraphConvergenceResult(0, 0, 0, 0, 0, 0, List.of(), List.of());

    /**
     * 全零结果（空句柄 / 短路场景的返回形态）。
     *
     * @return 共享的不可变空结果实例（多次调用返回同一实例）
     */
    public static DocumentGraphConvergenceResult empty() {
        return EMPTY;
    }
}
