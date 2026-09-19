package com.linkroa.deepdataagent.runtime.application.convert;

import com.linkroa.deepdataagent.agent.application.dto.ResolvedSkillDTO;
import com.linkroa.deepdataagent.runtime.domain.model.Skill;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * {@link SkillAssemblyConvert} 防腐映射单测：agent BC 发布语言 DTO → 本 BC
 * 技能值对象（fix-runtime-layering 4.3，原 SkillPackageMaterializer 映射用例平移）。
 * <p>批量入口 {@code materialize} 的「缺失 / 损坏条目跳过、不阻断装配」吞咽语义用例自原端口
 * 适配器测试平移（迭代裁决 2026-09-13：端口 + 适配器删除，规则只留防腐层一份）。</p>
 */
class SkillAssemblyConvertTest {

    @Test
    void should_mapSkillFields_when_toSkill_given_dto() {
        // given（发布语言 DTO 已含解析后的 name / description / 指令正文）
        ResolvedSkillDTO dto = new ResolvedSkillDTO("s-1", "code-reviewer", "代码评审技能", "指令正文", Map.of());

        // when
        Skill skill = SkillAssemblyConvert.INSTANCE.toSkill(dto);

        // then（dirName 不入领域模型）
        assertEquals("code-reviewer", skill.name());
        assertEquals("代码评审技能", skill.description());
        assertEquals("指令正文", skill.skillContent());
    }

    @Test
    void should_mapResources_when_toSkill_given_resources() {
        // given（结构化资源按相对路径物化到 resources）
        ResolvedSkillDTO dto = new ResolvedSkillDTO("s-1", "code-reviewer", "代码评审技能", "指令正文",
                Map.of("references/api.md", "参考文档", "scripts/run.py", "print('ok')"));

        // when
        Skill skill = SkillAssemblyConvert.INSTANCE.toSkill(dto);

        // then
        assertEquals("参考文档", skill.resources().get("references/api.md"));
        assertEquals("print('ok')", skill.resources().get("scripts/run.py"));
    }

    @Test
    void should_materializeAllValidSkills_when_materialize_given_validList() {
        // given（两个合法技能 DTO）
        ResolvedSkillDTO first = new ResolvedSkillDTO("s-1", "code-reviewer", "代码评审技能",
                "指令正文", Map.of());
        ResolvedSkillDTO second = new ResolvedSkillDTO("s-2", "sql-tuner", null,
                "调优正文", Map.of("references/tips.md", "参考"));

        // when
        List<Skill> skills = SkillAssemblyConvert.INSTANCE.materialize(List.of(first, second));

        // then（保序全量物化，description 可空）
        assertEquals(2, skills.size());
        assertEquals("code-reviewer", skills.get(0).name());
        assertEquals("指令正文", skills.get(0).skillContent());
        assertEquals("sql-tuner", skills.get(1).name());
        assertEquals("参考", skills.get(1).resources().get("references/tips.md"));
    }

    @Test
    void should_returnEmptySkills_when_materialize_given_nullList() {
        // given / when / then
        assertTrue(SkillAssemblyConvert.INSTANCE.materialize(null).isEmpty());
    }

    @Test
    void should_skipBrokenSkill_when_materialize_given_dtoMappingThrows() {
        // given（损坏条目：全部访问器返回 null → 领域不变量抛 IllegalArgumentException）
        ResolvedSkillDTO broken = mock(ResolvedSkillDTO.class);
        ResolvedSkillDTO valid = new ResolvedSkillDTO("s-1", "code-reviewer", "描述", "正文", Map.of());

        // when（缺失 / 损坏技能跳过、不阻断其余装配）
        List<Skill> skills = assertDoesNotThrow(
                () -> SkillAssemblyConvert.INSTANCE.materialize(List.of(broken, valid)));

        // then
        assertEquals(1, skills.size());
        assertEquals("code-reviewer", skills.get(0).name());
    }

    @Test
    void should_skipNullEntry_when_materialize_given_listWithNullElement() {
        // given
        List<ResolvedSkillDTO> dtos = Collections.singletonList(null);

        // when
        List<Skill> skills = assertDoesNotThrow(() -> SkillAssemblyConvert.INSTANCE.materialize(dtos));

        // then（null 条目跳过，空技能不得混入装配列表）
        assertTrue(skills.isEmpty());
    }
}
