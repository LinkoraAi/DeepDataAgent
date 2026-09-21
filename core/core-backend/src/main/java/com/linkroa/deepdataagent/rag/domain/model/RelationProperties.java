package com.linkroa.deepdataagent.rag.domain.model;

import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 图谱关系边属性（RelationProperties）。
 * <p>持久化为 {@code relation_edge_graph.properties} JSON 列（示例：{weight, description, keywords, sourceIds,
 * filePaths}，键名与 record 组件序列化的 camelCase 实况一致）。
 * weight 为累计权重（普通边 DEFAULT_EDGE_WEIGHT=1、belongs_to 边 BELONGS_TO_WEIGHT=10），
 * 语义为「存活来源的全额权重之和」——合并期逐来源全额累加，来源被清退时由图谱贡献重建服务按
 * 存活来源<b>全额重算</b>（权重回扣已实现，RQ-22 已平账；抽取缓存不可用时降级保留原值）；
 * sourceIds 为<b>图行展示列</b>（写入时按截断策略裁剪），「账本管幂等、权重管证据强度」——
 * 幂等短路与权重累加的过滤基准均为向量表 chunk_ids 权威账本，本列不再参与计权判定。</p>
 *
 * @param weight      累计权重（double，历史证据强度口径）
 * @param description 关系描述（空值直接抛错，关系不兜底）
 * @param keywords    关系关键词（去重排序后的集合）
 * @param sourceIds   来源分块ID展示列表（纯展示与归因用途，非计权基准）
 * @param filePaths   来源文件路径列表（graph-source-file-paths R1：全部贡献来源的去重保序列表，
 *                    同端点对关系被多篇文档贡献时含全部来源路径；落库前经
 *                    {@link GraphSourceFilePaths#normalize} 统一上限截断与溢出占位）
 */
public record RelationProperties(
        double weight,
        String description,
        List<String> keywords,
        List<Long> sourceIds,
        List<String> filePaths) {

    /**
     * 便捷构造（单来源抽取片段入口）：以单条来源文件路径构造列表形态属性。
     * <p>null 路径归一为空列表，非 null（含空白）原样承载为单元素列表；
     * 列表化裁剪与截断口径统一由 {@link GraphSourceFilePaths} 承担。</p>
     *
     * @param weight      累计权重
     * @param description 关系描述
     * @param keywords    关系关键词
     * @param sourceIds   来源分块ID列表
     * @param filePath    单条来源文件路径，可为 null
     */
    public RelationProperties(double weight, String description, List<String> keywords,
                              List<Long> sourceIds, String filePath) {
        this(weight, description, keywords, sourceIds, GraphSourceFilePaths.singletonListOrEmpty(filePath));
    }

    /**
     * 空属性：零权重、无描述、无来源（新建边默认值）。
     *
     * @return 空属性对象
     */
    public static RelationProperties empty() {
        return new RelationProperties(0, null, List.of(), List.of(), List.of());
    }

    /**
     * 新建单来源关系属性（抽取片段入口）。
     *
     * @param weight      片段权重（DEFAULT_EDGE_WEIGHT=1；belongs_to=BELONGS_TO_WEIGHT=10）
     * @param description 关系描述（可为 null，合并后仍空则抛错）
     * @param keywords    关系关键词（内部精确去重）
     * @param sourceId    来源分块ID（可为 null）
     * @param filePath    来源文件路径（可为 null，包装为单元素列表）
     * @return 初始属性对象
     */
    public static RelationProperties of(double weight, String description, List<String> keywords,
                                        Long sourceId, String filePath) {
        List<String> mergedKeywords = new ArrayList<>();
        if (!ObjectUtils.isEmpty(keywords)) {
            for (String keyword : keywords) {
                if (StringUtils.isNotBlank(keyword) && !mergedKeywords.contains(keyword)) {
                    mergedKeywords.add(keyword);
                }
            }
        }
        return new RelationProperties(weight, description, List.copyOf(mergedKeywords),
                ObjectUtils.isEmpty(sourceId) ? List.of() : List.of(sourceId),
                GraphSourceFilePaths.singletonListOrEmpty(filePath));
    }
}
