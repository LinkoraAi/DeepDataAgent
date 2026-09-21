package com.linkroa.deepdataagent.rag.infrastructure.persistence;

import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;

import java.time.OffsetDateTime;
import java.time.ZoneId;

/**
 * RAG BC 实体审计字段显式填充工具类（包内私有，纯静态、防实例化）。
 * <p>RAG 侧实体已脱离 {@code shared.BaseEntity} 与 {@code MybatisPlusMetaObjectHandler}
 * 体系（彻底物理删）：audit 字段无 {@code @TableField(fill=...)} 注解，
 * 全局 handler 的 strictInsertFill 对它们完全不触发，必须在 Repository 层
 * insert / update 前经本工具类显式填充。</p>
 * <p>时间口径与建表约定对齐：TIMESTAMPTZ 列统一按中国时区
 * {@link #CHINA_ZONE}（Asia/Shanghai）写入；操作人空白（null/空白串）回落
 * {@link #DEFAULT_OPERATOR}，保证 created_by / updated_by 落库语义完整。</p>
 * <p>与 KB BC 的 {@code KbAuditFieldUtils} 行为对称、互不引用（两 BC 各自持有）。</p>
 */
public final class RagAuditFieldUtils {

    /** 中国时区（audit 时间字段统一写读口径） */
    public static final ZoneId CHINA_ZONE = ZoneId.of("Asia/Shanghai");

    /** 缺省操作人（无操作人上下文时的系统级写入兜底） */
    public static final String DEFAULT_OPERATOR = "system";

    /**
     * 工具类私有构造器，防止实例化。
     */
    private RagAuditFieldUtils() {
        throw new UnsupportedOperationException("工具类不允许实例化");
    }

    /**
     * 当前时间（中国时区口径）。
     *
     * @return 以 {@link #CHINA_ZONE} 取时的 {@link OffsetDateTime}
     */
    public static OffsetDateTime now() {
        return OffsetDateTime.now(CHINA_ZONE);
    }

    /**
     * 插入前填充审计字段：createdAt = updatedAt = now，createdBy = updatedBy = 操作人。
     *
     * @param entity   待插入实体（为 null 时不处理，由调用方保证实体有效性）
     * @param operator 操作人（null/空白回落 {@link #DEFAULT_OPERATOR}）
     */
    public static void fillInsert(RagAuditable entity, String operator) {
        if (ObjectUtils.isEmpty(entity)) {
            return;
        }
        OffsetDateTime now = now();
        String resolvedOperator = resolveOperator(operator);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        entity.setCreatedBy(resolvedOperator);
        entity.setUpdatedBy(resolvedOperator);
    }

    /**
     * 更新前填充审计字段：updatedAt = now，updatedBy = 操作人（不触碰创建态字段）。
     *
     * @param entity   待更新实体（为 null 时不处理，由调用方保证实体有效性）
     * @param operator 操作人（null/空白回落 {@link #DEFAULT_OPERATOR}）
     */
    public static void fillUpdate(RagAuditable entity, String operator) {
        if (ObjectUtils.isEmpty(entity)) {
            return;
        }
        entity.setUpdatedAt(now());
        entity.setUpdatedBy(resolveOperator(operator));
    }

    /**
     * 操作人归一：null/空白串回落系统缺省操作人。
     *
     * @param operator 原始操作人，可为 null/空白
     * @return 有效操作人文本
     */
    private static String resolveOperator(String operator) {
        if (StringUtils.isBlank(operator)) {
            return DEFAULT_OPERATOR;
        }
        return operator;
    }
}
