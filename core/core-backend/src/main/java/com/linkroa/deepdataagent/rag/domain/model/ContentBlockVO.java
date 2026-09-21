package com.linkroa.deepdataagent.rag.domain.model;

import org.apache.commons.lang3.StringUtils;

import java.util.Map;

/**
 * 解析出的结构化内容块值对象。
 * <p>映射 MinerU 产物的 content_list 五类块（text / image / table / equation / generic），
 * 是文档解析（LocalTika / MinerU）与分块 / 多模态管线的统一输入单元。</p>
 *
 * @param type 内容块类型（TEXT / IMAGE / TABLE / EQUATION / GENERIC）
 * @param text 块文本内容（多模态块为描述文本或占位信息）
 * @param meta 扩展元数据（页码 page、坐标 positions、布局 layout 等，含解析器坐标标签剥离后的原值）
 */
public record ContentBlockVO(String type, String text, Map<String, Object> meta) {

    /** 文本块。 */
    public static final String TYPE_TEXT = "TEXT";
    /** 图片块。 */
    public static final String TYPE_IMAGE = "IMAGE";
    /** 表格块。 */
    public static final String TYPE_TABLE = "TABLE";
    /** 公式块。 */
    public static final String TYPE_EQUATION = "EQUATION";
    /** 泛型块。 */
    public static final String TYPE_GENERIC = "GENERIC";

    /**
     * 紧凑构造器：类型不变量校验。
     */
    public ContentBlockVO {
        if (StringUtils.isBlank(type)) {
            throw new IllegalArgumentException("内容块类型不能为空");
        }
    }

    /**
     * 是否为图片 / 表格 / 公式等多模态块（多模态 7-Stage 管线据此分流）。
     *
     * @return true 表示块需要走多模态描述与模板化处理
     */
    public boolean isMultimodal() {
        return TYPE_IMAGE.equals(type) || TYPE_TABLE.equals(type) || TYPE_EQUATION.equals(type);
    }
}