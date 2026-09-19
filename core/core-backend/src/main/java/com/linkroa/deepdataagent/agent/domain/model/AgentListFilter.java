package com.linkroa.deepdataagent.agent.domain.model;

import java.time.OffsetDateTime;

/**
 * Agent 列表游标查询条件（shared/api-conventions Cursor 约定，6.2 管理面）。
 * <p>排序为 {@code created_at DESC, agent_id DESC}（创建时间降序、业务 ID 稳定次键）；
 * keyset 游标位置（{@code cursorCreatedAt} + {@code cursorAgentId}）由应用层将
 * after_id/before_id 解析为行位点后装配，两者必须成对出现。</p>
 *
 * @param keyword         名称模糊匹配（可空 = 不过滤）
 * @param archived        归档状态过滤：null=不限、false=仅未归档、true=仅已归档
 * @param metadataJson    元数据键值包含过滤 JSON 文本（可空 = 不过滤；按激活版本快照的 metadata 匹配）
 * @param createdFrom     创建时间下界（含，可空）
 * @param createdTo       创建时间上界（含，可空）
 * @param cursorCreatedAt 游标行创建时间（可空 = 首页）
 * @param cursorAgentId   游标行业务 ID（次排序键定位；与游标时间成对）
 * @param reverse         true=before_id 方向（取更新侧、升序读取后由应用层翻转回降序）
 */
public record AgentListFilter(
        String keyword,
        Boolean archived,
        String metadataJson,
        OffsetDateTime createdFrom,
        OffsetDateTime createdTo,
        OffsetDateTime cursorCreatedAt,
        String cursorAgentId,
        boolean reverse
) {

    public AgentListFilter {
        if ((cursorCreatedAt == null) != (cursorAgentId == null)) {
            throw new IllegalArgumentException("游标位置必须成对提供（创建时间与业务ID）");
        }
    }
}
