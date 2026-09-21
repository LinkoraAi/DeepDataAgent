package com.linkroa.deepdataagent.rag.domain.service;

/**
 * 图合并结果报告值对象（单次 {@code mergeNodesAndEdges} 的聚合计数，供任务日志与观测）。
 * <p>不含「缺失端点补建数」：占位实体机制已退役，端点缺失属正常态、不建节点亦不产生计数。</p>
 * <p>{@code reconcilesFixed} / {@code reconcilesGuardMissed} 为 graph-write-consistency 变更引入的
 * <b>向量内容收口观测面</b>（唯一观测面）：正常摄入下二者应接近 0——「收口成功」稀少表示罕见的跨实例
 * 快照竞态被修复，「守卫落空」更稀少表示修复让位于更晚提交者；二者持续升高说明并发合并同一条目的频率偏高。</p>
 *
 * @param entitiesWritten          实体实际写入（新增或更新）数
 * @param entitiesSkipped          实体短路跳过数（向量账本已覆盖本批来源，原样保留旧节点）
 * @param relationsWritten         关系实际写入数
 * @param relationsSkipped         关系短路跳过数
 * @param selfLoopDropped          自环边丢弃数（聚合阶段拒绝，扩展流程 5b）
 * @param reconcilesFixed          向量内容收口成功数（提交后校验发现错位并窄更新修复，实体+关系合计）
 * @param reconcilesGuardMissed    向量内容收口守卫落空数（让位于更晚提交者，实体+关系合计，不视为错误）
 * @author DeepDataAgent
 */
public record GraphMergeReport(
        int entitiesWritten,
        int entitiesSkipped,
        int relationsWritten,
        int relationsSkipped,
        int selfLoopDropped,
        int reconcilesFixed,
        int reconcilesGuardMissed) {

    /**
     * 便捷构造器：无收口活动（收口成功/守卫落空计数均为 0）。
     *
     * @param entitiesWritten 实体写入数
     * @param entitiesSkipped 实体短路跳过数
     * @param relationsWritten 关系写入数
     * @param relationsSkipped 关系短路跳过数
     * @param selfLoopDropped  自环边丢弃数
     */
    public GraphMergeReport(int entitiesWritten, int entitiesSkipped, int relationsWritten,
                            int relationsSkipped, int selfLoopDropped) {
        this(entitiesWritten, entitiesSkipped, relationsWritten, relationsSkipped, selfLoopDropped, 0, 0);
    }

    /**
     * 全零报告（空批次结果）。
     *
     * @return 空报告实例
     */
    public static GraphMergeReport empty() {
        return new GraphMergeReport(0, 0, 0, 0, 0, 0, 0);
    }
}
