package com.linkroa.deepdataagent.rag.domain.model;

import org.apache.commons.lang3.StringUtils;

/**
 * 多模态媒体块描述值对象（Stage1 产出，仅内存不落库）。
 * <p>对应 vision/table/equation/generic 分析 Prompt 的输出 schema
 * （{@code detailed_description} + {@code entity_info.{entity_name, entity_type, summary}}），
 * {@link #enhancedCaption()} 供 Stage2 chunk 模板的 {@code enhanced_caption} 占位符填充，
 * {@link #primaryEntityDescription()} 供摄入管线构造主实体节点描述。</p>
 *
 * @param entityName         媒体主实体名（描述服务解析处一次定形：裸名 + 内容类型后缀；
 *                           LLM 解析失败时裸名为确定性代码兜底值、后缀仍拼接）
 * @param entityType         媒体主实体类型（固定小写：image / table / equation / generic，
 *                           不采信模型自报的实体类型字段）
 * @param summary            一句话摘要（可为空串；主实体节点描述的取值来源）
 * @param detailedDescription 详描正文（可为空串；留在 chunk 正文，不写入主实体描述）
 * @param llmGenerated       描述是否由 LLM 生成（false 表示走确定性兜底，供摄入质量统计）
 * @author DeepDataAgent
 */
public record MediaDescriptionVO(
        String entityName,
        String entityType,
        String summary,
        String detailedDescription,
        boolean llmGenerated
) {

    /**
     * 紧凑构造器：主实体名/类型非空校验，文本字段空白归一为空串。
     */
    public MediaDescriptionVO {
        if (StringUtils.isBlank(entityName)) {
            throw new IllegalArgumentException("媒体主实体名不能为空");
        }
        if (StringUtils.isBlank(entityType)) {
            throw new IllegalArgumentException("媒体主实体类型不能为空");
        }
        summary = StringUtils.defaultString(summary);
        detailedDescription = StringUtils.defaultString(detailedDescription);
    }

    /**
     * 增强描述文本（chunk 模板 {@code enhanced_caption} 占位符取值）：
     * 详描优先，详描为空退摘要，均为空返回空串（由模板装配方按缺失占位符归一）。
     *
     * @return 增强描述文本
     */
    public String enhancedCaption() {
        String detail = StringUtils.trimToEmpty(detailedDescription);
        if (StringUtils.isNotEmpty(detail)) {
            return detail;
        }
        return StringUtils.trimToEmpty(summary);
    }

    /**
     * 主实体节点描述取值（图谱上的主实体是轻量的）：一行摘要优先，摘要缺失回落详描文本；
     * 二者皆空返回空串，由实体模型的确定性兜底（{@code Entity {name}}）收口。
     * 完整详描留在块正文，经归属关系边溯源回块，MUST NOT 写入主实体描述。
     *
     * @return 主实体描述文本（可为空串）
     */
    public String primaryEntityDescription() {
        String oneLine = StringUtils.trimToEmpty(summary);
        if (StringUtils.isNotEmpty(oneLine)) {
            return oneLine;
        }
        return StringUtils.trimToEmpty(detailedDescription);
    }
}
