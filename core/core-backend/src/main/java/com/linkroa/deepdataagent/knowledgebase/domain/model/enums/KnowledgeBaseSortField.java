package com.linkroa.deepdataagent.knowledgebase.domain.model.enums;

import org.apache.commons.lang3.StringUtils;

import java.util.Optional;

/**
 * 知识库列表排序字段白名单枚举。
 * <p>code 为对外 API 参数（sortBy）的合法取值，仅开放名称与两个时间列，
 * 白名单外的取值一律由应用层拒绝，防止排序注入与全表扫描扩散。</p>
 *
 * @param code 对外排序参数取值（驼峰式）
 */
public enum KnowledgeBaseSortField {

    /** 按知识库名称排序 */
    NAME("name"),

    /** 按创建时间排序（默认字段） */
    CREATED_AT("createdAt"),

    /** 按更新时间排序 */
    UPDATED_AT("updatedAt");

    /** 对外排序参数取值 */
    private final String code;

    KnowledgeBaseSortField(String code) {
        this.code = code;
    }

    /**
     * 获取对外排序参数取值。
     *
     * @return 驼峰式参数取值
     */
    public String code() {
        return code;
    }

    /**
     * 按参数取值解析排序字段（忽略大小写与首尾空白）。
     *
     * @param code 排序参数取值，可为空
     * @return 命中的排序字段；白名单外或为空返回空
     */
    public static Optional<KnowledgeBaseSortField> fromCode(String code) {
        if (StringUtils.isBlank(code)) {
            return Optional.empty();
        }
        String normalized = code.trim();
        for (KnowledgeBaseSortField candidate : values()) {
            if (StringUtils.equalsIgnoreCase(candidate.code, normalized)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }
}
