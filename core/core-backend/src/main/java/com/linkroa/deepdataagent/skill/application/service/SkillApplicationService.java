package com.linkroa.deepdataagent.skill.application.service;

import com.linkroa.deepdataagent.agent.api.AgentReferenceApi;
import com.linkroa.deepdataagent.shared.exception.ResourceConflictException;
import com.linkroa.deepdataagent.shared.exception.ResourceNotFoundException;
import com.linkroa.deepdataagent.shared.result.CursorPage;
import com.linkroa.deepdataagent.shared.result.CursorPageParams;
import com.linkroa.deepdataagent.shared.security.AuthContext;
import com.linkroa.deepdataagent.skill.application.command.CreateSkillCommand;
import com.linkroa.deepdataagent.skill.application.command.CreateSkillVersionCommand;
import com.linkroa.deepdataagent.skill.application.port.SkillAssetPort;
import com.linkroa.deepdataagent.skill.application.query.ListSkillsQuery;
import com.linkroa.deepdataagent.skill.domain.model.SkillAsset;
import com.linkroa.deepdataagent.skill.domain.model.SkillContent;
import com.linkroa.deepdataagent.skill.domain.model.SkillListFilter;
import com.linkroa.deepdataagent.skill.domain.model.SkillPackage;
import com.linkroa.deepdataagent.skill.domain.model.SkillVersion;
import com.linkroa.deepdataagent.skill.domain.model.enums.SkillSource;
import com.linkroa.deepdataagent.skill.domain.repository.SkillAssetRepository;
import jakarta.annotation.Resource;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 技能资产应用服务：multipart 创建（首版随建）/ 发版（epoch 微秒键）/ 游标列表与版本查询 /
 * 版本内容读取 / 版本删除 / 技能删除。
 * <p>模型要点：</p>
 * <ul>
 *   <li><b>创建即首版</b>：multipart 技能包经 {@link SkillPackageParser} 解析后，单事务落
 *       壳对象 + 首个不可变版本（{@code latest_version} 指向该版本键）；展示名缺省取 zip 文件名
 *       （去 {@code .zip}）再回落 frontmatter name，创建后不可修改；</li>
 *   <li><b>epoch 微秒版本键</b>：版本键为创建时刻 epoch 微秒字符串（同一技能内唯一，冲突自旋 +1），
 *       字典序与时间序一致；发版必须保持 frontmatter name 与首版一致（否则 400）；</li>
 *   <li><b>引用保护</b>：技能与版本删除均经 {@link AgentReferenceApi} 反查
 *       {@code agent_version.skills_json} 绑定，仍被绑定引用时不可删除（409）；</li>
 *   <li><b>latest 重算</b>：版本逻辑删除后重算 {@code latest_version} 指向剩余最新版本，
 *       全部版本删除后为 {@code null}。</li>
 * </ul>
 * <p>写操作事务边界经 {@link TransactionTemplate} 控制；磁盘内容写入在事务内、台账行落库之后
 * （版本键已由唯一约束裁决，不会触碰他人版本前缀）。</p>
 */
@Service
public class SkillApplicationService {

    /** 版本键分配自旋上限（同技能内 epoch 微秒碰撞时逐个 +1 试探）。 */
    private static final int MAX_VERSION_ALLOCATION_ATTEMPTS = 1000;

    @Resource
    private SkillAssetRepository skillAssetRepository;
    @Resource
    private SkillAssetPort skillAssetPort;
    @Resource
    private AgentReferenceApi agentReferenceApi;
    @Resource
    private SkillPackageParser skillPackageParser;
    @Resource
    private TransactionTemplate transactionTemplate;

    /**
     * 创建技能（multipart 技能包）：解析包 → 生成 {@code skill_} ID 与首版 epoch 版本键 →
     * 单事务落壳对象 + 首版台账 + 磁盘内容。
     *
     * @param command 创建命令（multipart files + 可选展示名）
     * @return 落库后的技能壳（{@code latestVersion} 为首版版本键）
     * @throws IllegalArgumentException 包结构 / frontmatter 非法或展示名超长
     * @throws com.linkroa.deepdataagent.shared.exception.RequestTooLargeException zip 压缩包超 50MB
     */
    public SkillAsset createSkill(CreateSkillCommand command) {
        Long ownerId = AuthContext.requireUserId();
        SkillPackage skillPackage = skillPackageParser.parse(command.files());
        String displayTitle = resolveDisplayTitle(command, skillPackage);
        String skillId = SkillAsset.SKILL_ID_PREFIX + UUID.randomUUID();
        String versionKey = Long.toString(epochMicros());
        SkillVersion version = buildVersion(skillId, versionKey, skillPackage);
        SkillAsset asset = SkillAsset.create(skillId, displayTitle, SkillSource.CUSTOM, Map.of(), ownerId);
        return transactionTemplate.execute(status -> {
            SkillAsset saved = skillAssetRepository.save(asset.withLatestVersion(versionKey), version);
            skillAssetPort.write(skillId, versionKey, skillPackage.content());
            return saved;
        });
    }

    /**
     * 发版（multipart 技能包）：跨版本 frontmatter name 必须与首版一致 →
     * 分配新的 epoch 微秒版本键 → 单事务落新版本台账 + 推进 {@code latest_version} + 磁盘内容。
     *
     * @param command 发版命令（技能 ID + multipart files）
     * @return 新版本元数据
     * @throws ResourceNotFoundException 技能不存在或非本人 owner
     * @throws IllegalArgumentException  包结构 / frontmatter 非法，或跨版本名称不一致
     * @throws com.linkroa.deepdataagent.shared.exception.RequestTooLargeException zip 压缩包超 50MB
     */
    public SkillVersion createVersion(CreateSkillVersionCommand command) {
        SkillAsset asset = requireOwned(command.skillId());
        SkillPackage skillPackage = skillPackageParser.parse(command.files());
        requireConsistentName(asset.skillId(), skillPackage.name());
        String versionKey = allocateVersionKey(asset.skillId());
        SkillVersion version = buildVersion(asset.skillId(), versionKey, skillPackage);
        return transactionTemplate.execute(status -> {
            skillAssetRepository.appendVersion(asset.withLatestVersion(versionKey), version);
            skillAssetPort.write(asset.skillId(), versionKey, skillPackage.content());
            return version;
        });
    }

    /**
     * 游标分页列出技能（创建时间降序 keyset 行值比较）。
     * <p>游标 after_id/before_id 解析为行位点（created_at + 数据库主键），技能不存在 /
     * 非本人 → 404（与详情同语义）；limit+1 探针判 has_more，before 方向升序读取后
     * 由应用层翻转回降序。</p>
     *
     * @param query 列表查询（owner / 来源 / 展示名关键字 / 游标）
     * @return 游标分页结果（first_id/last_id 为技能业务 ID skill_）
     */
    public CursorPage<SkillAsset> listSkills(ListSkillsQuery query) {
        CursorPageParams cursor = query.cursor();
        OffsetDateTime cursorCreatedAt = null;
        Long cursorRowId = null;
        boolean reverse = false;
        if (cursor.afterId() != null) {
            SkillAsset at = requireOwned(cursor.afterId());
            cursorCreatedAt = at.createdAt();
            cursorRowId = at.id();
        } else if (cursor.beforeId() != null) {
            SkillAsset at = requireOwned(cursor.beforeId());
            cursorCreatedAt = at.createdAt();
            cursorRowId = at.id();
            reverse = true;
        }
        SkillListFilter filter = new SkillListFilter(query.source(), query.keyword(),
                cursorCreatedAt, cursorRowId, reverse);
        List<SkillAsset> rows = skillAssetRepository.findByCursor(query.ownerId(), filter, cursor.limit() + 1);
        boolean hasMore = rows.size() > cursor.limit();
        List<SkillAsset> data = rows.subList(0, Math.min(rows.size(), cursor.limit()));
        if (reverse) {
            data = List.copyOf(data).reversed();
        }
        return CursorPage.of(data, hasMore, SkillAsset::skillId);
    }

    /**
     * 查询技能详情（owner 隔离）。
     *
     * @param skillId 技能业务 ID
     * @return 技能壳
     * @throws ResourceNotFoundException 技能不存在或非本人 owner
     */
    public SkillAsset getSkill(String skillId) {
        return requireOwned(skillId);
    }

    /**
     * 游标分页列出版本（最新在前；游标值为版本键，越界 / 失配按位置语义自然裁切）。
     *
     * @param skillId 技能业务 ID
     * @param cursor  游标分页参数（limit / after_id / before_id，游标值为版本键）
     * @return 游标分页结果（first_id/last_id 为版本键）
     * @throws ResourceNotFoundException 技能不存在或非本人 owner
     */
    public CursorPage<SkillVersion> listVersions(String skillId, CursorPageParams cursor) {
        requireOwned(skillId);
        List<SkillVersion> all = skillAssetRepository.listVersions(skillId);
        int limit = cursor.limit();
        List<SkillVersion> data;
        boolean hasMore;
        if (cursor.afterId() != null) {
            String after = cursor.afterId();
            List<SkillVersion> tail = all.stream().filter(v -> v.version().compareTo(after) < 0).toList();
            hasMore = tail.size() > limit;
            data = tail.subList(0, Math.min(tail.size(), limit));
        } else if (cursor.beforeId() != null) {
            String before = cursor.beforeId();
            List<SkillVersion> head = all.stream().filter(v -> v.version().compareTo(before) > 0).toList();
            hasMore = head.size() > limit;
            data = head.subList(Math.max(0, head.size() - limit), head.size());
        } else {
            hasMore = all.size() > limit;
            data = all.subList(0, Math.min(all.size(), limit));
        }
        return CursorPage.of(data, hasMore, SkillVersion::version);
    }

    /**
     * 详情页全量版本清单（版本键倒序，最新在前；对齐详情场景「返回全部版本信息，不暴露内容全文」）。
     *
     * @param skillId 技能业务 ID
     * @return 内容版本列表
     * @throws ResourceNotFoundException 技能不存在或非本人 owner
     */
    public List<SkillVersion> listAllVersions(String skillId) {
        requireOwned(skillId);
        return skillAssetRepository.listVersions(skillId);
    }

    /**
     * 查询指定版本元数据（不暴露内容全文）。
     *
     * @param skillId 技能业务 ID
     * @param version 版本键
     * @return 内容版本元数据
     * @throws ResourceNotFoundException 技能 / 版本不存在或非本人 owner
     */
    public SkillVersion getVersion(String skillId, String version) {
        requireOwned(skillId);
        return skillAssetRepository.findVersion(skillId, version)
                .orElseThrow(() -> new ResourceNotFoundException("技能版本不存在: " + skillId + "@" + version));
    }

    /**
     * 读取技能指定版本内容（磁盘资产；{@code version} 为空表示最新版本）。
     * <p>先校验版本台账存在，已删除版本不得经磁盘残留内容继续可读。</p>
     *
     * @param skillId 技能业务 ID
     * @param version 版本键（可空 = 最新）
     * @return 技能内容
     * @throws ResourceNotFoundException 技能 / 版本 / 磁盘内容缺失，或技能已无版本
     */
    public SkillContent getContent(String skillId, String version) {
        SkillAsset asset = requireOwned(skillId);
        String resolved = StringUtils.isNotBlank(version) ? version : asset.latestVersion();
        if (resolved == null) {
            throw new ResourceNotFoundException("技能暂无内容版本: " + asset.skillId());
        }
        skillAssetRepository.findVersion(asset.skillId(), resolved)
                .orElseThrow(() -> new ResourceNotFoundException("技能版本不存在: " + asset.skillId() + "@" + resolved));
        SkillContent content = skillAssetPort.read(asset.skillId(), resolved);
        if (content == null) {
            throw new ResourceNotFoundException("技能内容缺失: " + asset.skillId() + "@" + resolved);
        }
        return content;
    }

    /**
     * 删除指定版本：仍被未删除 Agent 版本显式钉版绑定的版本不可删除（409）；
     * 逻辑删除版本台账行（磁盘内容法定保留），{@code latest_version} 重算指向剩余最新版本，
     * 全部版本删除后置 {@code null}。
     *
     * @param skillId 技能业务 ID
     * @param version 版本键
     * @throws ResourceConflictException  该版本仍被 Agent 版本绑定
     * @throws ResourceNotFoundException 技能 / 版本不存在或非本人 owner
     */
    public void deleteVersion(String skillId, String version) {
        SkillAsset asset = requireOwned(skillId);
        skillAssetRepository.findVersion(skillId, version)
                .orElseThrow(() -> new ResourceNotFoundException("技能版本不存在: " + skillId + "@" + version));
        if (agentReferenceApi.countSkillVersionBindings(skillId, version) > 0) {
            throw new ResourceConflictException("技能版本仍被 Agent 版本绑定，不可删除: " + skillId + "@" + version);
        }
        // 版本台账按版本键倒序返回，剩余首行即重算后的最新版本
        String recalculated = skillAssetRepository.listVersions(skillId).stream()
                .filter(v -> !v.version().equals(version))
                .map(SkillVersion::version)
                .findFirst()
                .orElse(null);
        transactionTemplate.executeWithoutResult(status -> {
            skillAssetRepository.deleteVersion(skillId, version);
            skillAssetRepository.updateLatestVersion(asset.withLatestVersion(recalculated));
        });
    }

    /**
     * 逻辑删除技能：仍被任一未删除 Agent 版本绑定的技能不可删除（409）。
     *
     * @param skillId 技能业务 ID
     * @throws ResourceConflictException  仍被绑定引用
     * @throws ResourceNotFoundException 技能不存在或非本人 owner
     */
    public void deleteSkill(String skillId) {
        SkillAsset asset = requireOwned(skillId);
        if (agentReferenceApi.countSkillBindings(asset.skillId()) > 0) {
            throw new ResourceConflictException("技能仍被 Agent 版本绑定，不可删除");
        }
        transactionTemplate.executeWithoutResult(status -> skillAssetRepository.deleteBySkillId(asset.skillId()));
    }

    /**
     * owner 隔离查找（不存在 / 非本人 → 404，不泄露存在性）。
     */
    private SkillAsset requireOwned(String skillId) {
        SkillAsset asset = skillAssetRepository.findBySkillId(skillId)
                .orElseThrow(() -> new ResourceNotFoundException("技能不存在"));
        if (!StringUtils.equals(asset.skillId(), skillId) || !asset.ownerId().equals(AuthContext.requireUserId())) {
            throw new ResourceNotFoundException("技能不存在");
        }
        return asset;
    }

    /**
     * 展示名裁决：显式提供即校验边界；缺省取 zip 文件名（去 {@code .zip}），
     * 再回落 frontmatter name。
     */
    private String resolveDisplayTitle(CreateSkillCommand command, SkillPackage skillPackage) {
        String provided = StringUtils.trimToNull(command.displayTitle());
        if (provided != null) {
            if (provided.length() > SkillAsset.MAX_DISPLAY_TITLE_LENGTH) {
                throw new IllegalArgumentException(
                        "技能展示名长度不能超过" + SkillAsset.MAX_DISPLAY_TITLE_LENGTH + "个字符");
            }
            return provided;
        }
        String suggested = StringUtils.trimToNull(skillPackageParser.suggestedDisplayTitle(command.files()));
        return suggested == null ? skillPackage.name() : suggested;
    }

    /** 跨版本 frontmatter name 一致性裁决（以最早版本为基准）。 */
    private void requireConsistentName(String skillId, String name) {
        skillAssetRepository.findFirstVersion(skillId)
                .filter(first -> !first.name().equals(name))
                .ifPresent(first -> {
                    throw new IllegalArgumentException(
                            "技能名称跨版本必须一致: " + first.name() + " != " + name);
                });
    }

    /**
     * 分配版本键：取当前时刻 epoch 微秒与「已知最新版本键 + 1」的较大值，逐步 +1 自旋避开
     * 同技能内已存在的版本键（数据库唯一索引为最终裁决）。
     *
     * @param skillId 技能业务 ID
     * @return 未被占用的版本键
     * @throws IllegalStateException 自旋超过上限仍冲突
     */
    private String allocateVersionKey(String skillId) {
        long base = epochMicros();
        base = Math.max(base, skillAssetRepository.findLatestVersion(skillId)
                .map(version -> parseVersionKey(version.version()) + 1)
                .orElse(base));
        for (int i = 0; i < MAX_VERSION_ALLOCATION_ATTEMPTS; i++) {
            String candidate = Long.toString(base + i);
            if (skillAssetRepository.findVersion(skillId, candidate).isEmpty()) {
                return candidate;
            }
        }
        throw new IllegalStateException("技能版本键分配冲突过多，请重试: " + skillId);
    }

    /** 由技能包构造内容版本元数据（分配版本键与版本 ID、计算包完整性校验值与大小）。 */
    private static SkillVersion buildVersion(String skillId, String versionKey, SkillPackage skillPackage) {
        SkillContent content = skillPackage.content();
        return SkillVersion.create(
                SkillVersion.VERSION_ID_PREFIX + UUID.randomUUID(),
                skillId,
                versionKey,
                skillPackage.name(),
                skillPackage.description(),
                packageSha256(content),
                packageSize(content),
                skillPackage.resourceSizes());
    }

    /** 当前时刻 epoch 微秒（服务端时钟，字典序与时间序一致）。 */
    private static long epochMicros() {
        Instant now = Instant.now();
        return now.getEpochSecond() * 1_000_000L + now.getNano() / 1_000L;
    }

    /** 版本键解析（领域不变量已保证为十进制字符串）。 */
    private static long parseVersionKey(String version) {
        return Long.parseLong(version);
    }

    /** 技能包内容整体字节长度（SKILL.md 正文 UTF-8 字节 + 全部资源原始字节）。 */
    private static long packageSize(SkillContent content) {
        long size = content.contentSize();
        for (byte[] value : content.resources().values()) {
            size += value.length;
        }
        return size;
    }

    /** 技能包内容整体 SHA-256 hex 摘要（正文 + 按键排序的资源原始字节，内部完整性手段，不进入响应）。 */
    private static String packageSha256(SkillContent content) {
        MessageDigest digest = sha256Digest();
        digest.update(content.markdown().getBytes(StandardCharsets.UTF_8));
        content.resources().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    digest.update(entry.getKey().getBytes(StandardCharsets.UTF_8));
                    digest.update((byte) 0);
                    digest.update(entry.getValue());
                });
        return HexFormat.of().formatHex(digest.digest());
    }

    /** SHA-256 摘要实例。 */
    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 摘要计算不可用", e);
        }
    }
}