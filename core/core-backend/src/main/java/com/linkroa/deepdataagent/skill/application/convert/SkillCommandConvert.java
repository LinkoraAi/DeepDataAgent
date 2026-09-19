package com.linkroa.deepdataagent.skill.application.convert;

import com.linkroa.deepdataagent.shared.result.CursorPageParams;
import com.linkroa.deepdataagent.skill.application.query.ListSkillsQuery;
import com.linkroa.deepdataagent.skill.domain.model.enums.SkillSource;
import org.apache.commons.lang3.StringUtils;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;
import org.mapstruct.factory.Mappers;

/**
 * 技能协议层入参 → 应用层命令 / 查询转换器（MapStruct 静态单例）。
 * <p>创建与发版走 multipart 包上传（无请求正文 DTO），故仅保留列表查询装配：来源小写词汇解析
 * （空白 = 不过滤，非法 → 400）与游标参数统一归一（limit 缺省 20、after/before 互斥）。</p>
 */
@Mapper(unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface SkillCommandConvert {

    SkillCommandConvert INSTANCE = Mappers.getMapper(SkillCommandConvert.class);

    /**
     * 装配技能列表游标查询。
     *
     * @param ownerId 归属用户 ID
     * @param source  来源小写词汇（可空 = 不过滤）
     * @param keyword 展示名模糊关键字（可空）
     * @param limit   单页上限原始值（可空 = 缺省）
     * @param afterId after 游标（可空）
     * @param beforeId before 游标（可空）
     * @return 列表查询对象
     * @throws IllegalArgumentException 来源词汇非法 / 游标参数非法
     */
    default ListSkillsQuery toListQuery(Long ownerId, String source, String keyword,
                                        String limit, String afterId, String beforeId) {
        SkillSource skillSource = StringUtils.isBlank(source) ? null : SkillSource.fromValue(source);
        return new ListSkillsQuery(ownerId, skillSource, keyword,
                CursorPageParams.parse(limit, afterId, beforeId));
    }
}