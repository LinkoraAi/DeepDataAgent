package com.linkroa.deepdataagent.skill.infrastructure.assembly;

import com.linkroa.deepdataagent.skill.api.SkillAssetApi;
import com.linkroa.deepdataagent.skill.api.dto.SkillContentDTO;
import com.linkroa.deepdataagent.skill.application.port.SkillAssetPort;
import com.linkroa.deepdataagent.skill.domain.model.SkillAsset;
import com.linkroa.deepdataagent.skill.domain.model.SkillContent;
import com.linkroa.deepdataagent.skill.domain.model.SkillVersion;
import com.linkroa.deepdataagent.skill.domain.repository.SkillAssetRepository;
import jakarta.annotation.Resource;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 技能资产只读服务契约实现（{@link SkillAssetApi}）。
 * <p>进程内委托技能仓储 + 磁盘内容端口解析（owner 隔离），出版发布语言 {@link SkillContentDTO}。
 * 未来接入 Feign 时仅需移除本实现并在接口追加 {@code @FeignClient}。任一归属不匹配或资产 / 版本 /
 * 磁盘内容缺失，统一返回 {@code null} / 空（不泄露存在性），由调用方显式判定装配失败。</p>
 * <p>{@code version} 为空表示动态版绑定：按调用时刻解析技能当前最新版本键（{@code null} 表示
 * 技能已无版本）。</p>
 */
@Service
public class DefaultSkillAssetApi implements SkillAssetApi {

    @Resource
    private SkillAssetRepository skillAssetRepository;
    @Resource
    private SkillAssetPort skillAssetPort;

    @Override
    public boolean exists(String skillId, Long ownerId) {
        return ownedAsset(skillId, ownerId).isPresent();
    }

    @Override
    public String latestVersion(String skillId, Long ownerId) {
        return ownedAsset(skillId, ownerId).map(SkillAsset::latestVersion).orElse(null);
    }

    @Override
    public boolean versionExists(String skillId, String version, Long ownerId) {
        return ownedAsset(skillId, ownerId).isPresent()
                && skillAssetRepository.findVersion(skillId, version).isPresent();
    }

    @Override
    public SkillContentDTO findContent(String skillId, String version, Long ownerId) {
        SkillAsset asset = ownedAsset(skillId, ownerId).orElse(null);
        if (asset == null) {
            return null;
        }
        String resolved = StringUtils.isNotBlank(version) ? version : asset.latestVersion();
        if (resolved == null) {
            return null;
        }
        SkillVersion resolvedVersion = skillAssetRepository.findVersion(asset.skillId(), resolved).orElse(null);
        if (resolvedVersion == null) {
            return null;
        }
        SkillContent content = skillAssetPort.read(asset.skillId(), resolved);
        if (content == null) {
            return null;
        }
        return new SkillContentDTO(
                asset.skillId(),
                resolved,
                resolvedVersion.name(),
                resolvedVersion.description(),
                resolvedVersion.directory(),
                content.markdown(),
                toTextResources(content.resources()));
    }

    /**
     * 资源原始字节 → 运行时装配置面文本：上游框架技能资源契约（{@code AgentSkill.resources}）只接受文本，
     * 故跨 BC 装配面在此按 UTF-8 降级为文本，二进制资源不进运行时装配置面。
     * <p>skill BC 的存储与版本 zip 下载面保持字节无损（见 {@code DefaultSkillAssetPort} /
     * {@code SkillResponseConvert#toZipArchive}），本方法仅作用于装配出口。</p>
     *
     * @param resources 资源相对路径 → 原始字节
     * @return 资源相对路径 → UTF-8 文本
     */
    private static Map<String, String> toTextResources(Map<String, byte[]> resources) {
        Map<String, String> text = new LinkedHashMap<>();
        resources.forEach((relPath, bytes) -> text.put(relPath, new String(bytes, StandardCharsets.UTF_8)));
        return text;
    }

    private Optional<SkillAsset> ownedAsset(String skillId, Long ownerId) {
        return skillAssetRepository.findBySkillId(skillId)
                .filter(asset -> Objects.equals(asset.ownerId(), ownerId));
    }
}