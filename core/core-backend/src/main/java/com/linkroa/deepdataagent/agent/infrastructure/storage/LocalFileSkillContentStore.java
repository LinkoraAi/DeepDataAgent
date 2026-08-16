package com.linkroa.deepdataagent.agent.infrastructure.storage;

import com.linkroa.deepdataagent.agent.domain.repository.SkillContentStore;
import com.linkroa.deepdataagent.agent.infrastructure.config.SkillStorageProperties;
import com.linkroa.deepdataagent.shared.exception.SkillContentMissingException;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 本地文件技能内容存储（LOCAL_FILE 实现）。
 * <p>确定性路径：{root}/{skill_id}/{version}/{skill_id}-{version}.zip，
 * storage_key 记录相对路径，便于横向迁移。</p>
 */
@Component
public class LocalFileSkillContentStore implements SkillContentStore {

    private final SkillStorageProperties properties;

    public LocalFileSkillContentStore(SkillStorageProperties properties) {
        this.properties = properties;
    }

    @Override
    public String put(String skillId, int versionNumber, byte[] content) {
        try {
            Path relative = relativePath(skillId, versionNumber);
            Path target = properties.getRootPath().resolve(relative);
            Files.createDirectories(target.getParent());
            Files.write(target, content);
            return relative.toString().replace('\\', '/');
        } catch (IOException e) {
            throw new IllegalStateException("技能内容写入本地存储失败: " + skillId + "/v" + versionNumber, e);
        }
    }

    @Override
    public byte[] get(String storageKey) {
        if (storageKey == null || storageKey.isBlank()) {
            throw new SkillContentMissingException("技能内容存储key缺失");
        }
        Path target = properties.getRootPath().resolve(storageKey);
        try {
            if (!Files.exists(target) || !Files.isRegularFile(target)) {
                throw new SkillContentMissingException("技能内容缺失（存储损坏）: " + storageKey);
            }
            return Files.readAllBytes(target);
        } catch (IOException e) {
            throw new IllegalStateException("技能内容读取失败: " + storageKey, e);
        }
    }

    @Override
    public void delete(String storageKey) {
        if (storageKey == null || storageKey.isBlank()) {
            return;
        }
        try {
            Files.deleteIfExists(properties.getRootPath().resolve(storageKey));
        } catch (IOException e) {
            throw new IllegalStateException("技能内容删除失败: " + storageKey, e);
        }
    }

    /**
     * 相对存储路径：{skill_id}/{version}/{skill_id}-{version}.zip
     */
    private Path relativePath(String skillId, int versionNumber) {
        String fileName = skillId + "-" + versionNumber + ".zip";
        return Path.of(skillId, String.valueOf(versionNumber), fileName);
    }
}