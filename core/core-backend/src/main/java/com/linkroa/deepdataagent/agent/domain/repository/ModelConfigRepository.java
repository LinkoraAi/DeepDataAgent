package com.linkroa.deepdataagent.agent.domain.repository;

import com.linkroa.deepdataagent.agent.domain.model.ModelConfig;

import java.util.List;
import java.util.Optional;

/**
 * 模型配置仓储接口
 * <p>定义在 domain 层，实现在 infrastructure 层。</p>
 */
public interface ModelConfigRepository {

    Optional<ModelConfig> findById(Long id);

    List<ModelConfig> findAllEnabled();

    Optional<ModelConfig> findDefault();

    List<ModelConfig> findByProviderName(String providerName);

    List<ModelConfig> findProviders();

    ModelConfig save(ModelConfig info);

    void update(ModelConfig info);

    void markDeleted(Long id);
}