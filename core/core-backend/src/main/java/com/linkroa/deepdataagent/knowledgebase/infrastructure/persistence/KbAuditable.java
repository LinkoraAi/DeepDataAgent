package com.linkroa.deepdataagent.knowledgebase.infrastructure.persistence;

import java.time.OffsetDateTime;

/**
 * KB BC 持久化实体审计字段契约接口（物理删体系，与 {@code shared.BaseEntity} 无关）。
 * <p>KB 侧实体彻底脱离逻辑删除：不继承 BaseEntity、无 is_deleted 列，
 * audit 四字段（createdAt/updatedAt/createdBy/updatedBy）由实体自持，
 * 写入前经 {@link KbAuditFieldUtils#fillInsert} / {@link KbAuditFieldUtils#fillUpdate}
 * 显式填充（注解 SQL 与批量写入均不触发 MetaObjectHandler）。</p>
 * <p>本接口仅暴露字段读写方法，供工具类统一填充；Lombok {@code @Data} 实体天然实现本接口。
 * 时间列统一 TIMESTAMPTZ，Java 侧使用 {@link OffsetDateTime}，
 * 按中国时区（{@link KbAuditFieldUtils#CHINA_ZONE}）写入。</p>
 */
public interface KbAuditable {

    /**
     * 获取创建时间。
     *
     * @return 创建时间（插入前由 {@link KbAuditFieldUtils#fillInsert} 填充）
     */
    OffsetDateTime getCreatedAt();

    /**
     * 设置创建时间。
     *
     * @param createdAt 创建时间
     */
    void setCreatedAt(OffsetDateTime createdAt);

    /**
     * 获取更新时间。
     *
     * @return 最近一次更新时间
     */
    OffsetDateTime getUpdatedAt();

    /**
     * 设置更新时间。
     *
     * @param updatedAt 更新时间
     */
    void setUpdatedAt(OffsetDateTime updatedAt);

    /**
     * 获取创建人。
     *
     * @return 创建人（无操作人上下文时兜底为 {@link KbAuditFieldUtils#DEFAULT_OPERATOR}）
     */
    String getCreatedBy();

    /**
     * 设置创建人。
     *
     * @param createdBy 创建人
     */
    void setCreatedBy(String createdBy);

    /**
     * 获取更新人。
     *
     * @return 最近一次更新人
     */
    String getUpdatedBy();

    /**
     * 设置更新人。
     *
     * @param updatedBy 更新人
     */
    void setUpdatedBy(String updatedBy);
}
