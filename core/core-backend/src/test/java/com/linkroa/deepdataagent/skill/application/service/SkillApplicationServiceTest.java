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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link SkillApplicationService} 技能管理面用例编排单测（7.4）。
 * <p>覆盖：multipart 创建（首版随建 / 展示名裁决）、epoch 版本键发版（跨版本名称一致 /
 * 版本键分配 / 对象写入顺序与失败上抛）、游标列表（行位点解析 / before 方向翻转）、
 * 游标版本列表（版本键字典序切片）、版本详情 / 内容读取（latest 兜底 / 已删版本不可读）、
 * 版本删除（绑定 409 / latest 重算 / 全部删除置 null）、技能删除引用约束。</p>
 */
@ExtendWith(MockitoExtension.class)
class SkillApplicationServiceTest {

    /** 合法 64 位 hex 校验值夹具。 */
    private static final String SHA = "a".repeat(64);

    /** 版本台账时间夹具。 */
    private static final OffsetDateTime TS = OffsetDateTime.parse("2026-08-20T10:00:00+08:00");

    /* 等长逐位递增的 epoch 微秒版本键（字典序与时间序一致） */
    private static final String V5 = "1759178010641130";
    private static final String V4 = "1759178010641129";
    private static final String V3 = "1759178010641128";
    private static final String V2 = "1759178010641127";
    private static final String V1 = "1759178010641126";
    /** 台账中不存在的版本键（低于全部台账键）。 */
    private static final String ABSENT = "1";

    @Mock private SkillAssetRepository skillAssetRepository;
    @Mock private SkillAssetPort skillAssetPort;
    @Mock private AgentReferenceApi agentReferenceApi;
    @Mock private SkillPackageParser skillPackageParser;
    @Mock private TransactionTemplate transactionTemplate;

    private SkillApplicationService service;

    @BeforeEach
    void setUp() {
        AuthContext.setUserId(1L);
        service = new SkillApplicationService();
        ReflectionTestUtils.setField(service, "skillAssetRepository", skillAssetRepository);
        ReflectionTestUtils.setField(service, "skillAssetPort", skillAssetPort);
        ReflectionTestUtils.setField(service, "agentReferenceApi", agentReferenceApi);
        ReflectionTestUtils.setField(service, "skillPackageParser", skillPackageParser);
        ReflectionTestUtils.setField(service, "transactionTemplate", transactionTemplate);
        stubTransactionDirect();
    }

    @AfterEach
    void clearAuth() {
        AuthContext.clear();
    }

    /** TransactionTemplate 直通执行（不启用真实事务；lenient 适配不走事务分支的查询用例）。 */
    private void stubTransactionDirect() {
        lenient().when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
        lenient().doAnswer(invocation -> {
            java.util.function.Consumer<TransactionStatus> callback = invocation.getArgument(0);
            callback.accept(mock(TransactionStatus.class));
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
    }

    // ---------- 创建（multipart 首版随建） ----------

    @Test
    void should_writeFirstVersionAndDisk_when_createSkill_given_zipPackage() {
        // given（展示名缺省回落 zip 文件名去后缀；首版键为 epoch 微秒串）
        List<MultipartFile> files = zipFiles();
        when(skillPackageParser.parse(files)).thenReturn(skillPackage());
        when(skillPackageParser.suggestedDisplayTitle(files)).thenReturn("导入技能");
        when(skillAssetRepository.save(any(), any())).thenAnswer(invocation -> {
            SkillAsset asset = invocation.getArgument(0);
            return new SkillAsset(9L, asset.skillId(), asset.displayTitle(), asset.source(),
                    asset.latestVersion(), asset.metadata(), asset.ownerId(), TS, TS);
        });

        // when
        SkillAsset saved = service.createSkill(new CreateSkillCommand(files, null));

        // then（壳 + 首版台账 + 磁盘内容；版本键即 latest_version 指针）
        assertEquals(SkillSource.CUSTOM, saved.source());
        assertEquals("导入技能", saved.displayTitle());
        assertTrue(saved.skillId().startsWith("skill_"));
        assertTrue(saved.latestVersion().chars().allMatch(Character::isDigit));
        ArgumentCaptor<SkillVersion> versionCaptor = ArgumentCaptor.forClass(SkillVersion.class);
        verify(skillAssetRepository).save(any(SkillAsset.class), versionCaptor.capture());
        SkillVersion version = versionCaptor.getValue();
        assertEquals(saved.skillId(), version.skillId());
        assertEquals(saved.latestVersion(), version.version());
        assertEquals("code-review", version.name());
        assertEquals("code-review", version.directory());
        assertEquals(64, version.contentSha256().length());
        assertEquals(Map.of("references/a.md", 12L), version.resources());
        verify(skillAssetPort).write(eq(saved.skillId()), eq(saved.latestVersion()), any(SkillContent.class));
    }

    @Test
    void should_preferExplicitTitle_when_createSkill_given_displayTitle() {
        // given（显式展示名优先，不再推导 zip 文件名）
        List<MultipartFile> files = zipFiles();
        when(skillPackageParser.parse(files)).thenReturn(skillPackage());
        when(skillAssetRepository.save(any(), any())).thenAnswer(invocation -> {
            SkillAsset asset = invocation.getArgument(0);
            return new SkillAsset(9L, asset.skillId(), asset.displayTitle(), asset.source(),
                    asset.latestVersion(), asset.metadata(), asset.ownerId(), TS, TS);
        });

        // when
        SkillAsset saved = service.createSkill(new CreateSkillCommand(files, "显式展示名"));

        // then
        assertEquals("显式展示名", saved.displayTitle());
        verify(skillPackageParser, never()).suggestedDisplayTitle(any());
    }

    @Test
    void should_throwIllegalArgument_when_createSkill_given_oversizedDisplayTitle() {
        // given（展示名超长在解析后立即拒绝）
        List<MultipartFile> files = zipFiles();
        when(skillPackageParser.parse(files)).thenReturn(skillPackage());

        // when // then
        assertThrows(IllegalArgumentException.class,
                () -> service.createSkill(new CreateSkillCommand(files, "x".repeat(256))));
        verify(skillAssetRepository, never()).save(any(), any());
    }

    // ---------- 发版（epoch 版本键） ----------

    @Test
    void should_allocateEpochKeyAndWriteDisk_when_createVersion_given_consistentName() {
        // given（首版 name=code-review，发版包一致 → 新键晚于已知最新键）
        List<MultipartFile> files = zipFiles();
        when(skillAssetRepository.findBySkillId("skill_x")).thenReturn(Optional.of(asset("skill_x", V5)));
        when(skillPackageParser.parse(files)).thenReturn(skillPackage());
        when(skillAssetRepository.findFirstVersion("skill_x")).thenReturn(Optional.of(version("skill_x", V1)));
        when(skillAssetRepository.findLatestVersion("skill_x")).thenReturn(Optional.of(version("skill_x", V5)));
        when(skillAssetRepository.findVersion(eq("skill_x"), anyString())).thenReturn(Optional.empty());
        when(skillAssetRepository.appendVersion(any(), any())).thenAnswer(invocation -> invocation.getArgument(0));

        // when
        SkillVersion created = service.createVersion(new CreateSkillVersionCommand("skill_x", files));

        // then（新版本键为 digit 串且字典序晚于已知最新键；壳指针推进到新键）
        assertTrue(created.version().chars().allMatch(Character::isDigit));
        assertTrue(created.version().compareTo(V5) > 0);
        ArgumentCaptor<SkillAsset> assetCaptor = ArgumentCaptor.forClass(SkillAsset.class);
        verify(skillAssetRepository).appendVersion(assetCaptor.capture(), eq(created));
        assertEquals(created.version(), assetCaptor.getValue().latestVersion());
        // 磁盘写入携带解析后的内容（byte[] 资源按内容比对，record 相等性不适用）
        ArgumentCaptor<SkillContent> contentCaptor = ArgumentCaptor.forClass(SkillContent.class);
        verify(skillAssetPort).write(eq("skill_x"), eq(created.version()), contentCaptor.capture());
        assertEquals("# 正文", contentCaptor.getValue().markdown());
        assertArrayEquals("引用正文".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                contentCaptor.getValue().resources().get("references/a.md"));
    }

    @Test
    void should_throwIllegalArgument_when_createVersion_given_nameMismatch() {
        // given（发版包 frontmatter name 与首版不一致）
        List<MultipartFile> files = zipFiles();
        when(skillAssetRepository.findBySkillId("skill_x")).thenReturn(Optional.of(asset("skill_x", V5)));
        when(skillPackageParser.parse(files)).thenReturn(new SkillPackage("other-name", "描述",
                new SkillContent("# 正文", Map.of())));
        when(skillAssetRepository.findFirstVersion("skill_x")).thenReturn(Optional.of(version("skill_x", V1)));

        // when // then（跨版本名称一致红线：拒绝且不落任何台账 / 磁盘）
        assertThrows(IllegalArgumentException.class,
                () -> service.createVersion(new CreateSkillVersionCommand("skill_x", files)));
        verify(skillAssetRepository, never()).appendVersion(any(), any());
        verify(skillAssetPort, never()).write(anyString(), anyString(), any());
    }

    @Test
    void should_returnNotFound_when_createVersion_given_foreignSkill() {
        // given（技能不存在或非本人 owner → 404，不泄露存在性）
        when(skillAssetRepository.findBySkillId("skill_ghost")).thenReturn(Optional.empty());

        // when // then
        assertThrows(ResourceNotFoundException.class,
                () -> service.createVersion(new CreateSkillVersionCommand("skill_ghost", zipFiles())));
    }

    @Test
    void should_reserveLedgerBeforeObjectWriteAndPropagate_when_createVersion_given_objectWriteFails() {
        // given（台账追加成功，但对象写入阶段失败）
        List<MultipartFile> files = zipFiles();
        when(skillAssetRepository.findBySkillId("skill_x")).thenReturn(Optional.of(asset("skill_x", V5)));
        when(skillPackageParser.parse(files)).thenReturn(skillPackage());
        when(skillAssetRepository.findFirstVersion("skill_x")).thenReturn(Optional.of(version("skill_x", V1)));
        when(skillAssetRepository.findLatestVersion("skill_x")).thenReturn(Optional.of(version("skill_x", V5)));
        when(skillAssetRepository.findVersion(eq("skill_x"), anyString())).thenReturn(Optional.empty());
        when(skillAssetRepository.appendVersion(any(), any())).thenAnswer(invocation -> invocation.getArgument(0));
        doThrow(new RuntimeException("对象存储不可用"))
                .when(skillAssetPort).write(eq("skill_x"), anyString(), any());

        // when // then（写对象失败原样上抛，事务由 Spring 回滚台账）
        assertThrows(RuntimeException.class,
                () -> service.createVersion(new CreateSkillVersionCommand("skill_x", files)));

        // then（顺序严格：先台账抢号、后对象写入；半成品前缀由同键重试与启动对账收敛）
        InOrder order = inOrder(skillAssetRepository, skillAssetPort);
        order.verify(skillAssetRepository).appendVersion(any(), any());
        order.verify(skillAssetPort).write(eq("skill_x"), anyString(), any());
    }

    // ---------- 游标列表 ----------

    @Test
    void should_resolveCursorRowPosition_when_listSkills_given_afterIdCursor() {
        // given（after_id 解析为游标行位点；limit+1 探针裁切）
        SkillAsset cursorRow = asset(9L, "skill_cursor", V5, TS);
        when(skillAssetRepository.findBySkillId("skill_cursor")).thenReturn(Optional.of(cursorRow));
        List<SkillAsset> rows = List.of(asset("skill_a", V5), asset("skill_b", V5), asset("skill_c", V5));
        when(skillAssetRepository.findByCursor(eq(1L), any(SkillListFilter.class), eq(3))).thenReturn(rows);

        // when
        CursorPage<SkillAsset> page = service.listSkills(new ListSkillsQuery(1L, SkillSource.CUSTOM, "技能",
                new CursorPageParams(2, "skill_cursor", null)));

        // then（降序取前两、has_more=true；游标位点与过滤词汇透传仓储）
        assertEquals(2, page.data().size());
        assertTrue(page.hasMore());
        assertEquals("skill_a", page.firstId());
        assertEquals("skill_b", page.lastId());
        ArgumentCaptor<SkillListFilter> filterCaptor = ArgumentCaptor.forClass(SkillListFilter.class);
        verify(skillAssetRepository).findByCursor(eq(1L), filterCaptor.capture(), eq(3));
        assertEquals(TS, filterCaptor.getValue().cursorCreatedAt());
        assertEquals(9L, filterCaptor.getValue().cursorRowId());
        assertFalse(filterCaptor.getValue().reverse());
        assertEquals(SkillSource.CUSTOM, filterCaptor.getValue().source());
        assertEquals("技能", filterCaptor.getValue().keyword());
    }

    @Test
    void should_returnNotFound_when_listSkills_given_unknownCursorSkill() {
        // given（游标技能不存在 / 非本人 → 404）
        when(skillAssetRepository.findBySkillId("skill_ghost")).thenReturn(Optional.empty());

        // when // then
        assertThrows(ResourceNotFoundException.class, () -> service.listSkills(new ListSkillsQuery(1L, null, null,
                new CursorPageParams(20, "skill_ghost", null))));
    }

    @Test
    void should_flipBackToDescending_when_listSkills_given_beforeIdCursor() {
        // given（before 方向：仓储升序读取更新侧，应用层翻转回降序）
        SkillAsset cursorRow = asset(9L, "skill_cursor", V5, TS);
        when(skillAssetRepository.findBySkillId("skill_cursor")).thenReturn(Optional.of(cursorRow));
        SkillAsset older = asset(5L, "skill_old", V5, TS.minusDays(1));
        SkillAsset newer = asset(6L, "skill_new", V5, TS.plusDays(1));
        when(skillAssetRepository.findByCursor(eq(1L), any(SkillListFilter.class), eq(21)))
                .thenReturn(List.of(older, newer));

        // when
        CursorPage<SkillAsset> page = service.listSkills(new ListSkillsQuery(1L, null, null,
                new CursorPageParams(20, null, "skill_cursor")));

        // then（翻转回降序：新行在前）
        assertEquals(List.of("skill_new", "skill_old"), page.data().stream().map(SkillAsset::skillId).toList());
        ArgumentCaptor<SkillListFilter> filterCaptor = ArgumentCaptor.forClass(SkillListFilter.class);
        verify(skillAssetRepository).findByCursor(eq(1L), filterCaptor.capture(), eq(21));
        assertTrue(filterCaptor.getValue().reverse());
    }

    // ---------- 游标版本列表（版本键字典序） ----------

    @Test
    void should_sliceOlderSide_when_listVersions_given_afterVersionKey() {
        // given（版本台账 [V5,V4,V3,V2,V1] 降序；after_id=V3 → 更旧侧 [V2,V1]）
        when(skillAssetRepository.findBySkillId("skill_x")).thenReturn(Optional.of(asset("skill_x", V5)));
        when(skillAssetRepository.listVersions("skill_x")).thenReturn(ledger());

        // when
        CursorPage<SkillVersion> page = service.listVersions("skill_x", new CursorPageParams(2, V3, null));

        // then（游标衔接无重叠无遗漏：first/last 为版本键）
        assertEquals(List.of(V2, V1), page.data().stream().map(SkillVersion::version).toList());
        assertEquals(V2, page.firstId());
        assertEquals(V1, page.lastId());
        assertFalse(page.hasMore());
    }

    @Test
    void should_sliceNewerSideClosestToCursor_when_listVersions_given_beforeVersionKey() {
        // given（before_id=V4 → 更新侧 [V5]；limit 2 取贴近游标的至多 2 条）
        when(skillAssetRepository.findBySkillId("skill_x")).thenReturn(Optional.of(asset("skill_x", V5)));
        when(skillAssetRepository.listVersions("skill_x")).thenReturn(ledger());

        // when
        CursorPage<SkillVersion> page = service.listVersions("skill_x", new CursorPageParams(2, null, V4));

        // then
        assertEquals(List.of(V5), page.data().stream().map(SkillVersion::version).toList());
        assertEquals(V5, page.lastId());
        assertFalse(page.hasMore());
    }

    @Test
    void should_probeHasMore_when_listVersions_given_firstPageSmallerThanLedger() {
        // given（首页 limit 2 探针：台账 5 条 → has_more=true 且裁切）
        when(skillAssetRepository.findBySkillId("skill_x")).thenReturn(Optional.of(asset("skill_x", V5)));
        when(skillAssetRepository.listVersions("skill_x")).thenReturn(ledger());

        // when
        CursorPage<SkillVersion> page = service.listVersions("skill_x", new CursorPageParams(2, null, null));

        // then
        assertEquals(List.of(V5, V4), page.data().stream().map(SkillVersion::version).toList());
        assertTrue(page.hasMore());
    }

    @Test
    void should_returnEmptySlice_when_listVersions_given_unmatchedVersionKey() {
        // given（游标键不在台账内：按位置语义自然裁切，不抛错）
        when(skillAssetRepository.findBySkillId("skill_x")).thenReturn(Optional.of(asset("skill_x", V5)));
        when(skillAssetRepository.listVersions("skill_x")).thenReturn(ledger());

        // when
        CursorPage<SkillVersion> page = service.listVersions("skill_x", new CursorPageParams(2, ABSENT, null));

        // then（无任何版本键小于该游标）
        assertTrue(page.data().isEmpty());
        assertFalse(page.hasMore());
    }

    // ---------- 版本详情 / 内容读取 ----------

    @Test
    void should_returnMetadata_when_getVersion_given_existingVersion() {
        // given
        when(skillAssetRepository.findBySkillId("skill_x")).thenReturn(Optional.of(asset("skill_x", V5)));
        when(skillAssetRepository.findVersion("skill_x", V3)).thenReturn(Optional.of(version("skill_x", V3)));

        // when
        SkillVersion found = service.getVersion("skill_x", V3);

        // then
        assertEquals(V3, found.version());
        assertEquals(SHA, found.contentSha256());
    }

    @Test
    void should_returnNotFound_when_getVersion_given_missingVersion() {
        // given
        when(skillAssetRepository.findBySkillId("skill_x")).thenReturn(Optional.of(asset("skill_x", V5)));
        when(skillAssetRepository.findVersion("skill_x", V1)).thenReturn(Optional.empty());

        // when // then
        assertThrows(ResourceNotFoundException.class, () -> service.getVersion("skill_x", V1));
    }

    @Test
    void should_readDisk_when_getContent_given_explicitVersion() {
        // given
        when(skillAssetRepository.findBySkillId("skill_x")).thenReturn(Optional.of(asset("skill_x", V5)));
        when(skillAssetRepository.findVersion("skill_x", V3)).thenReturn(Optional.of(version("skill_x", V3)));
        when(skillAssetPort.read("skill_x", V3)).thenReturn(new SkillContent("# v3 内容", Map.of()));

        // when // then
        SkillContent content = service.getContent("skill_x", V3);
        assertEquals("# v3 内容", content.markdown());
    }

    @Test
    void should_readLatest_when_getContent_given_blankVersion() {
        // given（version 为空 → 解析壳的 latest_version 指针）
        when(skillAssetRepository.findBySkillId("skill_x")).thenReturn(Optional.of(asset("skill_x", V5)));
        when(skillAssetRepository.findVersion("skill_x", V5)).thenReturn(Optional.of(version("skill_x", V5)));
        when(skillAssetPort.read("skill_x", V5)).thenReturn(new SkillContent("# 最新内容", Map.of()));

        // when // then
        assertEquals("# 最新内容", service.getContent("skill_x", null).markdown());
    }

    @Test
    void should_returnNotFound_when_getContent_given_noVersionAtAll() {
        // given（技能已无任何版本：latest_version 为 null）
        when(skillAssetRepository.findBySkillId("skill_x")).thenReturn(Optional.of(asset("skill_x", null)));

        // when // then
        assertThrows(ResourceNotFoundException.class, () -> service.getContent("skill_x", null));
        verify(skillAssetPort, never()).read(anyString(), anyString());
    }

    @Test
    void should_returnNotFound_when_getContent_given_deletedVersion() {
        // given（版本已逻辑删除：台账无行，磁盘残留内容不得继续可读）
        when(skillAssetRepository.findBySkillId("skill_x")).thenReturn(Optional.of(asset("skill_x", V5)));
        when(skillAssetRepository.findVersion("skill_x", V3)).thenReturn(Optional.empty());

        // when // then
        assertThrows(ResourceNotFoundException.class, () -> service.getContent("skill_x", V3));
        verify(skillAssetPort, never()).read(anyString(), anyString());
    }

    // ---------- delete-version 门禁 ----------

    @Test
    void should_returnNotFound_when_deleteVersion_given_missingVersion() {
        // given
        when(skillAssetRepository.findBySkillId("skill_x")).thenReturn(Optional.of(asset("skill_x", V5)));
        when(skillAssetRepository.findVersion("skill_x", V1)).thenReturn(Optional.empty());

        // when // then
        assertThrows(ResourceNotFoundException.class, () -> service.deleteVersion("skill_x", V1));
    }

    @Test
    void should_returnConflict_when_deleteVersion_given_versionStillBound() {
        // given（该版本仍被未删除 Agent 版本绑定 → 409）
        when(skillAssetRepository.findBySkillId("skill_x")).thenReturn(Optional.of(asset("skill_x", V5)));
        when(skillAssetRepository.findVersion("skill_x", V3)).thenReturn(Optional.of(version("skill_x", V3)));
        when(agentReferenceApi.countSkillVersionBindings("skill_x", V3)).thenReturn(1L);

        // when // then
        assertThrows(ResourceConflictException.class, () -> service.deleteVersion("skill_x", V3));
        verify(skillAssetRepository, never()).deleteVersion(anyString(), anyString());
    }

    @Test
    void should_recomputeLatest_when_deleteVersion_given_headVersionUnbound() {
        // given（删除头版本 V5：剩余 [V4,V3,V2,V1] → latest 重算为 V4）
        when(skillAssetRepository.findBySkillId("skill_x")).thenReturn(Optional.of(asset("skill_x", V5)));
        when(skillAssetRepository.findVersion("skill_x", V5)).thenReturn(Optional.of(version("skill_x", V5)));
        when(agentReferenceApi.countSkillVersionBindings("skill_x", V5)).thenReturn(0L);
        when(skillAssetRepository.listVersions("skill_x")).thenReturn(ledger());

        // when
        service.deleteVersion("skill_x", V5);

        // then（台账逻辑删除 + latest_version 重算指向剩余最新版本）
        verify(skillAssetRepository).deleteVersion("skill_x", V5);
        ArgumentCaptor<SkillAsset> captor = ArgumentCaptor.forClass(SkillAsset.class);
        verify(skillAssetRepository).updateLatestVersion(captor.capture());
        assertEquals(V4, captor.getValue().latestVersion());
    }

    @Test
    void should_recomputeLatest_when_deleteVersion_given_middleVersionUnbound() {
        // given（删除中间版本 V3：剩余首行仍为 V5）
        when(skillAssetRepository.findBySkillId("skill_x")).thenReturn(Optional.of(asset("skill_x", V5)));
        when(skillAssetRepository.findVersion("skill_x", V3)).thenReturn(Optional.of(version("skill_x", V3)));
        when(agentReferenceApi.countSkillVersionBindings("skill_x", V3)).thenReturn(0L);
        when(skillAssetRepository.listVersions("skill_x")).thenReturn(ledger());

        // when
        service.deleteVersion("skill_x", V3);

        // then
        verify(skillAssetRepository).deleteVersion("skill_x", V3);
        ArgumentCaptor<SkillAsset> captor = ArgumentCaptor.forClass(SkillAsset.class);
        verify(skillAssetRepository).updateLatestVersion(captor.capture());
        assertEquals(V5, captor.getValue().latestVersion());
    }

    @Test
    void should_nullLatest_when_deleteVersion_given_lastRemainingVersion() {
        // given（删除最后一个版本：剩余为空 → latest_version 置 null）
        when(skillAssetRepository.findBySkillId("skill_x")).thenReturn(Optional.of(asset("skill_x", V1)));
        when(skillAssetRepository.findVersion("skill_x", V1)).thenReturn(Optional.of(version("skill_x", V1)));
        when(agentReferenceApi.countSkillVersionBindings("skill_x", V1)).thenReturn(0L);
        when(skillAssetRepository.listVersions("skill_x")).thenReturn(List.of(version("skill_x", V1)));

        // when
        service.deleteVersion("skill_x", V1);

        // then
        ArgumentCaptor<SkillAsset> captor = ArgumentCaptor.forClass(SkillAsset.class);
        verify(skillAssetRepository).updateLatestVersion(captor.capture());
        assertNull(captor.getValue().latestVersion());
    }

    // ---------- 技能删除引用约束 ----------

    @Test
    void should_returnConflict_when_deleteSkill_given_skillStillBound() {
        // given（技能级绑定计数拒绝）
        when(skillAssetRepository.findBySkillId("skill_x")).thenReturn(Optional.of(asset("skill_x", V5)));
        when(agentReferenceApi.countSkillBindings("skill_x")).thenReturn(1L);

        // when // then
        assertThrows(ResourceConflictException.class, () -> service.deleteSkill("skill_x"));
        verify(skillAssetRepository, never()).deleteBySkillId(anyString());
    }

    @Test
    void should_deleteBySkillId_when_deleteSkill_given_unboundSkill() {
        // given
        when(skillAssetRepository.findBySkillId("skill_x")).thenReturn(Optional.of(asset("skill_x", V5)));
        when(agentReferenceApi.countSkillBindings("skill_x")).thenReturn(0L);

        // when
        service.deleteSkill("skill_x");

        // then（逻辑删除壳，历史版本数据保留）
        verify(skillAssetRepository).deleteBySkillId("skill_x");
    }

    // ---------- 夹具 ----------

    /** 单 zip multipart 上传夹具。 */
    private static List<MultipartFile> zipFiles() {
        return List.of(new MockMultipartFile("files", "code-review.zip", "application/zip", new byte[]{'P', 'K', 3}));
    }

    /** 解析后的技能包夹具（name=code-review，含一份资源）。 */
    private static SkillPackage skillPackage() {
        return new SkillPackage("code-review", "描述",
                new SkillContent("# 正文", Map.of("references/a.md",
                        "引用正文".getBytes(java.nio.charset.StandardCharsets.UTF_8))));
    }

    /** 版本台账夹具 [V5,V4,V3,V2,V1]（版本键倒序，最新在前）。 */
    private static List<SkillVersion> ledger() {
        return List.of(version("skill_x", V5), version("skill_x", V4), version("skill_x", V3),
                version("skill_x", V2), version("skill_x", V1));
    }

    private static SkillAsset asset(String skillId, String latestVersion) {
        return asset(9L, skillId, latestVersion, TS);
    }

    private static SkillAsset asset(Long id, String skillId, String latestVersion, OffsetDateTime createdAt) {
        return new SkillAsset(id, skillId, "技能", SkillSource.CUSTOM, latestVersion, Map.of(), 1L,
                createdAt, createdAt);
    }

    private static SkillVersion version(String skillId, String key) {
        return SkillVersion.restore(SkillVersion.VERSION_ID_PREFIX + key, skillId, key, "code-review", "描述",
                "code-review", SHA, 10L, Map.of(), TS);
    }
}