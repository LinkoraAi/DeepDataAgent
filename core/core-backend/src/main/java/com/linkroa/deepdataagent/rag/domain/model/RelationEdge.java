package com.linkroa.deepdataagent.rag.domain.model;

import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 知识图谱关系边聚合根。
 * <p>不变量：拒绝自环（source==target 构造函数即抛错）；边无向归一（(a,b) 与 (b,a) 视为同一对，
 * 由 {@link #normalizedPair()} 表达）；落库方向恒为端点名归一后的字典序（较小为源、较大为目标，
 * 在抽取聚合出口定序），图行与向量行共用同一归一身份，反向残留行无从产生。</p>
 * <p>权重不变量（与 LightRAG 合并端同构，「账本管幂等、权重管历史强度」）：weight =
 * 去重后贡献来源的加权和——每个「未记录在<b>向量账本</b>（权威来源账本）中」的贡献来源按记录全额
 * 累加一次；已记账的来源不再重复累加（幂等重放增量为 0）。图行自身 source_ids 为展示列，
 * MUST NOT 作为计权基准（见 {@link #mergeContributions(List, List)}）。
 * 上游 LightRAG「同 chunk 双抽同一对端点 → 重复计权 +2.0」的批内不去重形态在本管线不可达：
 * 同 (端点对, 来源) 记录已在抽取汇聚器归一为一条（取最大权重），「逐来源全额累加」公式本身
 * 不变（统一口径见 {@code GraphMergeService#aggregateEdges}、{@code ExtractionHolder} 类注释，
 * 三处互相引用）。</p>
 *
 * @param kbId       所属知识库ID
 * @param sourceName 源实体名称（恒为归一方向：字典序较小者）
 * @param targetName 目标实体名称（恒为归一方向：字典序较大者）
 * @param properties 关系属性（RelationProperties）
 */
public record RelationEdge(Long kbId, String sourceName, String targetName, RelationProperties properties) {

    /**
     * 紧凑构造器：不变量校验与属性兜底（自环拒绝）。
     */
    public RelationEdge {
        if (ObjectUtils.isEmpty(kbId)) {
            throw new IllegalArgumentException("关系必须关联知识库");
        }
        if (StringUtils.isBlank(sourceName) || StringUtils.isBlank(targetName)) {
            throw new IllegalArgumentException("关系端点不能为空");
        }
        if (StringUtils.equals(sourceName, targetName)) {
            throw new IllegalArgumentException("关系拒绝自环：" + sourceName);
        }
        properties = ObjectUtils.isEmpty(properties) ? RelationProperties.empty() : properties;
    }

    /**
     * 新建关系边（抽取片段入口）。
     *
     * @param kbId        所属知识库ID
     * @param sourceName  源实体名称
     * @param targetName  目标实体名称
     * @param weight      片段权重（普通 1 / belongs_to 10）
     * @param description 关系描述（可为 null）
     * @param keywords    关系关键词
     * @param sourceId    来源分块ID
     * @param filePath    来源文件路径（可为 null）
     * @return 初始关系边
     */
    public static RelationEdge create(Long kbId, String sourceName, String targetName, double weight,
                                      String description, List<String> keywords, Long sourceId, String filePath) {
        return new RelationEdge(kbId, sourceName, targetName,
                RelationProperties.of(weight, description, keywords, sourceId, filePath));
    }

    /**
     * 无向归一端点对：字典序较小者为 first，(a,b) 与 (b,a) 返回同一对。
     * 作为向量表身份键与「平行边等值判断」依据。
     *
     * @return 长度 2 的有序端点列表
     */
    public List<String> normalizedPair() {
        if (sourceName.compareTo(targetName) <= 0) {
            return List.of(sourceName, targetName);
        }
        return List.of(targetName, sourceName);
    }

    /**
     * 贡献记录合并（兼容便捷入口）：以本边自身 source_ids 快照为过滤基准，
     * 语义等价于 {@link #mergeContributions(List, List)} 传入 {@code this.source_ids}。
     *
     * @param records 贡献记录列表（sourceId 可空 + weight 全额）；为空时返回自身
     * @return 合并后的新关系边（端点与方向不变）
     */
    public RelationEdge mergeContributions(List<RelationContribution> records) {
        return mergeContributions(records, safeSourceIds());
    }

    /**
     * 贡献记录合并（权重唯一权威口径，与 LightRAG 合并端逐来源全额累加同构，「账本管幂等、
     * 权重管历史强度」）：
     * <pre>
     * weight = Σ{记录全额 | 记录.sourceId 为 null，或 ∉ 传入的向量账本 ledgerChunkIds} + 本边已存 weight
     * </pre>
     * 过滤基准为<b>向量表 chunk_ids 账本</b>（{@code ledgerChunkIds}，与幂等短路同源），
     * 而非图行自身展示列 source_ids——图行 source_ids 会被截断且不受收敛收缩，作为基准会导致
     * 「重复解析同一文档 → 权重逐次膨胀」。账本为空/null 时 fail-open（判「未覆盖」全额累加）。
     * 基准取调用方传入的<b>合并前</b>账本快照，而非随循环追加的账本——同一新来源若出现多条记录
     * 则各自全额计入（该形态在本管线不可达：同 (端点对, 来源) 已在抽取汇聚器归一，见类注释统一口径）；
     * 已记账来源增量为 0（幂等重放）。null 来源记录无条件全额累加。
     * 本边 source_ids 并入新来源（旧值优先，LinkedHashSet 去重）仅作展示列维护。
     * 不改动 description/keywords/filePaths。
     *
     * @param records         贡献记录列表（sourceId 可空 + weight 全额）；为空时返回自身
     * @param ledgerChunkIds  向量账本快照（幂等短路与计权共用的权威依据；为空表示无既记账，全额累加）
     * @return 合并后的新关系边（端点与方向不变）
     */
    public RelationEdge mergeContributions(List<RelationContribution> records, List<Long> ledgerChunkIds) {
        if (ObjectUtils.isEmpty(records)) {
            return this;
        }
        Set<Long> ledgerSnapshot = new LinkedHashSet<>(
                ObjectUtils.isEmpty(ledgerChunkIds) ? List.of() : ledgerChunkIds);
        Set<Long> mergedSources = new LinkedHashSet<>(safeSourceIds());
        double added = 0.0;
        for (RelationContribution record : records) {
            if (ObjectUtils.isEmpty(record)) {
                continue;
            }
            Long sourceId = record.sourceId();
            if (ObjectUtils.isEmpty(sourceId) || !ledgerSnapshot.contains(sourceId)) {
                added += record.weight();
            }
            if (ObjectUtils.isNotEmpty(sourceId)) {
                mergedSources.add(sourceId);
            }
        }
        double mergedWeight = properties().weight() + added;
        RelationProperties merged = new RelationProperties(mergedWeight, properties().description(),
                properties().keywords(), List.copyOf(new ArrayList<>(mergedSources)), properties().filePaths());
        return new RelationEdge(kbId, sourceName, targetName, merged);
    }

    /**
     * 骨架合并：只融合非权重字段——keywords 并集去重后按字符串自然排序；description 以自身为主、
     * 空白时取 other，两侧均空白抛错（关系不兜底）；filePaths 经
     * {@link GraphSourceFilePaths#union} 去重保序并集（自身在前、对方追加在后，同一文件只记一次）；
     * 端点方向与 weight/source_ids 沿用自身（权重口径由 {@link #mergeContributions} 决定）。
     *
     * @param other 并入方关系边（同无向端点对；null 视为无）
     * @return 骨架融合后的新关系边
     * @throws IllegalArgumentException 非同库 / 不同无向端点对 / 合并后描述为空时抛出
     */
    public RelationEdge mergeSkeleton(RelationEdge other) {
        if (ObjectUtils.isEmpty(other)) {
            return this;
        }
        checkMergeable(other);
        Set<String> mergedKeywords = new LinkedHashSet<>(safeKeywords());
        mergedKeywords.addAll(other.properties().keywords());
        List<String> sortedKeywords = new ArrayList<>(mergedKeywords);
        Collections.sort(sortedKeywords);
        String mergedDescription = StringUtils.defaultIfBlank(properties().description(),
                other.properties().description());
        if (StringUtils.isBlank(mergedDescription)) {
            throw new IllegalArgumentException("关系描述不能为空：" + normalizedPair());
        }
        List<String> mergedFilePaths = GraphSourceFilePaths.union(properties().filePaths(),
                other.properties().filePaths());
        RelationProperties merged = new RelationProperties(properties().weight(), mergedDescription,
                List.copyOf(sortedKeywords), properties().sourceIds(), mergedFilePaths);
        return new RelationEdge(kbId, sourceName, targetName, merged);
    }

    /**
     * 平行边融合（便捷组合，保留兼容）：先做骨架合并，再把 other 的 source_ids 展开为
     * 「每来源一条全额记录」并入权重账本，语义与 {@link #mergeContributions} 同一公式。
     *
     * @param other 同无向端点对的新片段（null 视为无）
     * @return 合并后的边（方向沿用本边原始方向）
     * @throws IllegalArgumentException 非同库 / 不同无向端点对 / 合并后描述为空时抛出
     */
    public RelationEdge merge(RelationEdge other) {
        if (ObjectUtils.isEmpty(other)) {
            return this;
        }
        return mergeSkeleton(other).mergeContributions(expandContributions(other));
    }

    /**
     * 将一条边的属性展开为贡献记录列表：每个 sourceId 对应「该来源全额 = 边权重」的一条记录；
     * source_ids 为空时展开为单条 null 来源记录（无条件全额累加口径）。
     * 供批内聚合与图内平行行折叠复用，保证合并端处处同一公式。
     *
     * @param edge 待展开的关系边（可为 null）
     * @return 贡献记录列表（edge 为 null 时返回空列表）
     */
    public static List<RelationContribution> expandContributions(RelationEdge edge) {
        if (ObjectUtils.isEmpty(edge)) {
            return List.of();
        }
        List<Long> sources = edge.safeSourceIds();
        double weight = edge.properties().weight();
        if (sources.isEmpty()) {
            return List.of(new RelationContribution(null, weight));
        }
        List<RelationContribution> records = new ArrayList<>(sources.size());
        for (Long source : sources) {
            records.add(new RelationContribution(source, weight));
        }
        return List.copyOf(records);
    }

    /**
     * 合并前置校验：同知识库、同无向端点对。
     */
    private void checkMergeable(RelationEdge other) {
        if (!Objects.equals(kbId, other.kbId)) {
            throw new IllegalArgumentException("仅同知识库关系可合并");
        }
        if (!normalizedPair().equals(other.normalizedPair())) {
            throw new IllegalArgumentException("仅同无向端点对关系可合并");
        }
    }

    private List<Long> safeSourceIds() {
        List<Long> sourceIds = properties().sourceIds();
        return ObjectUtils.isEmpty(sourceIds) ? List.of() : sourceIds;
    }

    private List<String> safeKeywords() {
        List<String> keywords = properties().keywords();
        return ObjectUtils.isEmpty(keywords) ? List.of() : keywords;
    }
}