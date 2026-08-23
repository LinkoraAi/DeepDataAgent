package com.linkroa.deepdataagent.agent.api;

/**
 * Agent 对外引用计数服务契约（跨 BC 服务边界，未来 Feign 落点）。
 * <p>反向引用数据（agent_version.memory_store_ids / model_profile.secret_id）归属 agent BC，
 * 删除被引用对象前需反向感知「是否仍被未删除的 Agent 版本 / 模型配置引用」。本接口由
 * agent BC 提供正向查询能力，memory / vault 作为消费方依赖本接口调用，方向统一为
 * 「消费方依赖服务契约、提供方实现」的 Feign 友好模型。当前由 {@code DefaultAgentReferenceApi}
 * 进程内实现，未来接入 Feign 时仅需在本接口追加 {@code @FeignClient} 注解。</p>
 */
public interface AgentReferenceApi {

    /**
     * 统计仍引用指定记忆库的未删除 Agent 版本数。
     *
     * @param memoryStoreId 记忆库业务 ID
     * @return 引用数（0 表示无引用，可安全删除）
     */
    long countMemoryStoreReferences(String memoryStoreId);

    /**
     * 统计仍经 secret_id 引用指定密钥的未删除模型配置数。
     *
     * @param secretId 密钥业务 ID
     * @return 引用数（0 表示无引用，可安全删除）
     */
    long countSecretReferences(String secretId);
}