package com.linkroa.deepdataagent.rag.infrastructure.client;

import com.linkroa.deepdataagent.agent.domain.model.ModelProfile;
import com.linkroa.deepdataagent.agent.domain.repository.ModelProfileRepository;
import com.linkroa.deepdataagent.agent.infrastructure.util.ModelCredentialEncryptionUtil;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

/**
 * 模型配置解析器（RAG → agent BC 只读依赖，防腐装配点）。
 * <p>把 {@code model_profile_id} 解析为可直接发起 HTTP 调用的端点三元组
 * （baseUrl / apiKey / modelName）。凭证解析沿用 agent BC 内嵌凭证规则：
 * 内嵌模式 AES/GCM 解密；明文仅内存持有、不落库不进日志。</p>
 * <p>TODO 凭证「引用模式」（secretId → vault 密钥解析）待 vault BC 提供单密钥解析端口后接入，
 * 当前 {@code ModelProfile} 领域模型尚无 secretId 分量。</p>
 */
@Component
public class ModelProfileAccess {

    private final ModelProfileRepository modelProfileRepository;
    private final ModelCredentialEncryptionUtil credentialEncryptionUtil;

    public ModelProfileAccess(ModelProfileRepository modelProfileRepository,
                              ModelCredentialEncryptionUtil credentialEncryptionUtil) {
        this.modelProfileRepository = modelProfileRepository;
        this.credentialEncryptionUtil = credentialEncryptionUtil;
    }

    /**
     * 解析模型访问端点。
     *
     * @param profileId 模型配置业务 ID
     * @return 端点三元组
     * @throws IllegalArgumentException profile 不存在
     */
    public ResolvedEndpoint resolve(String profileId) {
        if (StringUtils.isBlank(profileId)) {
            throw new IllegalArgumentException("模型配置 profileId 不能为空");
        }
        ModelProfile profile = modelProfileRepository.findByProfileId(profileId)
                .orElseThrow(() -> new IllegalArgumentException("模型配置不存在: " + profileId));
        return new ResolvedEndpoint(profile.apiEndpointUrl(), resolveCredential(profile),
                profile.modelName(), profile.vectorDimension());
    }

    /**
     * 凭证解析：内嵌模式 AES/GCM 解密（与 agent BC 装配规则一致）；
     * 引用模式（vault 单密钥解析）见类注释 TODO，接入前不解析。
     */
    private String resolveCredential(ModelProfile profile) {
        if (ObjectUtils.isEmpty(profile.encryptedCredential())) {
            return null;
        }
        return credentialEncryptionUtil.decrypt(profile.encryptedCredential());
    }

    /**
     * 解析后的模型访问端点。
     *
     * @param baseUrl         OpenAI 兼容 base URL（形如 {@code https://host/v1}，调用方仅追加资源路径）
     * @param apiKey          解密后的访问凭证（可为空，表示无鉴权端点）
     * @param modelName       模型名称（请求体 model 字段与缓存键组成部分）
     * @param vectorDimension 向量维度（EMBEDDING 类型配置值，CHAT 类型可为空）
     */
    public record ResolvedEndpoint(String baseUrl, String apiKey, String modelName, Integer vectorDimension) {
    }
}
