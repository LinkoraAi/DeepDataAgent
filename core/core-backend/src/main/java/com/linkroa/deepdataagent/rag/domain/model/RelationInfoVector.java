package com.linkroa.deepdataagent.rag.domain.model;

import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 关系向量聚合根（对应 {@code relation_info_vector} 表，与 relation_edge_graph 1:1）。
 * <p>图边保留原始方向，向量身份使用 sorted 双向（{@link #normalizedPair()}）；
 * {@link #merge(RelationInfoVector)} 累积 chunkIds 并做 Map-Reduce 摘要定位（content 择优）。
 * content 为向量化内容 {@code keywords\t source\n target\n description}。</p>
 *
 * @param id          主键
 * @param kbId        所属知识库ID
 * @param sourceName  源实体名称（向量身份夹持 sorted 双向）
 * @param targetName  目标实体名称
 * @param content     向量化内容
 * @param chunkIds    关联分块ID列表（弱引用 chunk.id，merge 时累积）
 * @param vector      关系向量（pgvector）
 * @param createdAt   创建时间
 * @param updatedAt   更新时间
 */
public record RelationInfoVector(
        Long id,
        Long kbId,
        String sourceName,
        String targetName,
        String content,
        List<Long> chunkIds,
        float[] vector,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    /**
     * 紧凑构造器：不变量校验与兜底。
     */
    public RelationInfoVector {
        if (ObjectUtils.isEmpty(kbId)) {
            throw new IllegalArgumentException("关系向量必须关联知识库");
        }
        if (StringUtils.isBlank(sourceName) || StringUtils.isBlank(targetName)) {
            throw new IllegalArgumentException("关系向量端点不能为空");
        }
        if (StringUtils.equals(sourceName, targetName)) {
            throw new IllegalArgumentException("关系向量拒绝自环");
        }
        chunkIds = ObjectUtils.isEmpty(chunkIds) ? List.of() : chunkIds;
    }

    /**
     * 新建关系向量（摄入写入入口）。
     *
     * @param kbId        所属知识库ID
     * @param sourceName  源实体名称
     * @param targetName  目标实体名称
     * @param content     向量化内容
     * @param chunkIds    关联分块ID列表（可为空）
     * @param vector      关系向量（可为 null）
     * @return 初始关系向量
     */
    public static RelationInfoVector create(Long kbId, String sourceName, String targetName,
                                            String content, List<Long> chunkIds, float[] vector) {
        OffsetDateTime now = OffsetDateTime.now(ZoneId.of("Asia/Shanghai"));
        return new RelationInfoVector(null, kbId, sourceName, targetName, content, chunkIds, vector, now, now);
    }

    /**
     * 从数据库恢复关系向量。
     */
    public static RelationInfoVector restore(Long id, Long kbId, String sourceName, String targetName,
                                             String content, List<Long> chunkIds, float[] vector,
                                             OffsetDateTime createdAt, OffsetDateTime updatedAt) {
        return new RelationInfoVector(id, kbId, sourceName, targetName, content, chunkIds, vector,
                createdAt, updatedAt);
    }

    /**
     * 无向归一端点对：字典序较小者为 first（向量身份统一用 sorted 双向）。
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
     * 合并：chunkIds 去重并集 + content 择优（Map-Reduce 摘要定位）。仅同库同无向端点对允许合并。
     *
     * @param other 待合并的向量（null 视为无）
     * @return 合并后的新关系向量
     * @throws IllegalArgumentException 非同库或非同一无向端点对时抛出
     */
    public RelationInfoVector merge(RelationInfoVector other) {
        if (ObjectUtils.isEmpty(other)) {
            return this;
        }
        if (!Objects.equals(kbId, other.kbId) || !normalizedPair().equals(other.normalizedPair())) {
            throw new IllegalArgumentException("仅同知识库同无向端点对关系向量可合并");
        }
        Set<Long> merged = new LinkedHashSet<>(chunkIds);
        merged.addAll(other.chunkIds());
        int selfLen = content == null ? 0 : content.length();
        int otherLen = other.content() == null ? 0 : other.content().length();
        String chosen = selfLen >= otherLen ? content : other.content();
        float[] chosenVector = ObjectUtils.isEmpty(vector) ? other.vector() : vector;
        return new RelationInfoVector(id, kbId, sourceName, targetName, chosen, List.copyOf(merged),
                chosenVector, createdAt, updatedAt);
    }

    /**
     * 构造关系向量化内容：{@code keywords\t source\n target\n description}。
     *
     * @param keywords    关键词（去重排序 join 分隔符）
     * @param sourceName  源实体名称
     * @param targetName  目标实体名称
     * @param description 关系描述（可为空串）
     * @return 向量化内容
     */
    public static String buildContent(List<String> keywords, String sourceName, String targetName,
                                      String description) {
        String joined = StringUtils.join(ObjectUtils.isEmpty(keywords) ? List.of() : keywords, ",");
        return joined + "\t" + sourceName + "\n" + targetName + "\n" + StringUtils.defaultString(description);
    }
}