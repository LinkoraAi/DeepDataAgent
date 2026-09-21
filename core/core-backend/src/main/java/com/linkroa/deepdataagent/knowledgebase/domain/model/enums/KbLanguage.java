package com.linkroa.deepdataagent.knowledgebase.domain.model.enums;

import org.apache.commons.lang3.StringUtils;

import java.util.Optional;

/**
 * 知识库语言枚举（真相源口径见）。
 * <p>{@code knowledge_base.language} 列（及其 {@code KnowledgeBase.language} 聚合字段，语言唯一真相源）
 * 的值域：11 个语言全名枚举，缺省（未配置 / 空白）视同 {@link #Chinese}。创建/更新时值域外内容
 * （含历史裸语言码，如 {@code ja}）由 {@code KnowledgeBaseValidator#validateLanguage} 校验拒绝，
 * 不做静默回落；列值按原文保留（存量大小写变体如 {@code ENGLISH} 在读取侧经 {@link #find} 归一为全名），
 * {@code rag_engine_config} JSONB 的 language 键已弃用。</p>
 * <p>与文档解析引擎自身的 {@code language} / {@code lang_list}（OCR 多语言列表参数，
 * 如 {@code ch_en}）完全解耦：两者各自独立配置，互不校验、互不联动。</p>
 */
public enum KbLanguage {

    /** 英语 */
    English,

    /** 中文（知识库语言缺省值：未配置 / 空白视同本项） */
    Chinese,

    /** 西班牙语 */
    Spanish,

    /** 法语 */
    French,

    /** 德语 */
    German,

    /** 日语 */
    Japanese,

    /** 韩语 */
    Korean,

    /** 越南语 */
    Vietnamese,

    /** 阿拉伯语 */
    Arabic,

    /** 土耳其语 */
    Turkish,

    /** 荷兰语 */
    Dutch;

    /**
     * 查找与给定语言标识匹配的枚举项（trim 后按全名大小写不敏感匹配）。
     *
     * @param code 语言标识（语言全名；裸语言码 / 未收录语言 / 空白不命中）
     * @return 命中的枚举项；未命中返回空
     */
    public static Optional<KbLanguage> find(String code) {
        String normalized = StringUtils.trimToEmpty(code);
        for (KbLanguage candidate : values()) {
            if (StringUtils.equalsIgnoreCase(candidate.name(), normalized)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    /**
     * 是否为中文（模板套「{@code Chinese} 用 zh 套、其余语言用 en 套」判定用）。
     *
     * @return 中文返回 true
     */
    public boolean isChinese() {
        return this == Chinese;
    }
}
