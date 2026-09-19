package com.linkroa.deepdataagent.agent.application.dto;

/**
 * Agent 模型凭证解析契约（应用层物化 DTO，仅进程内流转，永不发布、永不 Feign 化）。
 * <p>由 agent BC 在 {@code AgentVersionAssemblyPort.resolveModelCredential} 出版，供下游 runtime BC
 * 装配缓存命中路径<b>每轮实时材料化</b>模型凭据段（{@code credential} + {@code apiEndpointUrl}），
 * 与无凭证明文快照（{@code CachedAssemblySnapshot}）合成最终 {@code AgentAssemblySpec}。</p>
 * <p><b>机密材料化载体</b>：{@code credential} 为解密后的模型凭证明文，仅内存持有、不落库、不进日志
 * / 不进响应；{@code toString} 恒掩码，杜绝随异常链泄露。归 {@code application.dto}（非 {@code api} 面）。
 * 无鉴权模型 {@code credential} 可空（与全量 {@link ResolvedAgentAssemblyDTO} 的凭证语义等价），
 * 故本契约不强制凭证字段非空——{@code resolveModelCredential} 的「非空」体现在方法<b>恒返回实例</b>
 * （不返回 {@code null}）与内部<b>重跑归属校验</b>，而非字段级非空约束。</p>
 *
 * @param credential     解密后的模型凭证（无鉴权时可空；toString 掩码）
 * @param apiEndpointUrl 模型 API 端点（可空，默认走提供方内置端点）
 */
public record ResolvedModelCredentialDTO(
        String credential,
        String apiEndpointUrl
) {

    /**
     * 脱敏 toString：明文凭证保留前 4 位其余掩码，避免随日志 / 异常链泄露。
     */
    @Override
    public String toString() {
        return "ResolvedModelCredentialDTO[credential=" + mask(credential)
                + ", apiEndpointUrl=" + apiEndpointUrl + "]";
    }

    /** 凭证打码：非空且长度大于 4 时保留前 4 位，其余替换为掩码（长度不足以保留时全掩码）。 */
    private static String mask(String credential) {
        if (credential == null || credential.isBlank()) {
            return credential;
        }
        if (credential.length() <= 4) {
            return "****";
        }
        return credential.substring(0, 4) + "****";
    }
}
