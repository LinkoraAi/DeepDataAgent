package com.linkroa.deepdataagent.skill.domain.repository;

import com.linkroa.deepdataagent.skill.domain.model.SkillAsset;
import com.linkroa.deepdataagent.skill.domain.model.SkillListFilter;
import com.linkroa.deepdataagent.skill.domain.model.SkillVersion;

import java.util.List;
import java.util.Optional;

/**
 * 技能资产仓储接口（依赖倒置：领域声明语义，基础设施 {@code JdbcSkillAssetRepository} 实现）。
 * <p>同时管理 {@code skill}（聚合根）与 {@code skill_content_version}（内容版本）两张表；
 * 版本内容为不可变快照，壳对象的 {@code latest_version} 指针由本仓储随写入维护。</p>
 */
public interface SkillAssetRepository {

    /**
     * 保存新技能壳（壳行 + 首个内容版本行原子落库，壳的 latest_version 置为该版本键）。
     *
     * @param asset   技能壳
     * @param version 首个内容版本元数据
     * @return 落库后的技能壳
     */
    SkillAsset save(SkillAsset asset, SkillVersion version);

    /**
     * 追加内容版本（落新版本行 + 推进壳的 latest_version 指针）。
     *
     * @param asset   最新版本指针已更新的技能壳
     * @param version 新内容版本元数据
     * @return 更新后的技能壳
     */
    SkillAsset appendVersion(SkillAsset asset, SkillVersion version);

    /**
     * 更新壳的 latest_version 指针（版本删除后重算；不产生新版本行）。
     *
     * @param asset 指针更新后的技能壳
     * @return 更新后的技能壳
     */
    SkillAsset updateLatestVersion(SkillAsset asset);

    /**
     * 按技能业务 ID 查询未删除壳。
     *
     * @param skillId 技能业务 ID
     * @return 技能壳（不存在返回空）
     */
    Optional<SkillAsset> findBySkillId(String skillId);

    /**
     * 游标分页查询某归属用户的未删除技能壳（创建时间降序 keyset）。
     *
     * @param ownerId 归属用户 ID
     * @param filter  游标查询条件（来源 / 展示名模糊 / 游标行位点 / 方向）
     * @param limit   单页上限（调用方传 limit+1 探针）
     * @return 技能壳列表（正向降序；before 方向升序读取后由应用层翻转）
     */
    List<SkillAsset> findByCursor(Long ownerId, SkillListFilter filter, int limit);

    /**
     * 逻辑删除技能壳（历史版本数据保留）。
     *
     * @param skillId 技能业务 ID
     */
    void deleteBySkillId(String skillId);

    /**
     * 查询某技能的全部未删除内容版本（版本键倒序，最新在前）。
     *
     * @param skillId 技能业务 ID
     * @return 内容版本列表
     */
    List<SkillVersion> listVersions(String skillId);

    /**
     * 查询某技能指定版本键的内容元数据。
     *
     * @param skillId 技能业务 ID
     * @param version 版本键（epoch 微秒字符串）
     * @return 内容版本（不存在返回空）
     */
    Optional<SkillVersion> findVersion(String skillId, String version);

    /**
     * 查询某技能当前的未删除最新内容版本（版本键倒序首行；无版本返回空）。
     * <p>动态版技能绑定在沙箱准备期按此解析当时最新版本。</p>
     *
     * @param skillId 技能业务 ID
     * @return 最新内容版本（不存在返回空）
     */
    Optional<SkillVersion> findLatestVersion(String skillId);

    /**
     * 查询某技能最早的未删除内容版本（版本键升序首行；无版本返回空）。
     * <p>发版时以首版 {@code name} 校验跨版本一致。</p>
     *
     * @param skillId 技能业务 ID
     * @return 最早内容版本（不存在返回空）
     */
    Optional<SkillVersion> findFirstVersion(String skillId);

    /**
     * 逻辑删除某技能的指定内容版本（磁盘内容保留）。
     *
     * @param skillId 技能业务 ID
     * @param version 版本键
     */
    void deleteVersion(String skillId, String version);
}