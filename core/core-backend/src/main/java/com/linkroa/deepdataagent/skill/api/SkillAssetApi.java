package com.linkroa.deepdataagent.skill.api;

import com.linkroa.deepdataagent.skill.api.dto.SkillContentDTO;

/**
 * 技能资产只读服务契约（跨 BC 服务边界，未来 Feign 落点）。
 * <p>供 agent BC 在版本装配时按 {@code skill_id} + 版本键读取技能内容
 * （SKILL.md 正文 + 资源映射），出版发布语言 {@link SkillContentDTO}，不携带敏感 / 重载荷。
 * 由 {@code DefaultSkillAssetApi} 进程内实现。</p>
 * <p>owner 隔离：装配可发生在异步 / 调度链路（无 ThreadLocal {@code AuthContext}），
 * 故契约显式携带归属用户 {@code ownerId}，任一归属不匹配视为不存在（不泄露存在性）。</p>
 */
public interface SkillAssetApi {

    /**
     * 判断指定技能资产是否存在且属于该 owner（custom 绑定校验用）。
     *
     * @param skillId 技能业务 ID
     * @param ownerId 归属用户 ID
     * @return 存在且归属匹配返回 {@code true}
     */
    boolean exists(String skillId, Long ownerId);

    /**
     * 解析技能最新版本键。
     *
     * @param skillId 技能业务 ID
     * @param ownerId 归属用户 ID（不匹配视为不存在）
     * @return 最新版本键（epoch 微秒字符串）；技能不存在 / 已无版本返回 {@code null}
     */
    String latestVersion(String skillId, Long ownerId);

    /**
     * 判断指定技能的某版本是否存在（绑定显式钉版引用完整性校验）。
     *
     * @param skillId 技能业务 ID
     * @param version 版本键（epoch 微秒字符串）
     * @param ownerId 归属用户 ID（不匹配视为不存在）
     * @return 技能归属匹配且该版本存在返回 {@code true}
     */
    boolean versionExists(String skillId, String version, Long ownerId);

    /**
     * 读取技能指定版本的内容（装配用）。
     *
     * @param skillId 技能业务 ID
     * @param version 版本键（{@code null} 表示解析当时最新版本，即动态版绑定）
     * @param ownerId 归属用户 ID（不匹配视为不存在）
     * @return 内容装配契约；技能 / 版本 / 磁盘内容任一缺失返回 {@code null}
     */
    SkillContentDTO findContent(String skillId, String version, Long ownerId);
}