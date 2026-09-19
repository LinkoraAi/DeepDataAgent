package com.linkroa.deepdataagent.agent.api;

import com.linkroa.deepdataagent.agent.api.dto.EnvironmentReferenceDTO;

/**
 * 运行环境查询服务契约（跨 BC 服务边界，未来 Feign 落点）。
 * <p>暴露会话创建挂载校验所需的轻量类型解析能力（存在性 + owner 隔离判定），
 * 与会话装配所需的完整环境引用（规范化类型 + 初始化脚本，不含任何敏感材料）；
 * 当前由 {@code DefaultEnvironmentApi} 进程内实现，未来接入 Feign 时仅需在本接口
 * 追加 {@code @FeignClient} 注解，消费方无需改动。</p>
 */
public interface EnvironmentApi {

    /**
     * 解析指定运行环境的类型（存在性 + owner 隔离轻量校验）。
     * <p>环境不存在、已删除或 owner 不匹配统一返回 {@code null}
     * （消费方据此映射 404，不泄露存在性）。</p>
     *
     * @param ownerId       所属用户 ID（调用方显式传入，跨 BC / 异步链路不得回退线程上下文）
     * @param environmentId 运行环境业务 ID
     * @return 环境类型规范化小写值（当前取值 {@code cloud / self_hosted}）；
     *         null = 不存在或非本人 owner
     */
    String resolveType(Long ownerId, String environmentId);

    /**
     * 解析指定运行环境的完整装配引用（会话装配专用）。
     * <p>返回已格式化的 {@link EnvironmentReferenceDTO}（规范化类型 + 初始化脚本），
     * 不携带凭证等敏感材料；环境不存在、已删除或 owner 不匹配统一返回 {@code null}
     * （消费方据此映射 404，不泄露存在性，环境选择 MUST NOT 回退）。</p>
     *
     * @param ownerId       所属用户 ID（调用方显式传入，跨 BC / 异步链路不得回退线程上下文）
     * @param environmentId 运行环境业务 ID
     * @return 环境装配引用契约；null = 不存在或非本人 owner
     */
    EnvironmentReferenceDTO resolveReference(Long ownerId, String environmentId);
}
