package com.linkroa.deepdataagent.runtime.application.validation;

import com.linkroa.deepdataagent.agent.api.EnvironmentApi;
import com.linkroa.deepdataagent.file.api.FileApi;
import com.linkroa.deepdataagent.memory.api.MemoryStoreApi;
import com.linkroa.deepdataagent.memory.api.dto.MemoryStoreReferenceDTO;
import com.linkroa.deepdataagent.runtime.application.command.CreateSessionCommand;
import com.linkroa.deepdataagent.runtime.application.service.UserIds;
import com.linkroa.deepdataagent.runtime.domain.model.MountViolationException;
import com.linkroa.deepdataagent.runtime.domain.model.SessionResource;
import com.linkroa.deepdataagent.runtime.domain.model.enums.MountViolationType;
import com.linkroa.deepdataagent.runtime.domain.service.SessionMountPolicy;
import com.linkroa.deepdataagent.shared.exception.ResourceNotFoundException;
import com.linkroa.deepdataagent.shared.exception.SessionBusyException;
import com.linkroa.deepdataagent.vault.api.VaultReferenceApi;
import com.linkroa.deepdataagent.vault.api.dto.VaultReferenceDTO;
import jakarta.annotation.Resource;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 会话挂载校验器（应用级挂载契约校验，创建会话前置校验链的 400 / 404 语义权威）。
 * <p>承载创建会话的五项挂载前置校验，全部由 {@code SessionLifecycleService#createSession} 在
 * <b>任何物化落盘与落库之前</b>按「环境 → 文件 → 记忆库 → 保管库」顺序逐项调用，
 * 任一失败即整批拒绝、会话整体不落库（全有或全无）：</p>
 * <ul>
 *   <li>运行环境存在性 / 归属 / 执行平面（{@code self_hosted} → 400）；</li>
 *   <li>挂载文件批内判重（→ 400）、逐项归属门禁（不存在 / 越权 → 404；存在但未就绪
 *       → 409 {@code invalid_request_error}）与批内总量配额（→ 400）；</li>
 *   <li>挂载记忆库存在性与归属（差集非空 → 404）；</li>
 *   <li>保管库存在性与归属（差集非空 → 404）。</li>
 * </ul>
 * <p>判重与配额 MUST 早于任何物化落盘（超配额请求不得留下宿主副本）——该时序由调用方
 * （创建会话用例）保证，本类只做校验、零副作用。挂载字节总量判定所需的文件字节数经
 * {@link #mountedSizeBytes} 材料化为清单入参后交领域聚合（规则权威
 * {@link SessionMountPolicy#validateAppend}），保证聚合不触达跨 BC 端口。</p>
 * <p>调度器触发路径（{@code DefaultSchedulerSessionApi}）与 HTTP 入口同走创建会话用例，
 * 覆盖面即同一校验。入参一律为已归一的类型化命令对象，MUST NOT 接收
 * {@code controller.request} 类型。校验规则与异常类型逐字平移自原命令服务私有方法
 * （decompose-command-facade 2.1）。</p>
 */
@Component
public class SessionMountValidator {

    /** 自托管执行平面环境类型（本期运行时不可执行，创建会话挂接即拒绝）。 */
    private static final String SELF_HOSTED_ENVIRONMENT_TYPE = "self_hosted";

    /** 环境查询契约：校验运行环境存在性 / 归属 / 执行平面（跨 BC 轻量类型解析）。 */
    @Resource
    private EnvironmentApi environmentApi;
    /** 文件服务契约：校验挂载文件归属当前用户且状态 ready（跨 BC 只读查询 + ready 门禁）。 */
    @Resource
    private FileApi fileApi;
    /** 记忆库查询契约：校验挂载记忆库存在且归属当前用户（跨 BC 只读查询）。 */
    @Resource
    private MemoryStoreApi memoryStoreApi;
    /** 保管库引用契约：校验挂载保管库存在且归属当前用户（跨 BC 只读查询）。 */
    @Resource
    private VaultReferenceApi vaultReferenceApi;

    /**
     * 运行环境校验：环境 ID 空白跳过（会话可后挂环境）；经环境服务契约解析类型，
     * 不存在 / 越权统一 404（避免泄露存在性）；{@code self_hosted} 执行平面本期运行时
     * 不可执行，抛非法参数（400 语义）。
     */
    public void validateEnvironment(CreateSessionCommand command) {
        if (StringUtils.isBlank(command.environmentId())) {
            return;
        }
        Long ownerId = UserIds.parse(command.userId());
        String environmentType = ownerId == null ? null
                : environmentApi.resolveType(ownerId, command.environmentId());
        if (environmentType == null) {
            throw new ResourceNotFoundException("运行环境不存在或无权访问: " + command.environmentId());
        }
        if (SELF_HOSTED_ENVIRONMENT_TYPE.equalsIgnoreCase(environmentType)) {
            throw new IllegalArgumentException("不支持的执行平面: 环境类型为 " + SELF_HOSTED_ENVIRONMENT_TYPE);
        }
    }

    /**
     * 挂载文件校验（创建路径）：批内 {@code fileId} / {@code mount_path} 判重（→ 400）、
     * 逐项归属 / 就绪门禁（不存在 / 越权 → 404 不泄露存在性；存在但未就绪 →
     * 409 {@code invalid_request_error}）与批内总量配额（500MB，→ 400）。
     * <p>规则权威在领域（{@link SessionMountPolicy#validateAppend}）：创建路径「既有挂载」恒为空，
     * 文件字节数经 {@link #mountedSizeBytes} 材料化后作入参传入（聚合不触达跨 BC 端口）。
     * 判重与配额 MUST 早于任何物化落盘（超配额请求不得留下宿主副本）。
     * 调度器触发路径经 {@code createSession} 复用同一校验。</p>
     */
    public void validateMountedFiles(CreateSessionCommand command) {
        List<SessionResource> fileResources = command.resources().stream()
                .filter(resource -> SessionResource.FILE_TYPE.equals(resource.type()))
                .toList();
        if (fileResources.isEmpty()) {
            return;
        }
        Long ownerId = UserIds.parse(command.userId());
        try {
            SessionMountPolicy.validateAppend(List.of(), fileResources, mountedSizeBytes(fileResources, ownerId));
        } catch (MountViolationException ex) {
            // 创建路径口径：先区分「存在但未就绪」（409 invalid_request_error，契约明文）
            // 与「不存在 / 越权」（404 不泄露存在性）；判重 / 配额维持「批量单条非法→整批 400」基线契约
            if (ex.violation() == MountViolationType.FILE_NOT_MOUNTABLE) {
                if (fileApi.existsOwnedBy(ex.subject(), ownerId)) {
                    throw new SessionBusyException("挂载文件尚未就绪: " + ex.subject());
                }
                throw new ResourceNotFoundException("挂载文件不存在或无权访问: " + ex.subject());
            }
            throw ex;
        }
    }

    /**
     * 材料化挂载字节清单（既有 + 新增一次取齐，经文件服务契约就绪元数据）：命中就绪即记入清单，
     * 未命中（不存在 / 越权 / 未就绪）不落键——既有项由领域按「元数据缺失计 0」参与求和，
     * 新增项由领域判为不可挂载。清单入参化是聚合保持纯内存的前提。
     * <p>追加挂载路径（{@code appendResources}）与本类创建路径共用本方法，故对外可见。</p>
     *
     * @param resources 挂载资源（内部只取 file 类型）
     * @param ownerId   归属用户 ID（null 时门禁一律拒绝，与逐项查询同口径）
     * @return {@code fileId → sizeBytes} 清单（可继续合并）
     */
    public Map<String, Long> mountedSizeBytes(List<SessionResource> resources, Long ownerId) {
        Map<String, Long> sizeBytesByFileId = new HashMap<>();
        for (SessionResource resource : resources) {
            if (!SessionResource.FILE_TYPE.equals(resource.type())) {
                continue;
            }
            fileApi.findReadyMountMeta(resource.fileId(), ownerId)
                    .ifPresent(meta -> sizeBytesByFileId.put(resource.fileId(), meta.sizeBytes()));
        }
        return sizeBytesByFileId;
    }

    /**
     * 挂载记忆库存在性与归属校验：提取 {@code memory_store} 类挂载资源去重后
     * 经记忆库服务契约批量解析，差集（缺失 / 越权）非空统一映射 404。
     */
    public void validateMountedMemoryStores(CreateSessionCommand command) {
        List<String> storeIds = command.resources().stream()
                .filter(resource -> SessionResource.MEMORY_STORE_TYPE.equals(resource.type()))
                .map(SessionResource::memoryStoreId)
                .distinct()
                .toList();
        if (storeIds.isEmpty()) {
            return;
        }
        Long ownerId = UserIds.parse(command.userId());
        Set<String> resolved = ownerId == null ? Set.of()
                : memoryStoreApi.resolveByIds(ownerId, storeIds).stream()
                        .map(MemoryStoreReferenceDTO::storeId)
                        .collect(Collectors.toSet());
        for (String storeId : storeIds) {
            if (!resolved.contains(storeId)) {
                throw new ResourceNotFoundException("挂载记忆库不存在或无权访问: " + storeId);
            }
        }
    }

    /**
     * 保管库挂载校验：经保管库引用契约批量解析（存在、未归档且归属该 owner），
     * 差集（缺失 / 越权）非空统一映射 404（不泄露存在性）。
     */
    public void validateVaults(CreateSessionCommand command) {
        if (command.vaultIds().isEmpty()) {
            return;
        }
        Long ownerId = UserIds.parse(command.userId());
        Set<String> resolved = ownerId == null ? Set.of()
                : vaultReferenceApi.resolveByIds(ownerId, command.vaultIds()).stream()
                        .map(VaultReferenceDTO::vaultId)
                        .collect(Collectors.toSet());
        for (String vaultId : command.vaultIds()) {
            if (!resolved.contains(vaultId)) {
                throw new ResourceNotFoundException("保管库不存在或无权访问: " + vaultId);
            }
        }
    }
}
