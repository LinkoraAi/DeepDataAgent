package com.linkroa.deepdataagent.runtime.domain.model;

/**
 * 模型访问配置（运行时工厂装配侧）：承载模型凭证 + API 端点。
 * <p>独立于组装规格 {@link AgentAssemblySpec}：凭证/端点属工厂装配参数，
 * 不进领域规格、不参与持久化与序列化；模型标识由规格 {@link AgentAssemblySpec#model()}
 * 单一口径承载，本配置不重复持有。明文凭证经 {@link #toString()} 打码，避免随日志/异常链泄露。</p>
 *
 * @param apiKey  解密后的模型凭证（无鉴权时可空）
 * @param baseUrl 模型 API 端点（可空，默认走提供方内置端点）
 */
public record ModelAccess(String apiKey, String baseUrl) {

    public static ModelAccess of(String apiKey, String baseUrl) {
        return new ModelAccess(apiKey, baseUrl);
    }

    @Override
    public String toString() {
        return "ModelAccess[apiKey=" + mask(apiKey) + ", baseUrl=" + baseUrl + "]";
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