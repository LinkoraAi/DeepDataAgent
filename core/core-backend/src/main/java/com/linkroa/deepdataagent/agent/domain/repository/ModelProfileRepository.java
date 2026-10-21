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
     * 分页查询（按 owner 隔离；类型码列表为空表示不限类型）
     *
     * @param ownerId    归属用户 ID
     * @param keyword    名称关键字（可空）
     * @param status     状态过滤（可空）
     * @param modelTypes 类型业务码列表（可空/空表示不限；多值并集）
     * @param page       页码（1 起）
     * @param size       每页条数
     * @return 命中的模型配置
     */
    List<ModelProfile> findByCondition(Long ownerId, String keyword, ModelProfileStatus status,
                                      List<Integer> modelTypes, int page, int size);

    /**
     * 分页统计（按 owner 隔离；类型码列表为空表示不限类型）
     *
     * @param ownerId    归属用户 ID
     * @param keyword    名称关键字（可空）
     * @param status     状态过滤（可空）
     * @param modelTypes 类型业务码列表（可空/空表示不限；多值并集）
     * @return 命中总数
     */
    long countByCondition(Long ownerId, String keyword, ModelProfileStatus status, List<Integer> modelTypes);

    /**
     * 更新状态（启用/禁用）
     */
    void updateStatus(String profileId, ModelProfileStatus status);

    /**
     * 逻辑删除
     */
    void deleteByProfileId(String profileId);
}