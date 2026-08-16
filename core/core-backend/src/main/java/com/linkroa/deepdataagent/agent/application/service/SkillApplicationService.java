package com.linkroa.deepdataagent.agent.application.service;

import com.linkroa.deepdataagent.agent.application.command.CreateSkillCommand;
import com.linkroa.deepdataagent.agent.application.command.PublishSkillVersionCommand;
import com.linkroa.deepdataagent.agent.application.query.ListSkillQuery;
import com.linkroa.deepdataagent.agent.application.validation.SkillValidator;
import com.linkroa.deepdataagent.agent.domain.model.SkillResource;
import com.linkroa.deepdataagent.agent.domain.model.enums.SkillStorageType;
import com.linkroa.deepdataagent.agent.domain.repository.SkillContentStore;
import com.linkroa.deepdataagent.agent.domain.repository.SkillRepository;
import com.linkroa.deepdataagent.agent.infrastructure.config.SkillStorageProperties;
import com.linkroa.deepdataagent.agent.infrastructure.util.Sha256Util;
import com.linkroa.deepdataagent.shared.exception.ResourceConflictException;
import com.linkroa.deepdataagent.shared.exception.ResourceNotFoundException;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

/**
 * 技能资源应用服务（上传 / 发布版本 / 列表 / 详情 / 下载 / 删除）
 */
@Service
public class SkillApplicationService {

    private static final Logger log = LoggerFactory.getLogger(SkillApplicationService.class);

    @Resource
    private SkillRepository skillRepository;
    @Resource
    private SkillContentStore skillContentStore;
    @Resource
    private SkillStorageProperties storageProperties;
    @Resource
    private TransactionTemplate transactionTemplate;

    /**
     * 创建技能（首传 = v1）：先做内容校验，再落存储 + 写版本行。
     * 落盘、领域模型创建、数据库写入任一步失败时，补偿删除已落盘的内容文件，避免孤儿文件残留。
     */
    public SkillResource createSkill(CreateSkillCommand command) {
        validateContent(command.content(), command.declaredSha256());

        String skillId = UUID.randomUUID().toString();
        String storageKey = null;
        try {
            storageKey = skillContentStore.put(skillId, 1, command.content());
            SkillResource skill = SkillResource.create(
                    skillId, 1, command.name(), command.description(), command.skillType(),
                    SkillStorageType.LOCAL_FILE, storageKey,
                    Sha256Util.hex(command.content()), command.content().length
            );
            return transactionTemplate.execute(status -> skillRepository.save(skill));
        } catch (RuntimeException ex) {
            if (storageKey != null) {
                skillContentStore.delete(storageKey);
            }
            throw ex;
        }
    }

    /**
     * 发布技能新版本（再次上传 = MAX+1），旧版本保持可查询。
     * 事务内对最大版本行加 FOR UPDATE 行锁，缩小同一技能并发发布的竞态窗口；
     * READ COMMITTED 下 EvalPlanQual 不会重扫并发插入的新行，锁最大版本行本身
     * 无法串行化 MAX+1，最终由唯一索引 {@code uk_skill_version} 兜底，冲突转 409 语义；
     * 内容文件先落盘，数据库写入/事务失败时补偿删除，避免孤儿文件。
     */
    public SkillResource publishVersion(PublishSkillVersionCommand command) {
        validateContent(command.content(), command.declaredSha256());
        return transactionTemplate.execute(status -> {
            SkillResource latest = skillRepository.findMaxVersionForUpdate(command.skillId())
                    .orElseThrow(() -> new ResourceNotFoundException("技能不存在"));
            int nextVersion = latest.versionNumber() + 1;
            String storageKey = null;
            try {
                storageKey = skillContentStore.put(command.skillId(), nextVersion, command.content());
                SkillResource skill = SkillResource.create(
                        command.skillId(), nextVersion,
                        latest.name(),
                        command.description() != null ? command.description() : latest.description(),
                        latest.skillType(),
                        SkillStorageType.LOCAL_FILE, storageKey,
                        Sha256Util.hex(command.content()), command.content().length
                );
                return skillRepository.save(skill);
            } catch (DuplicateKeyException ex) {
                // 唯一索引 (skill_id, version_number) 冲突：后发事务也读到了旧 max，补偿删除已落盘内容
                if (storageKey != null) {
                    skillContentStore.delete(storageKey);
                }
                throw new ResourceConflictException("技能「" + latest.name() + "」版本已被并发发布，请刷新后重试", ex);
            } catch (RuntimeException ex) {
                if (storageKey != null) {
                    skillContentStore.delete(storageKey);
                }
                throw ex;
            }
        });
    }

    /**
     * 技能列表（每技能仅返回最新版本行）
     */
    public List<SkillResource> listSkills(ListSkillQuery query) {
        return skillRepository.findLatestByCondition(query.keyword(), query.page(), query.size());
    }

    public long countSkills(ListSkillQuery query) {
        return skillRepository.countSkillsByCondition(query.keyword());
    }

    /**
     * 技能详情：返回全部版本（不返回二进制内容）
     */
    public List<SkillResource> getSkillVersions(String skillId) {
        List<SkillResource> versions = skillRepository.listBySkillId(skillId);
        if (versions.isEmpty()) {
            throw new ResourceNotFoundException("技能不存在");
        }
        return versions;
    }

    /**
     * 按版本下载内容（存储中原样读取，以记录的 content_sha256 供调用方校验）
     */
    public byte[] downloadContent(String skillId, int versionNumber) {
        SkillResource skill = skillRepository.findBySkillIdAndVersion(skillId, versionNumber)
                .orElseThrow(() -> new ResourceNotFoundException("技能或版本不存在"));
        return skillContentStore.get(skill.storageKey());
    }

    /**
     * 删除技能（全部版本逻辑删除，随后尽力删除本地内容文件）。
     * <p>台账逻辑删除为主语义，内容文件删除为尽力而为：单文件 IO 失败仅记 WARN，
     * 不阻断删除流程（此时记录已逻辑删除，重复调用会 404，无法原地重试文件清理）。</p>
     */
    public void deleteSkill(String skillId) {
        List<SkillResource> versions = skillRepository.listBySkillId(skillId);
        if (versions.isEmpty()) {
            throw new ResourceNotFoundException("技能不存在");
        }
        List<String> storageKeys = versions.stream()
                .map(SkillResource::storageKey)
                .filter(key -> key != null && !key.isBlank())
                .toList();
        transactionTemplate.executeWithoutResult(status -> skillRepository.deleteBySkillId(skillId));
        // 台账逻辑删除提交成功后再删除内容文件，避免事务回滚导致内容永久丢失
        storageKeys.forEach(key -> {
            try {
                skillContentStore.delete(key);
            } catch (RuntimeException ex) {
                log.warn("技能内容文件删除失败，storageKey={}，请在存储侧人工回收: {}", key, ex.getMessage());
            }
        });
    }

    /**
     * 上传内容统一校验：缺失 500、空内容 400、超限 400、sha256 不匹配 400
     */
    private void validateContent(byte[] content, String declaredSha256) {
        SkillValidator.validateContentPresent(content);
        SkillValidator.validateNonEmpty(content);
        SkillValidator.validateMaxSize(content, storageProperties.getMaxSize());
        SkillValidator.validateSha256(content, declaredSha256);
    }
}