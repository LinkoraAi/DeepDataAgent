package com.linkroa.deepdataagent.runtime.api;

/**
 * 会话引用查询服务契约（跨 BC 服务边界，未来 Feign 落点）。
 * <p>agent BC 删除运行环境前经本接口校验是否仍有会话挂载该环境（引用则 409），
 * 实现「删除守卫依据实时会话引用而非版本静态配置」。当前由
 * {@code DefaultSessionReferenceApi} 进程内实现，未来接入 Feign 时仅需在本接口
 * 追加 {@code @FeignClient} 注解并移除进程内实现，消费方（agent BC）无需改动。</p>
 */
public interface SessionReferenceApi {

    /**
     * 统计仍引用指定执行环境的未删除会话数。
     *
     * @param environmentId 环境业务 ID
     * @return 引用数（0 表示无会话挂载，可安全删除）
     */
    long countSessionsByEnvironmentId(String environmentId);

    /**
     * 统计仍挂载指定记忆库资源（memory_store）的未删除会话数。
     * <p>memory BC 删除记忆库前经本方法校验引用：仍被任一未删除会话挂载的记忆库
     * 不得删除（409）。</p>
     *
     * @param storeId 记忆库业务 ID（ms_ 前缀）
     * @return 引用数（0 表示无会话挂载，可安全删除）
     */
    long countSessionsByMemoryStoreId(String storeId);

    /**
     * 统计仍挂载指定保管库的未删除会话数（vault_ids JSONB 数组包含判定）。
     * <p>vault BC 删除保管库前经本方法校验引用：仍被任一未删除会话挂载的保管库
     * 不得删除（409）。</p>
     *
     * @param vaultId 保管库业务 ID（vault_ 前缀）
     * @return 引用数（0 表示无会话挂载，可安全删除）
     */
    long countSessionsByVaultId(String vaultId);
}
