package com.linkroa.deepdataagent.agent.domain.repository;

import com.linkroa.deepdataagent.agent.domain.model.ModelProfile;
import com.linkroa.deepdataagent.agent.domain.model.enums.ModelProfileStatus;

import java.util.List;
import java.util.Optional;

/**
 * 模型配置仓储接口
 */
public interface ModelProfileRepository {

    /**
     * 保存模型配置（新增）
     */
    ModelProfile save(ModelProfile profile);

    /**
     * 更新模型配置（全量替换）
     */
    ModelProfile update(ModelProfile profile);

    /**
     * 按业务ID查询
     */
    Optional<ModelProfile> findByProfileId(String profileId);

    /**
     * 按业务ID查询并锁定该行（FOR UPDATE），用于删除等 check-then-act 场景的事务内串行化
     */
    Optional<ModelProfile> findByProfileIdForUpdate(String profileId);

    /**
     * 按显示名称查询（唯一）
     */
    Optional<ModelProfile> findByDisplayName(String displayName);

    /**
     * 分页查询
     */
    List<ModelProfile> findByCondition(String keyword, ModelProfileStatus status, int page, int size);

    /**
     * 分页统计
     */
    long countByCondition(String keyword, ModelProfileStatus status);

    /**
     * 更新状态（启用/禁用）
     */
    void updateStatus(String profileId, ModelProfileStatus status);

    /**
     * 逻辑删除
     */
    void deleteByProfileId(String profileId);
}