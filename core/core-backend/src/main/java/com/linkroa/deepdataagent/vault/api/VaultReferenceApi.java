package com.linkroa.deepdataagent.vault.api;

import com.linkroa.deepdataagent.vault.api.dto.VaultReferenceDTO;

import java.util.List;

/**
 * 保管库引用查询服务契约（跨 BC 服务边界，未来 Feign 落点）。
 * <p>仅暴露批量存在性 / 归属解析能力（会话创建挂载校验消费）；
 * 凭证明文等材料化解析走 vault BC 的 {@code application.port} 出站端口，
 * MUST NOT 进入本服务契约。当前由 {@code DefaultVaultReferenceApi} 进程内实现，
 * 未来接入 Feign 时仅需在本接口追加 {@code @FeignClient} 注解，消费方无需改动。</p>
 */
public interface VaultReferenceApi {

    /**
     * 批量按业务 ID 解析保管库引用（只返回存在、未归档且归属该 owner 的保管库，
     * 缺失 / 越权的 id 不出现在结果中；owner 不匹配按不存在处理，不泄露存在性）。
     *
     * @param ownerId  所属用户 ID（调用方显式传入，跨 BC / 异步链路不得回退线程上下文）
     * @param vaultIds 保管库业务 ID 列表（可空 / 空）
     * @return 保管库引用契约列表（输入为空时返回空列表）
     */
    List<VaultReferenceDTO> resolveByIds(Long ownerId, List<String> vaultIds);
}
