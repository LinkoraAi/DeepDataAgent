package com.linkroa.deepdataagent.agent.application.service;

import com.linkroa.deepdataagent.agent.application.command.CreateEnvironmentCommand;
import com.linkroa.deepdataagent.agent.application.command.UpdateEnvironmentCommand;
import com.linkroa.deepdataagent.agent.application.query.ListEnvironmentQuery;
import com.linkroa.deepdataagent.agent.application.validation.EnvironmentValidator;
import com.linkroa.deepdataagent.agent.domain.model.Environment;
import com.linkroa.deepdataagent.agent.domain.model.EnvironmentListFilter;
import com.linkroa.deepdataagent.agent.domain.repository.DeploymentRepository;
import com.linkroa.deepdataagent.agent.domain.repository.EnvironmentRepository;
import com.linkroa.deepdataagent.runtime.api.SessionReferenceApi;
import com.linkroa.deepdataagent.shared.exception.ResourceConflictException;
import com.linkroa.deepdataagent.shared.exception.ResourceNotFoundException;
import com.linkroa.deepdataagent.shared.result.CursorPage;
import com.linkroa.deepdataagent.shared.result.CursorPageParams;
import com.linkroa.deepdataagent.shared.security.AuthContext;
import jakarta.annotation.Resource;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

/**
 * 运行环境应用服务（增删改查）
 */
@Service
public class EnvironmentApplicationService {

    @Resource
    private EnvironmentRepository environmentRepository;
    @Resource
    private DeploymentRepository deploymentRepository;
    @Resource
    private SessionReferenceApi sessionReferenceApi;
    @Resource
    private TransactionTemplate transactionTemplate;

    /**
     * 创建运行环境：先做 owner 隔离下的名称唯一性校验，再在事务内落库。
     *
     * @param command 创建命令
     * @return 新建运行环境
     * @throws ResourceConflictException 同 owner 下已存在同名环境
     */
    public Environment create(CreateEnvironmentCommand command) {
        Long ownerId = AuthContext.requireUserId();
        validateNameUnique(ownerId, null, command.name());
        Environment environment = Environment.create(
                // 资源 ID 语义前缀（shared/api-conventions：env_ + 无连分 UUID）
                Environment.ENVIRONMENT_ID_PREFIX + UUID.randomUUID().toString().replace("-", ""),
                command.name(),
                command.description(),
                command.config(),
                command.metadata(),
                ownerId
        );
        return transactionTemplate.execute(status -> environmentRepository.save(environment));
    }

    /**
     * 游标分页列出运行环境（6.6 管理面，创建时间降序；metadata 包含 / 创建时间区间过滤；
     * 游标 after_id/before_id 经 owner 定位行位点，未知 / 越权 → 404）。
     */
    public CursorPage<Environment> list(ListEnvironmentQuery query) {
        Long ownerId = query.ownerId();
        CursorPageParams cursor = query.cursor();
        OffsetDateTime cursorCreatedAt = null;
        Long cursorRowId = null;
        boolean reverse = false;
        if (StringUtils.isNotBlank(cursor.afterId())) {
            Environment anchor = requireOwned(cursor.afterId());
            cursorCreatedAt = anchor.createdAt();
            cursorRowId = anchor.id();
        } else if (StringUtils.isNotBlank(cursor.beforeId())) {
            Environment anchor = requireOwned(cursor.beforeId());
            cursorCreatedAt = anchor.createdAt();
            cursorRowId = anchor.id();
            reverse = true;
        }
        EnvironmentListFilter filter = new EnvironmentListFilter(query.metadataJson(),
                query.createdAfter(), query.createdBefore(), cursorCreatedAt, cursorRowId, reverse);
        List<Environment> rows = environmentRepository.findByCursor(ownerId, filter, cursor.limit() + 1);
        boolean hasMore = rows.size() > cursor.limit();
        List<Environment> data = hasMore ? List.copyOf(rows.subList(0, cursor.limit())) : rows;
        if (reverse) {
            data = List.copyOf(data).reversed();
        }
        return CursorPage.of(data, hasMore, Environment::environmentId);
    }

    /**
     * 按业务 ID 读取运行环境（owner 隔离，不存在或越权一律 404）。
     *
     * @param environmentId 环境业务 ID
     * @return 运行环境
     * @throws ResourceNotFoundException 环境不存在或不属于当前用户
     */
    public Environment get(String environmentId) {
        return requireOwned(environmentId);
    }

    /**
     * 更新运行环境：名称唯一性校验（排除自身）通过后，在同一事务内写入。
     *
     * @param command 更新命令
     * @return 更新后的运行环境
     * @throws ResourceNotFoundException 环境不存在或不属于当前用户
     * @throws ResourceConflictException 同 owner 下已存在同名环境
     */
    public Environment update(UpdateEnvironmentCommand command) {
        Environment existing = requireOwned(command.environmentId());
        validateNameUnique(existing.ownerId(), command.environmentId(), command.name());
        Environment updated = Environment.restore(
                existing.id(),
                command.environmentId(),
                command.name(),
                command.description(),
                command.config(),
                command.metadata(),
                existing.ownerId(),
                existing.archivedAt(),
                existing.createdAt(),
                existing.updatedAt(),
                existing.createdBy(),
                existing.updatedBy()
        );
        return transactionTemplate.execute(status -> environmentRepository.update(updated));
    }

    /**
     * 归档运行环境（{@code archived_at} 单列写入）：归档后不可被新 Session 引用
     * （{@code EnvironmentApi} 解析返回 null → 消费方 404）；仍被未删除会话或未归档
     * 调度器引用时拒绝（409）。
     */
    public Environment archive(String environmentId) {
        return transactionTemplate.execute(status -> {
            Environment environment = requireOwnedForUpdate(environmentId);
            long refCount = sessionReferenceApi.countSessionsByEnvironmentId(environmentId);
            EnvironmentValidator.validateArchive(environment, refCount);
            long deploymentRefCount = deploymentRepository.countActiveByEnvironmentId(environmentId);
            if (deploymentRefCount > 0) {
                throw new ResourceConflictException("运行环境「" + environment.name()
                        + "」仍被 " + deploymentRefCount + " 个调度器引用，无法归档");
            }
            return environmentRepository.update(
                    environment.withArchivedAt(OffsetDateTime.now(ZoneId.of("Asia/Shanghai"))));
        });
    }

    /**
     * 校验运行环境名称唯一性（owner 隔离）：排除自身（excludeEnvironmentId）后仍存在同名环境视为冲突。
     *
     * @param ownerId             归属用户 ID
     * @param excludeEnvironmentId 排除的环境业务 ID（更新场景传自身 ID，创建场景传 null）
     * @param name                 待校验名称
     */
    private void validateNameUnique(Long ownerId, String excludeEnvironmentId, String name) {
        environmentRepository.findByNameAndOwnerId(name, ownerId)
                .filter(existing -> !existing.environmentId().equals(excludeEnvironmentId))
                .ifPresent(existing -> {
                    throw new ResourceConflictException("运行环境名称「" + name + "」已被使用");
                });
    }

    /**
     * 删除运行环境：锁行后复核实时会话与调度器引用，均无引用方在<b>同一事务内</b>逻辑删除。
     *
     * @param environmentId 环境业务 ID
     * @throws ResourceNotFoundException 环境不存在或不属于当前用户
     * @throws ResourceConflictException 仍被未删除会话或未归档调度器引用
     */
    public void delete(String environmentId) {
        transactionTemplate.executeWithoutResult(status -> {
            Environment environment = requireOwnedForUpdate(environmentId);
            // 删除守卫依据实时会话引用：仍有会话挂载该环境时拒绝删除（409）
            long refCount = sessionReferenceApi.countSessionsByEnvironmentId(environmentId);
            EnvironmentValidator.validateDelete(environment, refCount);
            // 删除守卫扩展（审查修复 F10）：仍有未归档调度器引用时拒绝删除（409），
            // 否则调度器带着悬空环境引用继续被轮询领取，触发链静默停摆
            long deploymentRefCount = deploymentRepository.countActiveByEnvironmentId(environmentId);
            if (deploymentRefCount > 0) {
                throw new ResourceConflictException("运行环境「" + environment.name()
                        + "」仍被 " + deploymentRefCount + " 个调度器引用，无法删除");
            }
            environmentRepository.deleteByEnvironmentId(environmentId);
        });
    }

    /**
     * 校验运行环境归属：仅 owner 可见（不含则 404）。
     */
    private Environment requireOwned(String environmentId) {
        Environment environment = environmentRepository.findByEnvironmentId(environmentId)
                .orElseThrow(() -> new ResourceNotFoundException("运行环境不存在"));
        if (!environment.ownerId().equals(AuthContext.requireUserId())) {
            throw new ResourceNotFoundException("运行环境不存在");
        }
        return environment;
    }

    /**
     * 校验运行环境归属并锁行。
     */
    private Environment requireOwnedForUpdate(String environmentId) {
        Environment environment = environmentRepository.findByEnvironmentIdForUpdate(environmentId)
                .orElseThrow(() -> new ResourceNotFoundException("运行环境不存在"));
        if (!environment.ownerId().equals(AuthContext.requireUserId())) {
            throw new ResourceNotFoundException("运行环境不存在");
        }
        return environment;
    }
}