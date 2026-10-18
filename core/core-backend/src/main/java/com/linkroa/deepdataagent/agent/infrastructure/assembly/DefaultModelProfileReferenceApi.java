package com.linkroa.deepdataagent.agent.infrastructure.assembly;

import com.linkroa.deepdataagent.agent.api.ModelProfileReferenceApi;
import com.linkroa.deepdataagent.agent.domain.repository.ModelProfileRepository;
import jakarta.annotation.Resource;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

/**
 * 模型配置归属查询契约实现（{@link ModelProfileReferenceApi}）：供跨 BC 写入期归属校验消费。
 * <p>仓储按业务 ID 查询不携带 owner 条件，owner 隔离在本实现内匹配判定；
 * 不存在 / 非本人 owner 统一返回 {@code false}（404 语义由消费方映射，不泄露存在性）。</p>
 */
@Component
public class DefaultModelProfileReferenceApi implements ModelProfileReferenceApi {

    @Resource
    private ModelProfileRepository modelProfileRepository;

    @Override
    public boolean existsOwned(String profileId, Long ownerId) {
        if (StringUtils.isBlank(profileId) || ObjectUtils.isEmpty(ownerId)) {
            return false;
        }
        return modelProfileRepository.findByProfileId(profileId)
                .filter(profile -> ownerId.equals(profile.ownerId()))
                .isPresent();
    }
}