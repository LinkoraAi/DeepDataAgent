package com.linkroa.deepdataagent.rag.domain.service;

import com.linkroa.deepdataagent.rag.domain.model.EntityNode;
import com.linkroa.deepdataagent.rag.domain.model.EntityProperties;
import com.linkroa.deepdataagent.rag.domain.model.RelationEdge;
import com.linkroa.deepdataagent.rag.domain.model.RelationProperties;
import org.apache.commons.lang3.ObjectUtils;

import java.util.List;
import java.util.Map;

/**
 * 图谱贡献重建计划（阶段 A 产出的纯内存值对象，design D3 步骤②→③的载体）。
 * <p><b>零写入承诺</b>：本对象只是「受影响条目分类 + 逐分块重放记录 + 已算好的绝对值候选」的
 * 不可变快照；阶段 B（{@link GraphContributionRebuildService#apply}）以事务内加锁重读的账本为
 * 唯一写回基准，计划中的候选值仅在与锁定账本推出的存活集合<b>完全一致</b>时被直接采用
 * （spec R4 / design D9：不采信阶段 A 快照）。</p>
 * <p>条目清单按实体名 / 归一端点对升序装载——与 {@code lockByKbIdAndNames} 等既有加锁方法的
 * 加锁序同向，阶段 B 按本序逐条处理即满足固定加锁顺序（事务规范 3.1）。</p>
 *
 * @param deletedChunkIds    本批被删分块ID（去 null 去重升序，账本剔除集）
 * @param entities           需重建的实体条目计划列表（实体名升序）
 * @param relations          需重建的关系条目计划列表（归一端点对升序）
 * @param prunedEntityNames  分类阶段即「剔后无存活来源」的实体名（直接物理删除，升序）
 * @param prunedRelationPairs 分类阶段即「剔后无存活来源」的归一端点对（直接物理删除，升序）
 * @author DeepDataAgent
 */
public record GraphRebuildPlan(
        List<Long> deletedChunkIds,
        List<EntityRebuildItem> entities,
        List<RelationRebuildItem> relations,
        List<String> prunedEntityNames,
        List<List<String>> prunedRelationPairs) {

    /**
     * 紧凑构造器：列表空值归一为不可变空列表（下游遍历零防御）。
     */
    public GraphRebuildPlan {
        deletedChunkIds = ObjectUtils.isEmpty(deletedChunkIds) ? List.of() : List.copyOf(deletedChunkIds);
        entities = ObjectUtils.isEmpty(entities) ? List.of() : List.copyOf(entities);
        relations = ObjectUtils.isEmpty(relations) ? List.of() : List.copyOf(relations);
        prunedEntityNames = ObjectUtils.isEmpty(prunedEntityNames) ? List.of() : List.copyOf(prunedEntityNames);
        prunedRelationPairs = ObjectUtils.isEmpty(prunedRelationPairs) ? List.of() : List.copyOf(prunedRelationPairs);
    }

    /**
     * 空计划（本批被删分块不产生任何图谱贡献：重建整体跳过、对图谱零读写）。
     *
     * @param deletedChunkIds 本批被删分块ID（可为空）
     * @return 空计划实例
     */
    public static GraphRebuildPlan empty(List<Long> deletedChunkIds) {
        return new GraphRebuildPlan(deletedChunkIds, List.of(), List.of(), List.of(), List.of());
    }

    /**
     * 是否空计划（apply 对空计划 MUST NOT 发起任何 DB 交互）。
     *
     * @return 无任何受影响条目返回 true
     */
    public boolean isEmpty() {
        return entities.isEmpty() && relations.isEmpty()
                && prunedEntityNames.isEmpty() && prunedRelationPairs.isEmpty();
    }

    /**
     * 实体条目重建计划（阶段 B 的确定性重算输入：绝对值 = 锁定存活集合的函数）。
     * <p>阶段 A 已按快照存活集合算好候选绝对值（{@code final*} 字段）；阶段 B 加锁重读后若
     * 存活集合与 {@link #snapshotSurvivors} 不一致（并发合并/并发删除），用
     * {@link #survivorRecords} 在内存中按锁定集合过滤重算（纯内存、零远程）——描述退化为
     * 确定性聚合（换行拼接）而非 LLM 摘要，按 design D9 的既定取舍接受「与账本的一轮短暂偏差，
     * 条目下次合并/删除整体重建即收敛」。</p>
     *
     * @param entityName        实体名（条目身份，重放记录名已归一、可直接等值匹配）
     * @param snapshotSurvivors 阶段 A 快照存活账本（账本剔除被删分块后，升序）
     * @param survivorRecords   存活分块的重放记录（每分块至多一条该实体记录，
     *                          {@code properties.sourceIds} 为该分块单元素列表；降级条目为空列表）
     * @param survivorFilePaths 存活分块「chunkId → 来源文件路径」已知映射（不可见分块缺键——
     *                          路径按可得集归一，spec R3「仅修账本与来源路径」口径下的已知部分）
     * @param currentProperties 阶段 A 快照的图行当前属性（降级保留语义字段的原值来源；
     *                          阶段 B 仍以加锁重读值为准，本字段仅在锁定图行缺失时兜底）
     * @param degraded          是否降级（存活分块缓存缺失/不可解析，或该条目无任何可重放记录——
     *                          阶段 B 保留原语义字段、仅修账本与来源路径，计入降级报告）
     * @param finalDescription  阶段 A 摘要产出的描述候选（降级时为 null）
     * @param finalEntityType   阶段 A 票数众数类型（降级时为 null）
     * @param finalVotes        阶段 A 存活类型票统计（降级时为 null）
     * @param finalContent      阶段 A 派生的向量行内容候选（降级时为 null）
     * @param finalVector       阶段 A 复用或补算的向量（降级时为 null；与 {@code finalContent} 成对）
     * @author DeepDataAgent
     */
    public record EntityRebuildItem(
            String entityName,
            List<Long> snapshotSurvivors,
            List<EntityNode> survivorRecords,
            Map<Long, String> survivorFilePaths,
            EntityProperties currentProperties,
            boolean degraded,
            String finalDescription,
            String finalEntityType,
            Map<String, Integer> finalVotes,
            String finalContent,
            float[] finalVector) {

        /**
         * 紧凑构造器：集合空值归一（记录/映射/票统计恒非 null，降低阶段 B 防御分支）。
         */
        public EntityRebuildItem {
            snapshotSurvivors = ObjectUtils.isEmpty(snapshotSurvivors) ? List.of() : List.copyOf(snapshotSurvivors);
            survivorRecords = ObjectUtils.isEmpty(survivorRecords) ? List.of() : List.copyOf(survivorRecords);
            survivorFilePaths = ObjectUtils.isEmpty(survivorFilePaths) ? Map.of() : Map.copyOf(survivorFilePaths);
            currentProperties = ObjectUtils.isEmpty(currentProperties) ? EntityProperties.empty() : currentProperties;
            finalVotes = ObjectUtils.isEmpty(finalVotes) ? Map.of() : finalVotes;
        }
    }

    /**
     * 关系条目重建计划（字段语义与 {@link EntityRebuildItem} 同构，差异仅在重算字段集：
     * 权重=存活记录全额求和（权重回扣，spec R2 / design D6）、关键词=拆分去重后字典序）。
     *
     * @param sourceName        归一端点对之源（字典序较小）
     * @param targetName        归一端点对之目标（字典序较大）
     * @param snapshotSurvivors 阶段 A 快照存活账本（升序）
     * @param survivorRecords   存活分块的重放边记录（每条边 sourceIds 为单分块、weight 为该记录全额）
     * @param survivorFilePaths 存活分块「chunkId → 来源文件路径」已知映射
     * @param currentProperties 阶段 A 快照的图边当前属性（降级原值来源）
     * @param degraded          是否降级
     * @param finalWeight       阶段 A 求和的权重候选（降级时无意义）
     * @param finalKeywords     阶段 A 去重排序的关键词候选（降级时为 null）
     * @param finalDescription  阶段 A 摘要产出的描述候选（降级时为 null）
     * @param finalContent      阶段 A 派生的向量行内容候选（降级时为 null）
     * @param finalVector       阶段 A 复用或补算的向量（降级时为 null）
     * @author DeepDataAgent
     */
    public record RelationRebuildItem(
            String sourceName,
            String targetName,
            List<Long> snapshotSurvivors,
            List<RelationEdge> survivorRecords,
            Map<Long, String> survivorFilePaths,
            RelationProperties currentProperties,
            boolean degraded,
            double finalWeight,
            List<String> finalKeywords,
            String finalDescription,
            String finalContent,
            float[] finalVector) {

        /**
         * 紧凑构造器：集合空值归一。
         */
        public RelationRebuildItem {
            snapshotSurvivors = ObjectUtils.isEmpty(snapshotSurvivors) ? List.of() : List.copyOf(snapshotSurvivors);
            survivorRecords = ObjectUtils.isEmpty(survivorRecords) ? List.of() : List.copyOf(survivorRecords);
            survivorFilePaths = ObjectUtils.isEmpty(survivorFilePaths) ? Map.of() : Map.copyOf(survivorFilePaths);
            currentProperties = ObjectUtils.isEmpty(currentProperties) ? RelationProperties.empty() : currentProperties;
            finalKeywords = ObjectUtils.isEmpty(finalKeywords) ? List.of() : List.copyOf(finalKeywords);
        }

        /**
         * 归一端点对（与 {@code RelationEdge#normalizedPair()} 同口径，报告与日志定位用）。
         *
         * @return 长度 2 的有序端点列表
         */
        public List<String> normalizedPair() {
            return List.of(sourceName, targetName);
        }
    }
}
