package com.linkroa.deepdataagent.agent.domain.model;

import org.apache.commons.lang3.StringUtils;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 技能绑定值对象（agent BC 领域持有，{@code agent_version.skills_json} 的解析结果，Skill binding 契约）。
 * <p>绑定形如 {@code {type, skill_id, version?}}：{@code type} 取 {@code catalog}（平台目录引用）/
 * {@code custom}（本系统自建技能资产）；{@code skill_id} 前缀 {@code skill_}；{@code version} 为非空
 * epoch 微秒字符串时<b>钉版</b>（沙箱准备期固定取该版本），省略或为 {@code "latest"} 时为<b>动态版</b>
 * （沙箱准备期解析当时最新版本）。技能资产归属 skill BC，agent BC 仅以 {@code skill_id} 字符串弱关联，
 * 不引入 skill BC 领域类型（守领域层零跨 BC 耦合）。</p>
 *
 * @param type    绑定类型（{@code catalog} / {@code custom}）
 * @param skillId 技能业务 ID（前缀 {@code skill_}）
 * @param version 引用的技能版本键（{@code null} = 动态版；非空为创建时刻 epoch 微秒字符串）
 */
public record SkillBinding(String type, String skillId, String version) {

    /** 技能目录引用类型词汇。 */
    public static final String TYPE_CATALOG = "catalog";
    /** 本系统自建技能类型词汇。 */
    public static final String TYPE_CUSTOM = "custom";
    /** 技能业务 ID 前缀。 */
    public static final String SKILL_ID_PREFIX = "skill_";
    /** 单版本技能绑定数量上限。 */
    public static final int MAX_BINDINGS = 20;
    /** 动态版版本词汇（等同省略 version）。 */
    public static final String VERSION_LATEST = "latest";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * 紧凑构造器：绑定不变量校验（类型枚举词汇、skill_id 前缀、版本键非空字符串）。
     *
     * @throws IllegalArgumentException 类型非法 / skill_id 为空或前缀非法 / 版本键为空白字符串
     */
    public SkillBinding {
        if (!TYPE_CATALOG.equals(type) && !TYPE_CUSTOM.equals(type)) {
            throw new IllegalArgumentException("技能绑定类型非法，须为 catalog/custom");
        }
        if (StringUtils.isBlank(skillId) || !skillId.startsWith(SKILL_ID_PREFIX)) {
            throw new IllegalArgumentException("技能绑定 skill_id 非法，须以 skill_ 前缀");
        }
        if (version != null && StringUtils.isBlank(version)) {
            throw new IllegalArgumentException("技能绑定 version 须为非空 epoch 字符串或省略");
        }
    }

    /**
     * 是否为本系统自建技能（需解析本地 skill 资产内容）。
     *
     * @return {@code type == custom} 返回 {@code true}
     */
    public boolean isCustom() {
        return TYPE_CUSTOM.equals(type);
    }

    /**
     * 是否动态版绑定（version 省略或为 {@code "latest"}，沙箱准备期解析最新版本）。
     */
    public boolean isDynamicVersion() {
        return version == null;
    }

    /**
     * 派生钉版后的绑定（将动态版锁定为具体 epoch 版本键）。
     *
     * @param pinnedVersion 解析后的版本键
     * @return 版本键固定后的绑定快照
     */
    public SkillBinding withVersion(String pinnedVersion) {
        return new SkillBinding(type, skillId, pinnedVersion);
    }

    /**
     * 解析技能配方 JSON 数组为绑定列表（{@code [{type, skill_id, version}]}，空白配方返回空列表）。
     * <p>{@code version} 省略或等于 {@code "latest"}（忽略大小写）归一为动态版（{@code null}）。</p>
     *
     * @param skillsJson 技能配方 JSON（可空）
     * @return 绑定列表（保持声明顺序）
     * @throws IllegalStateException JSON 结构非法（无法解析为对象数组）
     */
    public static List<SkillBinding> parse(String skillsJson) {
        if (StringUtils.isBlank(skillsJson)) {
            return List.of();
        }
        List<Map<String, Object>> items;
        try {
            items = OBJECT_MAPPER.readValue(skillsJson, new TypeReference<>() {
            });
        } catch (RuntimeException e) {
            throw new IllegalStateException("技能引用JSON解析失败", e);
        }
        List<SkillBinding> bindings = new ArrayList<>();
        for (Map<String, Object> item : items) {
            if (item == null) {
                throw new IllegalStateException("技能引用格式非法：存在空对象");
            }
            bindings.add(new SkillBinding(
                    String.valueOf(item.get("type")),
                    String.valueOf(item.get("skill_id")),
                    normalizeVersion(item.get("version"))));
        }
        return bindings;
    }

    /**
     * 序列化绑定列表为技能配方 JSON（动态版不落 version 键；空列表返回 {@code null}）。
     *
     * @param bindings 绑定列表
     * @return 配方 JSON 字符串；绑定为空返回 {@code null}
     */
    public static String toJson(List<SkillBinding> bindings) {
        if (bindings == null || bindings.isEmpty()) {
            return null;
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (SkillBinding binding : bindings) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("type", binding.type());
            item.put("skill_id", binding.skillId());
            if (binding.version() != null) {
                item.put("version", binding.version());
            }
            items.add(item);
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(items);
        } catch (RuntimeException e) {
            throw new IllegalStateException("技能绑定序列化失败", e);
        }
    }

    /**
     * 校验绑定数量不超过上限（超出抛非法参数）。
     *
     * @param bindings 绑定列表
     * @throws IllegalArgumentException 数量超过 {@link #MAX_BINDINGS}
     */
    public static void validateCount(List<SkillBinding> bindings) {
        if (bindings != null && bindings.size() > MAX_BINDINGS) {
            throw new IllegalArgumentException("技能绑定数量不能超过" + MAX_BINDINGS + "个");
        }
    }

    /** 版本键归一：省略 / {@code "latest"}（忽略大小写）→ 动态版；其余取非空字符串。 */
    private static String normalizeVersion(Object rawVersion) {
        if (rawVersion == null) {
            return null;
        }
        String text = String.valueOf(rawVersion).trim();
        if (text.isEmpty() || VERSION_LATEST.equalsIgnoreCase(text)) {
            return null;
        }
        return text;
    }
}