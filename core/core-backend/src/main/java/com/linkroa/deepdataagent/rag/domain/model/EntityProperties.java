package com.linkroa.deepdataagent.rag.domain.model;

import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 图谱实体节点属性（EntityProperties）。
 * <p>持久化为 {@code entity_node_graph.properties} JSON 列（示例：{entityType, description, sourceIds, filePaths,
 * entityTypeVotes, descriptions}，键名与 record 组件序列化的 camelCase 实况一致）。
 * 承载实体合并所需的追踪状态：sourceIds（来源分块弱引用 chunk.id，<b>图行展示列</b>——
 * 「账本管幂等、权重管历史强度」，幂等短路的权威账本为向量表 chunk_ids，本列不参与计权/覆盖判定）、
 * entityTypeVotes（类型频次统计，用于 Counter 频次降序取首）、
 * descriptions（描述原文累积，跨新旧精确去重，供 Map-Reduce 摘要）。</p>
 *
 * @param entityType      主类型（由 entityTypeVotes 频次取首）
 * @param description     最终描述（空值由 EntityNode 兜底为 {@code Entity {name}}）
 * @param sourceIds       来源分块ID展示列表（随 merge 累积去重，非幂等判定基准）
 * @param filePaths       来源文件路径列表（graph-source-file-paths R1：全部贡献来源的去重保序列表，
 *                        同名实体被多篇文档贡献时含全部来源路径；落库前经
 *                        {@link GraphSourceFilePaths#normalize} 统一上限截断与溢出占位）
 * @param entityTypeVotes 类型频次统计（LinkedHashMap，出现顺序即新旧顺序）
 * @param descriptions    描述原文累积（精确去重，避免引号/换行差异造成重复累加）
 */
public record EntityProperties(
        String entityType,
        String description,
        List<Long> sourceIds,
        List<String> filePaths,
        Map<String, Integer> entityTypeVotes,
        List<String> descriptions) {

    /**
     * 便捷构造（单来源抽取片段入口）：以单条来源文件路径构造列表形态属性。
     * <p>null 路径归一为空列表，非 null（含空白）原样承载为单元素列表；
     * 列表化裁剪与截断口径统一由 {@link GraphSourceFilePaths} 承担。</p>
     *
     * @param entityType      主类型
     * @param description     最终描述
     * @param sourceIds       来源分块ID列表
     * @param filePath        单条来源文件路径，可为 null
     * @param entityTypeVotes 类型频次统计
     * @param descriptions    描述原文累积
     */
    public EntityProperties(String entityType, String description, List<Long> sourceIds,
                            String filePath, Map<String, Integer> entityTypeVotes, List<String> descriptions) {
        this(entityType, description, sourceIds, GraphSourceFilePaths.singletonListOrEmpty(filePath),
                entityTypeVotes, descriptions);
    }

    /**
     * 空属性：无类型、无描述、无来源（新建实体默认值）。
     *
     * @return 空属性对象
     */
    public static EntityProperties empty() {
        return new EntityProperties(null, null, List.of(), List.of(), Map.of(), List.of());
    }

    /**
     * 新建单来源实体属性（抽取片段入口）。
     *
     * @param entityType  实体类型（可为 null）
     * @param description 实体描述（可为 null）
     * @param sourceId    来源分块ID（可为 null）
     * @param filePath    来源文件路径（可为 null，包装为单元素列表）
     * @return 初始属性对象
     */
    public static EntityProperties of(String entityType, String description, Long sourceId, String filePath) {
        List<Long> mergedSources = new ArrayList<>();
        if (ObjectUtils.isNotEmpty(sourceId)) {
            mergedSources.add(sourceId);
        }
        Map<String, Integer> votes = StringUtils.isBlank(entityType) ? Map.of() : Map.of(entityType, 1);
        List<String> descs = new ArrayList<>();
        if (StringUtils.isNotBlank(description)) {
            descs.add(description);
        }
        return new EntityProperties(entityType, description, List.copyOf(mergedSources),
                GraphSourceFilePaths.singletonListOrEmpty(filePath), votes, List.copyOf(descs));
    }

    /**
     * 从类型频次统计取主类型：频次降序取首，同频时新数据（后出现）优先。
     *
     * @param votes 类型频次 Map（LinkedHashMap 保证出现顺序）
     * @return 主类型名称；votes 为空返回 null
     */
    public static String primaryEntityType(Map<String, Integer> votes) {
        if (ObjectUtils.isEmpty(votes)) {
            return null;
        }
        String primary = null;
        int max = -1;
        for (Map.Entry<String, Integer> entry : votes.entrySet()) {
            // 同频时后出现者（新数据）优先
            if (primary == null || entry.getValue() >= max) {
                primary = entry.getKey();
                max = entry.getValue();
            }
        }
        return primary;
    }

    /**
     * 合并另一份实体属性（同名实体归一）：
     * sourceIds 去重并集（展示列累积）、类型频次累加、描述原文跨新旧精确去重追加，
     * filePaths 经 {@link GraphSourceFilePaths#union} 去重保序并集（自身在前、对方追加在后，
     * 同一文件重复贡献只记一次），并按 {@link #primaryEntityType} 频次降序取首重算主类型。
     *
     * @param other 待合并的另一份属性（null 视为空属性）
     * @return 合并后的新属性对象（不可变）
     */
    public EntityProperties merge(EntityProperties other) {
        if (ObjectUtils.isEmpty(other)) {
            return this;
        }
        Set<Long> mergedSourceIds = new LinkedHashSet<>(safeSourceIds());
        mergedSourceIds.addAll(other.safeSourceIds());

        Map<String, Integer> mergedVotes = new LinkedHashMap<>(safeVotes());
        for (Map.Entry<String, Integer> entry : other.safeVotes().entrySet()) {
            mergedVotes.merge(entry.getKey(), entry.getValue(), Integer::sum);
        }

        List<String> mergedDescriptions = new ArrayList<>(safeDescriptions());
        for (String desc : other.safeDescriptions()) {
            if (StringUtils.isNotBlank(desc) && !mergedDescriptions.contains(desc)) {
                mergedDescriptions.add(desc);
            }
        }

        String mergedType = primaryEntityType(mergedVotes);
        String mergedDescription = StringUtils.defaultIfBlank(description, other.description());
        List<String> mergedFilePaths = GraphSourceFilePaths.union(safeFilePaths(), other.safeFilePaths());

        return new EntityProperties(mergedType, mergedDescription,
                List.copyOf(mergedSourceIds), mergedFilePaths,
                mergedVotes, List.copyOf(mergedDescriptions));
    }

    private List<Long> safeSourceIds() {
        return ObjectUtils.isEmpty(sourceIds) ? List.of() : sourceIds;
    }

    private List<String> safeFilePaths() {
        return ObjectUtils.isEmpty(filePaths) ? List.of() : filePaths;
    }

    private Map<String, Integer> safeVotes() {
        return ObjectUtils.isEmpty(entityTypeVotes) ? Map.of() : entityTypeVotes;
    }

    private List<String> safeDescriptions() {
        return ObjectUtils.isEmpty(descriptions) ? List.of() : descriptions;
    }
}
