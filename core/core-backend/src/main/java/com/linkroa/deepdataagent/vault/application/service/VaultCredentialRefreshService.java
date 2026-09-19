package com.linkroa.deepdataagent.vault.application.service;

import com.linkroa.deepdataagent.vault.application.convert.VaultCredentialMaterialConvert;
import com.linkroa.deepdataagent.vault.application.dto.OAuthRefreshOutcomeDTO;
import com.linkroa.deepdataagent.vault.application.port.VaultCredentialCipherPort;
import com.linkroa.deepdataagent.vault.application.port.VaultOAuthRefreshPort;
import com.linkroa.deepdataagent.vault.application.port.VaultRefreshSingleFlightPort;
import com.linkroa.deepdataagent.vault.domain.model.VaultCredential;
import com.linkroa.deepdataagent.vault.domain.model.VaultCredentialMaterial;
import com.linkroa.deepdataagent.vault.domain.model.VaultCredentialRefresh;
import com.linkroa.deepdataagent.vault.domain.repository.VaultCredentialRepository;
import com.linkroa.deepdataagent.vault.infrastructure.config.VaultOAuthProperties;
import jakarta.annotation.Resource;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Optional;

/**
 * 凭证临期刷新编排（design D6 / D7 与公开契约「access token 临期自动刷新」）。
 *
 * <p>在 MCP discovery 或执行前对本轮所需凭证<b>按需刷新</b>：仅当持有 refresh token
 * <b>且</b> access token 进入刷新窗口（默认 60s）时发起；未临期与无 refresh token 一律不刷新。</p>
 *
 * <p><b>并发控制为两层</b>（design D6）：Redis 单飞键保证「同一凭证只实际发出一次刷新请求」
 * （若仅靠 CAS，两实例都会刷新，服务商轮换 refresh token 时后到者会用已作废值使凭证失效），
 * DB 条件更新（CAS 比对当前密文）保证「只有一个实例的写入胜出」；CAS 落败方不覆盖胜出结果，
 * 改为重读已持久化的材料返回。</p>
 *
 * <p><b>失败语义</b>：刷新失败 MUST NOT 覆盖已存令牌（返回既有材料），MUST NOT 把
 * 「无法判定」（无响应 / 5xx / 408 / 429）当作凭证失效。轮换后的 refresh token 与新到期时间
 * 与 access token <b>同一次加密信封写入</b>原子落库。</p>
 */
@Service
public class VaultCredentialRefreshService {

    private static final Logger log = LoggerFactory.getLogger(VaultCredentialRefreshService.class);

    /** 刷新单飞持有权存活时长（覆盖一次令牌端点出网的最坏耗时 + 余量）。 */
    private static final Duration SINGLE_FLIGHT_TTL = Duration.ofSeconds(30);

    @Resource
    private VaultCredentialCipherPort cipherPort;
    @Resource
    private VaultOAuthRefreshPort oauthRefreshPort;
    @Resource
    private VaultRefreshSingleFlightPort singleFlightPort;
    @Resource
    private VaultCredentialRepository credentialRepository;
    @Resource
    private VaultOAuthProperties oauthProperties;
    @Resource
    private TransactionTemplate transactionTemplate;

    /**
     * 按需刷新并返回该凭证当前可用的秘密材料。
     *
     * @param credential 凭证（须为未归档、已解密材料对应的行）
     * @param material   已解密的既有材料
     * @return 可用的秘密材料（未刷新 / 刷新失败时为既有材料；刷新成功且写入胜出时为轮换后材料）
     */
    public VaultCredentialMaterial refreshIfNeeded(VaultCredential credential, VaultCredentialMaterial material) {
        VaultCredentialRefresh refresh = material.refresh();
        if (refresh == null) {
            // 无 refresh token：不发起刷新（按凭证不可用处理，由调用方按未命中凭证语义处置）
            return material;
        }
        if (!withinRefreshWindow(credential.expiresAt())) {
            return material;
        }
        // 刷新前不泄露令牌：单飞键的尝试先于任何出网调用
        Optional<String> holder = singleFlightPort.tryAcquire(credential.credentialId(), SINGLE_FLIGHT_TTL);
        if (holder.isEmpty()) {
            // 他人正在刷新：复用其成果（重读已持久化材料，避免双刷使 refresh token 失效）
            log.debug("凭证刷新已被其他实例接管，复用其持久化结果: credentialId={}", credential.credentialId());
            return rereadMaterial(credential, material);
        }
        try {
            OAuthRefreshOutcomeDTO outcome = oauthRefreshPort.refresh(refresh);
            if (StringUtils.isBlank(outcome.accessToken())) {
                // 刷新失败不覆盖已存令牌；也不改变凭证可用性判定
                log.debug("凭证刷新未换得令牌，保持既有令牌: credentialId={}", credential.credentialId());
                return material;
            }
            VaultCredentialMaterial rotated = mergeRotatedToken(material, outcome);
            OffsetDateTime expiresAt = rotatedExpiresAt(credential, outcome);
            return persistIfUnchanged(credential, rotated, expiresAt)
                    ? rotated : rereadMaterial(credential, material);
        } finally {
            singleFlightPort.release(credential.credentialId(), holder.get());
        }
    }

    /**
     * 是否进入刷新窗口：到期时间缺失视为「无到期」（不刷新）；距到期不足刷新窗口即临期。
     */
    private boolean withinRefreshWindow(OffsetDateTime expiresAt) {
        if (expiresAt == null) {
            return false;
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneId.of("Asia/Shanghai"));
        return !expiresAt.isAfter(now.plus(oauthProperties.refreshWindow()));
    }

    /**
     * 合并刷新返回的令牌：访问令牌必换；服务商轮换刷新令牌时一并替换（未轮换沿用原值）。
     */
    private static VaultCredentialMaterial mergeRotatedToken(VaultCredentialMaterial material,
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
    private static OffsetDateTime rotatedExpiresAt(VaultCredential credential, OAuthRefreshOutcomeDTO outcome) {
        return outcome.expiresInSeconds() == null
                ? credential.expiresAt()
                : OffsetDateTime.now(ZoneId.of("Asia/Shanghai")).plusSeconds(outcome.expiresInSeconds());
    }

    /**
     * 条件写入轮换后的材料（CAS 比对<b>行内当前密文</b>）：返回是否本次写入胜出。
     * <p>比对基准取读到的密文字节本体而非重加密结果——AES-GCM 每次加密的 IV 不同，
     * 重加密必然产生不同字节，用它比对会恒不匹配。</p>
     * <p>整体重加密（访问令牌 / 刷新令牌 / 到期时间同批落下）保证「refresh token 轮换被原子保存」。</p>
     */
    private boolean persistIfUnchanged(VaultCredential credential, VaultCredentialMaterial rotated,
                                       OffsetDateTime expiresAt) {
        byte[] ciphertext = cipherPort.encrypt(VaultCredentialMaterialConvert.INSTANCE.toEnvelopeJson(rotated));
        VaultCredential updated = credential.withUpdated(ciphertext, expiresAt, credential.metadata());
        Boolean applied = transactionTemplate.execute(status -> credentialRepository.updateIfCipherUnchanged(
                updated, credential.ciphertext()));
        if (applied == null || !applied) {
            log.debug("凭证刷新轮换写入未胜出（并发接管者已写入），不覆盖其结果: credentialId={}",
                    credential.credentialId());
            return false;
        }
        return true;
    }

    /**
     * 重读已持久化材料（并发接管者 / 本实例 CAS 落败时的权威值）；
     * 重读失败或解析失败回落既有材料，绝不因对账失败而丢令牌。
     */
    private VaultCredentialMaterial rereadMaterial(VaultCredential credential, VaultCredentialMaterial fallback) {
        try {
            return credentialRepository.findByVaultIdAndCredentialId(credential.vaultId(), credential.credentialId())
                    .map(fresh -> VaultCredentialMaterialConvert.INSTANCE.parse(
                            cipherPort.decrypt(fresh.ciphertext())))
                    .orElse(fallback);
        } catch (RuntimeException e) {
            log.debug("凭证材料重读失败，沿用既有材料: credentialId={}", credential.credentialId());
            return fallback;
        }
    }
}