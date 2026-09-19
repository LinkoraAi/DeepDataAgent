package com.linkroa.deepdataagent.agent.infrastructure.assembly;

import com.linkroa.deepdataagent.agent.api.EnvironmentApi;
import com.linkroa.deepdataagent.agent.api.dto.EnvironmentReferenceDTO;
import com.linkroa.deepdataagent.agent.domain.model.Environment;
import com.linkroa.deepdataagent.agent.domain.repository.EnvironmentRepository;
import jakarta.annotation.Resource;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

/**
 * 运行环境查询服务契约实现（{@link EnvironmentApi}）：解析环境类型 / 装配引用供跨 BC 消费。
 * <p>仓储按业务 ID 查询不携带 owner 条件，owner 隔离在本实现内匹配判定；
 * 不存在 / 非本人 owner / <b>已归档</b>统一返回 null（404 语义由消费方映射，
 * 不泄露存在性，已归档环境不可被新 Session 引用）。</p>
 */
@Component
public class DefaultEnvironmentApi implements EnvironmentApi {

    @Resource
    private EnvironmentRepository environmentRepository;

    @Override
    public String resolveType(Long ownerId, String environmentId) {
        if (ownerId == null || StringUtils.isBlank(environmentId)) {
            return null;
        }
        return environmentRepository.findByEnvironmentId(environmentId)
                .filter(environment -> ownerId.equals(environment.ownerId()))
                .filter(environment -> !environment.isArchived())
                .map(environment -> environment.config().type().value())
                .orElse(null);
    }

    @Override
    public EnvironmentReferenceDTO resolveReference(Long ownerId, String environmentId) {
        if (ownerId == null || StringUtils.isBlank(environmentId)) {
            return null;
        }
        return environmentRepository.findByEnvironmentId(environmentId)
                .filter(environment -> ownerId.equals(environment.ownerId()))
                .filter(environment -> !environment.isArchived())
                .map(DefaultEnvironmentApi::toReference)
                .orElse(null);
    }

    /** 领域环境 → 装配引用契约（类型输出规范化小写值，不泄露领域枚举与配置值对象）。 */
    private static EnvironmentReferenceDTO toReference(Environment environment) {
        return new EnvironmentReferenceDTO(
                environment.environmentId(),
                environment.name(),
                environment.config().type().value(),
                environment.config().setupScript()
        );
    }
}
