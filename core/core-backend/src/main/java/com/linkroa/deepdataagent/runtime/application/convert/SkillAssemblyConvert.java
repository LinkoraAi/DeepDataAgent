package com.linkroa.deepdataagent.runtime.application.convert;

import com.linkroa.deepdataagent.agent.application.dto.ResolvedSkillDTO;
import com.linkroa.deepdataagent.runtime.domain.model.Skill;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;
import org.mapstruct.factory.Mappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 技能装配防腐转换器（MapStruct 静态单例，fix-runtime-layering 4.1/4.3）：
 * agent BC 发布语言 {@link ResolvedSkillDTO} → 本 BC 领域值对象 {@link Skill}。
 * <p>原 {@code SkillPackageMaterializer}（infrastructure.client）对他 BC DTO 的
 * 形状映射迁入本防腐层；name / description / skillContent / resources 四字段
 * 同名自动映射，{@code dirName}（agent BC 目录名）不入本 BC 领域模型，静默丢弃。
 * 不变量（名称非空、正文非空）由 {@link Skill} 紧凑构造器校验。</p>
 * <p>批量物化的「缺失 / 损坏条目跳过 + WARN」吞咽语义由 {@link #materialize} 承载
 * （迭代裁决 2026-09-13：原技能物化端口 + 适配器只是转调本接口、零基础设施依赖，
 * 故删除该端口包装，映射规则仍只有一份）。</p>
 */
@Mapper(unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface SkillAssemblyConvert {

    SkillAssemblyConvert INSTANCE = Mappers.getMapper(SkillAssemblyConvert.class);

    /** 转换接口自带日志器（接口字段隐式 public static final，用于损坏条目的 WARN 记录）。 */
    Logger log = LoggerFactory.getLogger(SkillAssemblyConvert.class);

    /**
     * 单个技能物化：工作区文件技能文本直接包裹为 {@link Skill}。
     */
    Skill toSkill(ResolvedSkillDTO dto);

    /**
     * 批量物化：缺失 / 损坏技能跳过（不阻断装配），其余正常物化。
     *
     * @param dtos 他 BC 装配契约中的技能列表（可空 / 可含非法条目）
     * @return 物化成功的技能值对象列表（永不为 {@code null}）
     */
    default List<Skill> materialize(List<ResolvedSkillDTO> dtos) {
        List<Skill> skills = new ArrayList<>();
        if (dtos == null) {
            return skills;
        }
        for (ResolvedSkillDTO dto : dtos) {
            if (dto == null) {
                // 对 null 入参映射结果为 null，显式跳过防止空技能混入装配列表
                log.warn("技能材料缺失或损坏，跳过物化: name=null");
                continue;
            }
            try {
                skills.add(toSkill(dto));
            } catch (RuntimeException e) {
                log.warn("技能材料缺失或损坏，跳过物化: name={}", dto.name(), e);
            }
        }
        return skills;
    }
}
