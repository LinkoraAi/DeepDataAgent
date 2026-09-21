package com.linkroa.deepdataagent.rag.domain.service;

import org.apache.commons.lang3.ObjectUtils;

import java.util.List;

/**
 * 图谱贡献重建结果报告值对象（阶段 B 写回后的聚合计数，spec graph-contribution-rebuild / R3
 * 降级报告与 design D10 观测口径的承载，tasks 4.8）。
 * <p><b>计数口径说明（以阶段 B 锁定账本为最终裁决）</b>：</p>
 * <ul>
 *   <li>{@link #rebuiltEntries}：完成「绝对值重算写回」的条目数（非降级、存活集合与快照一致、
 *       按阶段 A 候选或确定性重算值写入）；</li>
 *   <li>{@link #degradedEntries}：降级写回条目数（缓存缺失/不可解析/分类为直接删除但写回时被
 *       并发并入新来源——保留原语义字段、仅修账本与可得来源路径），MUST NOT 视为删除失败；</li>
 *   <li>{@link #prunedEntries}：实际物理删除的条目数（含分类阶段即无存活来源的直接删除，
 *       与写回时发现存活集合转空的转删除；向量行与图行 1:1 同步删除，计一条目）；</li>
 *   <li>{@link #missingEntries}：写回加锁重读时条目已不存在的数量（并发删除链已回收——
 *       「条目已不存在且存活为空则保持不存在、不复活」纪律的观测面，正常态恒为 0 或幂等空转）。</li>
 * </ul>
 * <p>{@link #pendingVectorSyncEntityNames} / {@link #pendingVectorSyncRelationPairs}：因写回时
 * 存活集合与快照不一致而无法在事务内给出成对（内容,向量）的条目身份清单——其向量行内容已在
 * 事务内按最终描述派生写入，但向量分量为空/过期，MUST 由调用方在事务提交后调用
 * {@link GraphContributionRebuildService#reconcilePendingVectorContent} 交
 * {@link VectorContentReconciler} 收口（design D9「事务内零远程」的必然产物）。</p>
 *
 * @param rebuiltEntries                  绝对值重算写回条目数
 * @param degradedEntries                 降级写回条目数（计入降级报告，不阻断删除链）
 * @param prunedEntries                   实际物理删除条目数（直接删除 + 写回时存活转空的转删除）
 * @param missingEntries                  写回时条目已不存在的数量（保持不存在、零写入）
 * @param pendingVectorSyncEntityNames    待提交后向量收口的实体名列表
 * @param pendingVectorSyncRelationPairs  待提交后向量收口的归一端点对列表
 * @author DeepDataAgent
 */
public record GraphRebuildReport(
        int rebuiltEntries,
        int degradedEntries,
        int prunedEntries,
        int missingEntries,
        List<String> pendingVectorSyncEntityNames,
        List<List<String>> pendingVectorSyncRelationPairs) {

    /**
     * 紧凑构造器：清单空值归一为不可变空列表。
     */
    public GraphRebuildReport {
        pendingVectorSyncEntityNames = ObjectUtils.isEmpty(pendingVectorSyncEntityNames)
                ? List.of() : List.copyOf(pendingVectorSyncEntityNames);
        pendingVectorSyncRelationPairs = ObjectUtils.isEmpty(pendingVectorSyncRelationPairs)
                ? List.of() : List.copyOf(pendingVectorSyncRelationPairs);
    }

    /**
     * 全零报告（空计划 apply 的结果）。
     *
     * @return 空报告实例
     */
    public static GraphRebuildReport empty() {
        return new GraphRebuildReport(0, 0, 0, 0, List.of(), List.of());
    }

    /**
     * 本报告的删除链是否发生「运行失败」——本值对象不承载失败语义：运行失败（写回 SQL 异常等）
     * 以异常上抛表达（design D7），能产出报告即写回成功；降级仅体现在 {@link #degradedEntries}。
     *
     * @return 存在降级条目返回 true（供调用方日志与观测快速判定）
     */
    public boolean hasDegraded() {
        return degradedEntries > 0;
    }
}
