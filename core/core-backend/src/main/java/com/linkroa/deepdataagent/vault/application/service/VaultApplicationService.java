package com.linkroa.deepdataagent.vault.application.service;

import com.linkroa.deepdataagent.runtime.api.SessionReferenceApi;
import com.linkroa.deepdataagent.shared.exception.ResourceConflictException;
import com.linkroa.deepdataagent.shared.exception.ResourceNotFoundException;
import com.linkroa.deepdataagent.shared.exception.TooManyRequestsException;
import com.linkroa.deepdataagent.shared.result.CursorPage;
import com.linkroa.deepdataagent.shared.result.CursorPageParams;
import com.linkroa.deepdataagent.shared.security.AuthContext;
import com.linkroa.deepdataagent.vault.application.command.AddVaultCredentialCommand;
import com.linkroa.deepdataagent.vault.application.command.CreateVaultCommand;
import com.linkroa.deepdataagent.vault.application.command.UpdateVaultCredentialCommand;
import com.linkroa.deepdataagent.vault.application.convert.VaultCredentialMaterialConvert;
import com.linkroa.deepdataagent.vault.application.dto.HttpDiagnosticDTO;
import com.linkroa.deepdataagent.vault.application.dto.McpProbeOutcomeDTO;
import com.linkroa.deepdataagent.vault.application.dto.OAuthRefreshOutcomeDTO;
import com.linkroa.deepdataagent.vault.application.dto.VaultCredentialViewDTO;
import com.linkroa.deepdataagent.vault.application.port.McpInitializeProbePort;
import com.linkroa.deepdataagent.vault.application.port.VaultCredentialCipherPort;
import com.linkroa.deepdataagent.vault.application.port.VaultOAuthRefreshPort;
import com.linkroa.deepdataagent.vault.application.query.ListVaultCredentialQuery;
import com.linkroa.deepdataagent.vault.application.query.ListVaultQuery;
import com.linkroa.deepdataagent.vault.domain.model.Vault;
import com.linkroa.deepdataagent.vault.domain.model.VaultCredential;
import com.linkroa.deepdataagent.vault.domain.model.VaultCredentialListFilter;
import com.linkroa.deepdataagent.vault.domain.model.VaultCredentialMaterial;
import com.linkroa.deepdataagent.vault.domain.model.VaultCredentialRefresh;
import com.linkroa.deepdataagent.vault.domain.model.VaultListFilter;
import com.linkroa.deepdataagent.vault.domain.model.enums.VaultCredentialAuthType;
import com.linkroa.deepdataagent.vault.domain.model.enums.VaultCredentialRefreshStatus;
import com.linkroa.deepdataagent.vault.domain.model.enums.VaultCredentialValidationStatus;
import com.linkroa.deepdataagent.vault.domain.repository.VaultCredentialRepository;
import com.linkroa.deepdataagent.vault.domain.repository.VaultRepository;
import jakarta.annotation.Resource;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Semaphore;

/**
 * 保管库应用服务（Vault → VaultCredential 两层模型用例编排）。
 * <p>写操作统一以 {@link TransactionTemplate} 界定事务边界；owner 隔离按当前认证用户
 * 收敛（非 owner / 已归档一律视作 404，不泄露存在性）。添加凭证在应用层完成
 * AES-GCM 加密（明文仅内存持有），列表 / 详情仅返回元数据；validateCredential 仅适用于
 * active 的 {@code mcp_oauth} 凭证，按 design D4 语义先尝试刷新（成功即持久化轮换令牌）、
 * 再以当前访问令牌向 MCP 服务器发起最小握手探测，结论三态且不返回任何明文。</p>
 */
@Service
public class VaultApplicationService {

    private static final Logger log = LoggerFactory.getLogger(VaultApplicationService.class);

    /** 保管库业务 ID 前缀（与 Flyway 注释 / 接口对齐） */
    private static final String VAULT_ID_PREFIX = "vault_";
    /**
     * 凭证业务 ID 前缀（对齐公开契约的 {@code vcred_}）：仅约束新写入；
     * 存量 {@code cr_} 凭证不回填、响应原样返回其既有 ID。
     */
    private static final String CREDENTIAL_ID_PREFIX = "vcred_";
    /** 单个保管库活跃凭证数上限（对齐公开契约：一个 Vault 最多 20 个 active 凭证） */
    private static final int MAX_ACTIVE_CREDENTIALS = 20;
    /**
     * 搜索进行中并发上限（design D13）：超限<b>快速失败</b>返回 429 而非排队——搜索由用户交互触发，
     * 排队会让延迟不可预期。
     * <p>以进程内许可计数承载（实例级，非全局）：搜索是短时只读查询，本机限流已足够保护库连接；
     * 许可在 {@code finally} 释放，异常路径不泄漏。</p>
     */
    private static final int MAX_SEARCH_IN_FLIGHT = 10;
    /** 元数据 merge 补丁（键级浅合并）用的 JSON 工具与目标类型 */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    @Resource
    private VaultRepository vaultRepository;
    @Resource
    private VaultCredentialRepository credentialRepository;
    @Resource
    private VaultCredentialCipherPort cipherPort;
    @Resource
    private VaultOAuthRefreshPort oauthRefreshPort;
    @Resource
    private McpInitializeProbePort mcpInitializeProbePort;
    @Resource
    private SessionReferenceApi sessionReferenceApi;
    @Resource
    private TransactionTemplate transactionTemplate;

    /** 搜索进行中许可池（{@link #MAX_SEARCH_IN_FLIGHT} 枚；见该常量说明）。 */
    private final Semaphore searchInFlight = new Semaphore(MAX_SEARCH_IN_FLIGHT);

    /**
     * 创建保管库：生成唯一业务 ID（{@code vault_} 前缀）并归属当前认证用户。
     */
    public Vault create(CreateVaultCommand command) {
        Long ownerId = AuthContext.requireUserId();
        Vault vault = Vault.create(
                VAULT_ID_PREFIX + UUID.randomUUID(),
                command.displayName(),
                command.metadata(),
                ownerId
        );
        return transactionTemplate.execute(status -> vaultRepository.save(vault));
    }

    /**
     * 游标分页列出保管库（6.6 管理面，创建时间降序；{@code display_name} 模糊 / 归档态过滤；
     * 游标 after_id/before_id 经 owner 定位行位点〔允许指向已归档行，配合 include_archived 翻页〕，
     * 未知 / 越权 → 404）。
     * <p>结构性筛选（metadata 精确匹配）不在列表端点，改由 {@link #search} 承载（design D13）。</p>
     */
    public CursorPage<Vault> list(ListVaultQuery query) {
        return queryPage(query).page();
    }

    /**
     * 搜索保管库（design D13）：筛选与游标语义与列表完全一致，
     * <b>首页</b>额外返回满足筛选条件的总数（翻页请求不回计数：计数与游标位点无关，
     * 只在首页有意义且避免每次翻页多打一次库）。
     * <p>进行中并发受 {@link #MAX_SEARCH_IN_FLIGHT} 约束：许可耗尽即快速失败
     * （{@link TooManyRequestsException} → 429 {@code rate_limit_error}），不进入查询。</p>
     */
    public VaultSearchResult search(ListVaultQuery query) {
        if (!searchInFlight.tryAcquire()) {
            throw new TooManyRequestsException("保管库搜索并发过高，请稍后重试");
        }
        try {
            VaultQueryOutcome outcome = queryPage(query);
            VaultListFilter filter = outcome.filter();
            Long total = filter.cursorCreatedAt() == null
                    ? vaultRepository.countByFilter(query.ownerId(), filter) : null;
            return new VaultSearchResult(outcome.page(), total);
        } finally {
            searchInFlight.release();
        }
    }

    /**
     * 保管库分页查询与所用筛选条件的装配（列表 / 搜索共用，避免两处筛选口径漂移）。
     */
    private VaultQueryOutcome queryPage(ListVaultQuery query) {
        Long ownerId = query.ownerId();
        CursorPageParams cursor = query.cursor();
        OffsetDateTime cursorCreatedAt = null;
        Long cursorRowId = null;
        boolean reverse = false;
        if (StringUtils.isNotBlank(cursor.afterId())) {
            Vault anchor = requireOwnedForCursor(cursor.afterId());
            cursorCreatedAt = anchor.createdAt();
            cursorRowId = anchor.id();
        } else if (StringUtils.isNotBlank(cursor.beforeId())) {
            Vault anchor = requireOwnedForCursor(cursor.beforeId());
            cursorCreatedAt = anchor.createdAt();
            cursorRowId = anchor.id();
            reverse = true;
        }
        VaultListFilter filter = new VaultListFilter(query.name(), query.metadataJson(), query.archived(),
                cursorCreatedAt, cursorRowId, reverse);
        List<Vault> rows = vaultRepository.findByCursor(ownerId, filter, cursor.limit() + 1);
        boolean hasMore = rows.size() > cursor.limit();
        List<Vault> data = hasMore ? List.copyOf(rows.subList(0, cursor.limit())) : rows;
        if (reverse) {
            data = List.copyOf(data).reversed();
        }
        return new VaultQueryOutcome(CursorPage.of(data, hasMore, Vault::vaultId), filter);
    }

    /**
     * 读取保管库详情：owner 隔离 + 已归档过滤（非 owner / 已归档 → 404）。
     */
    public Vault get(String vaultId) {
        return requireActiveOwned(vaultId);
    }

    /**
     * 归档保管库（软删）：置 archived_at，列表 / 详情不再返回。
     */
    public void archive(String vaultId) {
        transactionTemplate.executeWithoutResult(status -> {
            requireActiveOwned(vaultId);
            vaultRepository.archive(vaultId, OffsetDateTime.now(ZoneId.of("Asia/Shanghai")));
        });
    }

    /**
     * 删除保管库（6.6 管理面）：仍有历史 Session 引用时拒绝（409）；
     * 同一事务内先级联逻辑删其下全部凭证，再逻辑删保管库（历史数据保留）。
     */
    public void delete(String vaultId) {
        transactionTemplate.executeWithoutResult(status -> {
            Vault vault = requireActiveOwnedForUpdate(vaultId);
            long refCount = sessionReferenceApi.countSessionsByVaultId(vault.vaultId());
            if (refCount > 0) {
                throw new ResourceConflictException(
                        "保管库仍被 " + refCount + " 个历史 Session 引用，不可删除，请改用归档");
            }
            // 直接使用当前 user 校验后的 vaultId（锁行副本），级联清理凭证
            credentialRepository.deleteByVaultId(vault.vaultId());
            vaultRepository.deleteByVaultId(vault.vaultId());
        });
    }

    /**
     * 添加凭证：AES-GCM 加密明文为 ciphertext 落库（BYTEA），返回不含明文的凭证记录。
     * <p>落库前先做两条保管库级约束校验（对齐公开契约的 409 语义，避免直撞唯一索引报 500）：
     * 同一 MCP server 已有活跃凭证、或活跃凭证数已达 {@link #MAX_ACTIVE_CREDENTIALS} 上限。</p>
     */
    public VaultCredential addCredential(AddVaultCredentialCommand command) {
        requireActiveOwned(command.vaultId());
        if (credentialRepository.existsActiveByVaultIdAndUrl(command.vaultId(), command.mcpServerUrl())) {
            throw new ResourceConflictException("该 MCP server 已存在活跃凭证，请先归档或更新原凭证");
        }
        if (credentialRepository.countActiveByVaultId(command.vaultId()) >= MAX_ACTIVE_CREDENTIALS) {
            throw new ResourceConflictException(
                    "保管库活跃凭证数已达上限 " + MAX_ACTIVE_CREDENTIALS + " 条，请先归档不再使用的凭证");
        }
        byte[] ciphertext = cipherPort.encrypt(command.token());
        VaultCredential credential = VaultCredential.create(
                CREDENTIAL_ID_PREFIX + UUID.randomUUID(),
                command.vaultId(),
                VaultCredentialAuthType.fromValue(command.authType()),
                command.mcpServerUrl(),
                ciphertext
        );
        return transactionTemplate.execute(status -> credentialRepository.save(credential));
    }

    /**
     * 游标分页列出某保管库下的凭证（仅元数据，不返回明文；创建时间降序，
     * 支持 {@code name} 按 MCP 服务器 URL 模糊与归档态过滤；游标锚点必须是本保管库内的凭证，
     * 未知 / 越权 → 404）。
     */
    public CursorPage<VaultCredential> listCredentials(ListVaultCredentialQuery query) {
        requireActiveOwned(query.vaultId());
        CursorPageParams cursor = query.cursor();
        OffsetDateTime cursorCreatedAt = null;
        Long cursorRowId = null;
        boolean reverse = false;
        if (StringUtils.isNotBlank(cursor.afterId())) {
            VaultCredential anchor = requireCredentialForCursor(query.vaultId(), cursor.afterId());
            cursorCreatedAt = anchor.createdAt();
            cursorRowId = anchor.id();
        } else if (StringUtils.isNotBlank(cursor.beforeId())) {
            VaultCredential anchor = requireCredentialForCursor(query.vaultId(), cursor.beforeId());
            cursorCreatedAt = anchor.createdAt();
            cursorRowId = anchor.id();
            reverse = true;
        }
        VaultCredentialListFilter filter = new VaultCredentialListFilter(query.urlSearch(), query.archived(),
                cursorCreatedAt, cursorRowId, reverse);
        List<VaultCredential> rows = credentialRepository.findByCursor(query.vaultId(), filter,
                cursor.limit() + 1);
        return CursorPage.slice(rows, cursor, reverse, VaultCredential::credentialId);
    }

    /**
     * 读取某凭证详情（仅元数据，不返回明文；不存在 / 越权 → 404）。
     */
    public VaultCredential getCredential(String vaultId, String credentialId) {
        requireActiveOwned(vaultId);
        return credentialRepository.findByVaultIdAndCredentialId(vaultId, credentialId)
                .orElseThrow(() -> new ResourceNotFoundException("凭证不存在"));
    }

    /**
     * 删除凭证（逻辑删，见 tasks 5.3）：删除后列表 / 详情不可见，
     * 且不再参与任何 Session 挂载鉴权（解析路径按未删除行取凭证）。
     * 不存在 / 越权 → 404。
     */
    public void deleteCredential(String vaultId, String credentialId) {
        transactionTemplate.executeWithoutResult(status -> {
            requireActiveOwned(vaultId);
            if (credentialRepository.delete(vaultId, credentialId) == 0) {
                throw new ResourceNotFoundException("凭证不存在");
            }
        });
    }

    /**
     * 凭证领域对象 → 脱敏发布视图（协议层唯一凭证出口，见 {@link VaultCredentialViewDTO}）。
     *
     * <p>只取非密列；{@code mcp_oauth} 额外解密信封以取刷新配置的<b>非密成分</b>
     * （客户端 ID / 令牌端点 / scope / 鉴权方式），访问令牌 / 刷新令牌 / 客户端密钥在装配时丢弃。
     * 密文不可解（密钥轮换 / 历史脏行）时收敛为「无刷新配置」——读侧不得因此 5xx。</p>
     */
    public VaultCredentialViewDTO toView(VaultCredential credential) {
        return new VaultCredentialViewDTO(
                credential.credentialId(),
                credential.vaultId(),
                credential.authType().getValue(),
                credential.mcpServerUrl(),
                credential.expiresAt(),
                refreshViewOf(credential),
                credential.metadata(),
                credential.archivedAt(),
                credential.createdAt(),
                credential.updatedAt()
        );
    }

    /**
     * 脱敏刷新配置装配（仅 {@code mcp_oauth} 且信封内含刷新配置时非空）。
     */
    private VaultCredentialViewDTO.Refresh refreshViewOf(VaultCredential credential) {
        if (credential.authType() != VaultCredentialAuthType.MCP_OAUTH) {
            return null;
        }
        try {
            VaultCredentialRefresh refresh = VaultCredentialMaterialConvert.INSTANCE
                    .parse(cipherPort.decrypt(credential.ciphertext())).refresh();
            if (refresh == null) {
                return null;
            }
            return new VaultCredentialViewDTO.Refresh(refresh.clientId(), refresh.tokenEndpoint(),
                    refresh.resource(), refresh.scope(), refresh.tokenEndpointAuth().type().getValue());
        } catch (RuntimeException e) {
            log.warn("凭证脱敏视图解密失败，refresh 配置不展示: credentialId={}", credential.credentialId());
            return null;
        }
    }

    /**
     * 凭证游标锚点定位：必须是<b>本保管库内</b>的未删除凭证（跨保管库 / 未知 → 404）。
     */
    private VaultCredential requireCredentialForCursor(String vaultId, String credentialId) {
        return credentialRepository.findByVaultIdAndCredentialId(vaultId, credentialId)
                .orElseThrow(() -> new ResourceNotFoundException("凭证不存在"));
    }

    /**
     * 归档凭证（软删）：置 archived_at，归档后不再注入新 Session；已归档凭证再次归档返回 409。
     */
    public void archiveCredential(String vaultId, String credentialId) {
        transactionTemplate.executeWithoutResult(status -> {
            requireActiveOwned(vaultId);
            VaultCredential credential = credentialRepository.findByVaultIdAndCredentialId(vaultId, credentialId)
                    .orElseThrow(() -> new ResourceNotFoundException("凭证不存在"));
            if (credential.archived()) {
                throw new ResourceConflictException("已归档凭证不可重复归档");
            }
            credentialRepository.archive(vaultId, credentialId,
                    OffsetDateTime.now(ZoneId.of("Asia/Shanghai")));
        });
    }

    /**
     * 更新凭证（merge 补丁，见 design D3 / D3.1）：未传字段保持原值、不版本化；
     * 身份字段（{@code auth.type} / {@code mcp_server_url} / {@code refresh.client_id} /
     * {@code refresh.token_endpoint}）不可修改——{@code mcp_server_url} 等由协议层白名单拒绝，
     * {@code auth.type} 的<b>一致性</b>在此裁决（与既有类型不一致 → 400）。
     *
     * <p>秘密材料按类型合并：{@code static_bearer} 仅轮换 {@code token}；
     * {@code mcp_oauth} 在解密信封上合并 {@code access_token} 与 {@code refresh} 子补丁后整体重加密，
     * 且<b>无刷新配置的凭证不可补加 refresh</b>（否则会造出刷新路径必然失败的半残凭证）。
     * 已归档凭证不可更新（409）。</p>
     */
    public VaultCredential updateCredential(UpdateVaultCredentialCommand command) {
        requireActiveOwned(command.vaultId());
        VaultCredential existing = credentialRepository
                .findByVaultIdAndCredentialId(command.vaultId(), command.credentialId())
                .orElseThrow(() -> new ResourceNotFoundException("凭证不存在"));
        if (existing.archived()) {
            throw new ResourceConflictException("已归档凭证不可更新，请重新添加凭证");
        }
        if (command.authPresent() && !existing.authType().getValue().equals(command.authType())) {
            throw new IllegalArgumentException(
                    "auth.type 与凭证既有类型不一致: " + existing.authType().getValue());
        }
        byte[] ciphertext = mergeSecretMaterial(existing, command);
        OffsetDateTime expiresAt = command.expiresAtPresent() ? command.expiresAt() : existing.expiresAt();
        String metadata = resolveMetadata(existing.metadata(), command);
        VaultCredential updated = existing.withUpdated(ciphertext, expiresAt, metadata);
        return transactionTemplate.execute(status -> credentialRepository.update(updated));
    }

    /**
     * 按类型合并秘密材料：返回最终密文字节（{@code null} = 保持原密文）。
     */
    private byte[] mergeSecretMaterial(VaultCredential existing, UpdateVaultCredentialCommand command) {
        if (existing.authType() == VaultCredentialAuthType.STATIC_BEARER) {
            return command.tokenPresent() ? cipherPort.encrypt(command.token()) : null;
        }
        if (!command.accessTokenPresent() && !command.refreshPresent()) {
            return null;
        }
        VaultCredentialMaterial material =
                VaultCredentialMaterialConvert.INSTANCE.parse(cipherPort.decrypt(existing.ciphertext()));
        if (command.accessTokenPresent()) {
            material = material.withAccessToken(command.accessToken());
        }
        if (command.refreshPresent()) {
            if (!material.hasRefresh()) {
                throw new IllegalArgumentException("该凭证创建时无 refresh 配置，仅可修补既有刷新配置");
            }
            VaultCredentialRefresh refresh = material.refresh();
            if (command.refreshTokenPresent()) {
                refresh = refresh.withRefreshToken(command.refreshToken());
            }
            if (command.refreshScopePresent()) {
                refresh = refresh.withScope(command.refreshScope());
            }
            if (command.refreshTokenEndpointAuthPresent()) {
                refresh = refresh.withTokenEndpointAuth(command.refreshTokenEndpointAuth());
            }
            material = material.withRefresh(refresh);
        }
        return cipherPort.encrypt(VaultCredentialMaterialConvert.INSTANCE.toEnvelopeJson(material));
    }

    /**
     * 元数据 merge 补丁最终值：未提供 → 保持原值；整体显式 null → {@code {}}；否则浅合并
     * （增量中值为 null 的键表示删除该键）。
     */
    private String resolveMetadata(String existingMetadata, UpdateVaultCredentialCommand command) {
        if (!command.metadataPresent()) {
            return existingMetadata;
        }
        if (command.metadataMerge() == null) {
            return "{}";
        }
        return mergeMetadata(existingMetadata, command.metadataMerge());
    }

    /**
     * 元数据浅合并（merge-patch 键级语义）：既有 JSON 对象叠加增量（同名键覆盖），
     * 增量中值为 null 的键表示<b>删除该键</b>；解析失败的历史脏值收敛为空对象，不阻断更新。
     */
    private String mergeMetadata(String existingJson, String mergeJson) {
        Map<String, Object> merged = parseJsonObject(existingJson);
        parseJsonObject(mergeJson).forEach((key, value) -> {
            if (value == null) {
                merged.remove(key);
            } else {
                merged.put(key, value);
            }
        });
        try {
            return OBJECT_MAPPER.writeValueAsString(merged);
        } catch (Exception e) {
            throw new IllegalStateException("凭证元数据合并结果序列化失败: " + e.getMessage(), e);
        }
    }

    /** JSON 对象文本 → 可变键值对象（空白 / 非法收敛为空对象）。 */
    private Map<String, Object> parseJsonObject(String json) {
        if (StringUtils.isBlank(json)) {
            return new LinkedHashMap<>();
        }
        try {
            Map<String, Object> parsed = OBJECT_MAPPER.readValue(json, MAP_TYPE);
            return parsed == null ? new LinkedHashMap<>() : new LinkedHashMap<>(parsed);
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    /**
     * 校验凭证（对齐 design D4 的 validate 语义）：仅适用于 <b>active 的 {@code mcp_oauth} 凭证</b>
     * （类型不符或已归档一律 409）；执行两步——① 持有 refresh token 时先尝试刷新，
     * 刷新成功即<b>持久化轮换后的令牌</b>（校验因此具备「治愈」临近过期凭证的能力）；
     * ② 以当前访问令牌向 MCP 服务器发起最小握手探测（JSON-RPC {@code initialize}）。
     *
     * <p><b>分类规则</b>：确定性 4xx（{@code 408} / {@code 429} 除外）或无可信访问令牌
     * （解密失败）→ {@code invalid}；连接错误、超时、{@code 408} / {@code 429} / {@code 5xx}
     * → {@code unknown}（不得据此判定凭证失效，避免误导用户重新授权）。</p>
     *
     * <p>刷新失败<b>不覆盖已存令牌</b>；响应不含任何明文（外部响应体已脱敏截断）。</p>
     *
     * @return 校验结论（三态）与刷新 / 探测诊断，明文绝不出应用边界
     */
    public CredentialValidationResult validateCredential(String vaultId, String credentialId) {
        requireActiveOwned(vaultId);
        VaultCredential credential = credentialRepository.findByVaultIdAndCredentialId(vaultId, credentialId)
                .orElseThrow(() -> new ResourceNotFoundException("凭证不存在"));
        if (credential.archived()) {
            throw new ResourceConflictException("已归档凭证不参与校验");
        }
        if (credential.authType() != VaultCredentialAuthType.MCP_OAUTH) {
            throw new ResourceConflictException(
                    "仅 active 的 mcp_oauth 凭证可校验，当前类型为 " + credential.authType().getValue());
        }
        OffsetDateTime validatedAt = OffsetDateTime.now(ZoneId.of("Asia/Shanghai"));

        // 无可信访问令牌（解密失败）→ 直接判 invalid，不发起任何刷新 / 探测
        VaultCredentialMaterial material;
        try {
            material = VaultCredentialMaterialConvert.INSTANCE.parse(cipherPort.decrypt(credential.ciphertext()));
        } catch (RuntimeException e) {
            log.warn("凭证校验解密失败: vaultId={}, credentialId={}", vaultId, credentialId);
            return new CredentialValidationResult(credential.credentialId(), credential.vaultId(),
                    VaultCredentialValidationStatus.INVALID, validatedAt, false,
                    new RefreshDiagnostic(VaultCredentialRefreshStatus.NO_REFRESH_TOKEN, null), null);
        }

        // ① 刷新：持 refresh token 时先试刷新（成功即持久化轮换后的令牌，并以新令牌继续探测）
        RefreshStep refresh = refreshIfConfigured(credential, material, validatedAt);
        material = refresh.material();

        // ② 探测：以当前访问令牌发起最小 MCP 握手
        McpProbeOutcomeDTO probe = mcpInitializeProbePort.probe(credential.mcpServerUrl(), material.accessToken());
        VaultCredentialValidationStatus status = classifyProbe(probe);
        McpProbe mcpProbe = status == VaultCredentialValidationStatus.VALID ? null
                : new McpProbe(probe.method(), probe.httpResponse());
        return new CredentialValidationResult(credential.credentialId(), credential.vaultId(), status,
                validatedAt, material.hasRefresh(), refresh.diagnostic(), mcpProbe);
    }

    /**
     * 持有刷新配置时尝试刷新并分类（design D4）：刷新成功即持久化轮换后的材料，并回传替换后的材料
     * （探测 MUST 以轮换后的访问令牌发起）。
     */
    private RefreshStep refreshIfConfigured(VaultCredential credential, VaultCredentialMaterial material,
                                            OffsetDateTime validatedAt) {
        VaultCredentialRefresh refresh = material.refresh();
        if (refresh == null) {
            return new RefreshStep(
                    new RefreshDiagnostic(VaultCredentialRefreshStatus.NO_REFRESH_TOKEN, null), material);
        }
        OAuthRefreshOutcomeDTO outcome = oauthRefreshPort.refresh(refresh);
        VaultCredentialRefreshStatus status = classifyRefresh(outcome);
        if (status != VaultCredentialRefreshStatus.SUCCEEDED) {
            // 刷新失败不覆盖已存令牌，探测仍以既有访问令牌发起
            return new RefreshStep(new RefreshDiagnostic(status, outcome.httpResponse()), material);
        }
        VaultCredentialMaterial rotated = mergeRotatedToken(material, outcome);
        persistRotatedMaterial(credential, rotated, rotatedExpiresAt(credential, outcome, validatedAt));
        return new RefreshStep(new RefreshDiagnostic(status, outcome.httpResponse()), rotated);
    }

    /**
     * 合并刷新返回的令牌：访问令牌必换；服务商轮换刷新令牌时一并替换（未轮换沿用原值）。
     */
    private VaultCredentialMaterial mergeRotatedToken(VaultCredentialMaterial material,
                                                      OAuthRefreshOutcomeDTO outcome) {
        VaultCredentialMaterial rotated = material.withAccessToken(outcome.accessToken());
        if (StringUtils.isNotBlank(outcome.refreshToken()) && rotated.hasRefresh()) {
            rotated = rotated.withRefresh(rotated.refresh().withRefreshToken(outcome.refreshToken()));
        }
        return rotated;
    }

    /**
     * 刷新成功后的到期时间：响应携带 {@code expires_in} 时按当前时刻重算，否则保持既有值。
     */
    private OffsetDateTime rotatedExpiresAt(VaultCredential credential, OAuthRefreshOutcomeDTO outcome,
                                            OffsetDateTime validatedAt) {
        return outcome.expiresInSeconds() == null
                ? credential.expiresAt() : validatedAt.plusSeconds(outcome.expiresInSeconds());
    }

    /**
     * 持久化轮换后的秘密材料（刷新成功 MUST 落库，见 design D4）；明文仅内存持有。
     */
    private void persistRotatedMaterial(VaultCredential credential, VaultCredentialMaterial material,
                                        OffsetDateTime expiresAt) {
        byte[] ciphertext = cipherPort.encrypt(VaultCredentialMaterialConvert.INSTANCE.toEnvelopeJson(material));
        VaultCredential updated = credential.withUpdated(ciphertext, expiresAt, credential.metadata());
        transactionTemplate.execute(status -> credentialRepository.update(updated));
    }

    /**
     * 刷新结论分类（design D4）：无响应 / 408 / 429 / 5xx → {@code connect_error}（不可归因于凭证）；
     * 确定性 4xx（或 2xx 但未携带访问令牌）→ {@code failed}；换得令牌 → {@code succeeded}。
     */
    private static VaultCredentialRefreshStatus classifyRefresh(OAuthRefreshOutcomeDTO outcome) {
        HttpDiagnosticDTO response = outcome.httpResponse();
        if (response == null) {
            return VaultCredentialRefreshStatus.CONNECT_ERROR;
        }
        if (StringUtils.isNotBlank(outcome.accessToken())) {
            return VaultCredentialRefreshStatus.SUCCEEDED;
        }
        return deterministicRejection(response.statusCode())
                ? VaultCredentialRefreshStatus.FAILED : VaultCredentialRefreshStatus.CONNECT_ERROR;
    }

    /**
     * 探测结论分类（design D4）：2xx / 3xx → {@code valid}；确定性 4xx → {@code invalid}；
     * 无响应 / 408 / 429 / 5xx → {@code unknown}。
     */
    private static VaultCredentialValidationStatus classifyProbe(McpProbeOutcomeDTO outcome) {
        HttpDiagnosticDTO response = outcome.httpResponse();
        if (response == null) {
            return VaultCredentialValidationStatus.UNKNOWN;
        }
        int statusCode = response.statusCode();
        if (statusCode >= 200 && statusCode < 400) {
            return VaultCredentialValidationStatus.VALID;
        }
        return deterministicRejection(statusCode)
                ? VaultCredentialValidationStatus.INVALID : VaultCredentialValidationStatus.UNKNOWN;
    }

    /**
     * 是否确定性拒绝：4xx 且非 {@code 408}（超时）/ {@code 429}（限流）——
     * 后者与 5xx 同属「不可据以判定凭证失效」的响应。
     */
    private static boolean deterministicRejection(int statusCode) {
        return statusCode >= 400 && statusCode < 500 && statusCode != 408 && statusCode != 429;
    }

    /**
     * 凭证校验结果（协议层仅透传，明文绝不出应用边界）。
     *
     * @param credentialId    凭证业务 ID
     * @param vaultId         所属保管库业务 ID
     * @param status          校验结论（valid / invalid / unknown）
     * @param validatedAt     校验时刻
     * @param hasRefreshToken 该凭证是否持有刷新配置（无刷新配置时跳过刷新步骤）
     * @param refresh         刷新结论（四态；失败时含已脱敏截断的响应诊断）
     * @param mcpProbe        MCP 握手探测诊断（<b>仅探测失败时非空</b>，含失败的调用名）
     */
    public record CredentialValidationResult(
            String credentialId,
            String vaultId,
            VaultCredentialValidationStatus status,
            OffsetDateTime validatedAt,
            boolean hasRefreshToken,
            RefreshDiagnostic refresh,
            McpProbe mcpProbe
    ) {
    }

    /**
     * 刷新诊断（结论 + 可选响应诊断）。
     */
    public record RefreshDiagnostic(VaultCredentialRefreshStatus status, HttpDiagnosticDTO httpResponse) {
    }

    /**
     * MCP 探测诊断（失败的调用名 + 可选响应诊断）。
     */
    public record McpProbe(String method, HttpDiagnosticDTO httpResponse) {
    }

    /**
     * 刷新步骤结果（诊断 + 该步骤后的秘密材料：成功即轮换后的材料）。
     */
    private record RefreshStep(RefreshDiagnostic diagnostic, VaultCredentialMaterial material) {
    }

    /**
     * 保管库分页查询结果（页数据 + 本次所用筛选条件，供搜索端点复用计数）。
     */
    private record VaultQueryOutcome(CursorPage<Vault> page, VaultListFilter filter) {
    }

    /**
     * 保管库搜索结果（页数据 + 首页总数）。
     *
     * @param page  游标分页结果
     * @param total 满足筛选条件的总数（<b>仅首页非空</b>，翻页时为 null）
     */
    public record VaultSearchResult(CursorPage<Vault> page, Long total) {
    }

    /**
     * owner 隔离 + 归档过滤：非 owner / 已归档 → 404。
     */
    private Vault requireActiveOwned(String vaultId) {
        Vault vault = vaultRepository.findByVaultId(vaultId)
                .orElseThrow(() -> new ResourceNotFoundException("保管库不存在"));
        if (!vault.ownerId().equals(AuthContext.requireUserId()) || vault.archived()) {
            throw new ResourceNotFoundException("保管库不存在");
        }
        return vault;
    }

    /**
     * 锁行版本的 owner 隔离 + 归档过滤（删除用，避免 check-then-act 竞态）。
     */
    private Vault requireActiveOwnedForUpdate(String vaultId) {
        Vault vault = vaultRepository.findByVaultIdForUpdate(vaultId)
                .orElseThrow(() -> new ResourceNotFoundException("保管库不存在"));
        if (!vault.ownerId().equals(AuthContext.requireUserId()) || vault.archived()) {
            throw new ResourceNotFoundException("保管库不存在");
        }
        return vault;
    }

    /**
     * 游标锚点定位：仅要求 owner 匹配（<b>允许已归档行</b>，status=archived 翻页时
     * after_id 指向归档行）；未知 / 越权 → 404。
     */
    private Vault requireOwnedForCursor(String vaultId) {
        Vault vault = vaultRepository.findByVaultId(vaultId)
                .orElseThrow(() -> new ResourceNotFoundException("保管库不存在"));
        if (!vault.ownerId().equals(AuthContext.requireUserId())) {
            throw new ResourceNotFoundException("保管库不存在");
        }
        return vault;
    }
}