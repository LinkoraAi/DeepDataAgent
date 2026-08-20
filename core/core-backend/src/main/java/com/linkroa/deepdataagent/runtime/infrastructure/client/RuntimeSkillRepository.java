package com.linkroa.deepdataagent.runtime.infrastructure.client;

import com.linkroa.deepdataagent.runtime.domain.model.Skill;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.skill.repository.AgentSkillRepositoryInfo;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 运行时技能仓储（{@link AgentSkillRepository} 实现）：内存态、只读。
 * <p>由装配规格中已物化的 {@link Skill} 构建为 AgentScope {@link AgentSkill}，
 * 供 {@code HarnessAgent.Builder#skillRepository(...)} 挂载。技能名以 {@link Skill#name()}
 * 为键，与 SKILL.md frontmatter 的 name 对齐。</p>
 */
public class RuntimeSkillRepository implements AgentSkillRepository {

    private static final String SOURCE = "runtime";

    private final Map<String, AgentSkill> skills;

    public RuntimeSkillRepository(List<Skill> skills) {
        this.skills = new LinkedHashMap<>();
        if (skills != null) {
            for (Skill skill : skills) {
                this.skills.put(skill.name(), new AgentSkill(
                        skill.name(),
                        skill.description() == null ? "" : skill.description(),
                        skill.skillContent(),
                        skill.resources()
                ));
            }
        }
    }

    @Override
    public AgentSkill getSkill(String name) {
        return skills.get(name);
    }

    @Override
    public List<String> getAllSkillNames() {
        return List.copyOf(skills.keySet());
    }

    @Override
    public List<AgentSkill> getAllSkills() {
        return List.copyOf(skills.values());
    }

    @Override
    public boolean save(List<AgentSkill> skills, boolean overwrite) {
        return false;
    }

    @Override
    public boolean delete(String name) {
        return false;
    }

    @Override
    public boolean skillExists(String name) {
        return skills.containsKey(name);
    }

    @Override
    public AgentSkillRepositoryInfo getRepositoryInfo() {
        return new AgentSkillRepositoryInfo("memory", SOURCE, false);
    }

    @Override
    public String getSource() {
        return SOURCE;
    }

    @Override
    public void setWriteable(boolean writable) {
        // 只读仓储，忽略写使能。
    }

    @Override
    public boolean isWriteable() {
        return false;
    }
}