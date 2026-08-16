package com.linkroa.deepdataagent.agent.application.service;

import com.linkroa.deepdataagent.agent.application.command.CreateSkillCommand;
import com.linkroa.deepdataagent.agent.application.command.PublishSkillVersionCommand;
import com.linkroa.deepdataagent.agent.application.query.ListSkillQuery;
import com.linkroa.deepdataagent.agent.domain.model.SkillResource;
import com.linkroa.deepdataagent.agent.domain.model.enums.SkillStatus;
import com.linkroa.deepdataagent.agent.domain.model.enums.SkillStorageType;
import com.linkroa.deepdataagent.agent.domain.model.enums.SkillType;
import com.linkroa.deepdataagent.agent.domain.repository.SkillContentStore;
import com.linkroa.deepdataagent.agent.domain.repository.SkillRepository;
import com.linkroa.deepdataagent.agent.infrastructure.config.SkillStorageProperties;
import com.linkroa.deepdataagent.agent.infrastructure.util.Sha256Util;
import com.linkroa.deepdataagent.shared.exception.ResourceConflictException;
import com.linkroa.deepdataagent.shared.exception.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 技能创建 / 发布版本 / 列表 / 下载 / 删除应用服务单测
 */
@ExtendWith(MockitoExtension.class)
class SkillApplicationServiceTest {

    @Mock private SkillRepository skillRepository;
    @Mock private SkillContentStore skillContentStore;
    @Mock private TransactionTemplate transactionTemplate;

    private SkillStorageProperties storageProperties;
    private SkillApplicationService service;

    @BeforeEach
    void setUp() {
        storageProperties = new SkillStorageProperties();
        storageProperties.setRoot("./data/skills");
        storageProperties.setMaxSize(1024L);
        service = new SkillApplicationService();
        ReflectionTestUtils.setField(service, "skillRepository", skillRepository);
        ReflectionTestUtils.setField(service, "skillContentStore", skillContentStore);
        ReflectionTestUtils.setField(service, "storageProperties", storageProperties);
        ReflectionTestUtils.setField(service, "transactionTemplate", transactionTemplate);
        lenient().doAnswer(invocation -> {
            TransactionCallback<Object> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        }).when(transactionTemplate).execute(any());
        lenient().doAnswer(invocation -> {
            java.util.function.Consumer<TransactionStatus> consumer = invocation.getArgument(0);
            consumer.accept(mock(TransactionStatus.class));
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
    }

    private byte[] content() {
        return "以自然语言处理为核心的工具包。".getBytes(StandardCharsets.UTF_8);
    }

    private SkillResource buildVersion(String skillId, int versionNumber, String name, byte[] content) {
        return SkillResource.restore(
                1L, skillId, versionNumber, name, "描述-" + versionNumber, SkillType.CUSTOM,
                SkillStorageType.LOCAL_FILE, skillId + "/" + versionNumber + "/" + skillId + "-" + versionNumber + ".zip",
                Sha256Util.hex(content), content.length, SkillStatus.ACTIVE,
                OffsetDateTime.now(ZoneId.of("Asia/Shanghai")),
                OffsetDateTime.now(ZoneId.of("Asia/Shanghai")), null, null);
    }

    @Test
    void should_createSkillWithVersion1_when_createSkill_given_validContent() {
        // given
        byte[] content = content();
        when(skillContentStore.put(anyString(), anyInt(), any())).thenReturn("skill-1/1/skill-1-1.zip");
        when(skillRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // when
        SkillResource created = service.createSkill(
                new CreateSkillCommand("文本分析工具", "首版", SkillType.CUSTOM, content, null));

        // then
        assertNotNull(created.skillId());
        assertEquals(1, created.versionNumber());
        assertEquals("文本分析工具", created.name());
        assertEquals(Sha256Util.hex(content), created.contentSha256());
        assertEquals(content.length, created.contentSize());
        assertEquals(SkillStatus.ACTIVE, created.status());
    }

    @Test
    void should_publishVersionIncrement_when_publishVersion_given_existingSkill() {
        // given
        String skillId = "skill-1";
        byte[] oldContent = "旧版内容".getBytes(StandardCharsets.UTF_8);
        byte[] newContent = "新版内容".getBytes(StandardCharsets.UTF_8);
        when(skillRepository.findMaxVersionForUpdate(skillId))
                .thenReturn(Optional.of(buildVersion(skillId, 1, "数据清洗", oldContent)));
        when(skillContentStore.put(anyString(), anyInt(), any())).thenReturn("skill-1/2/skill-1-2.zip");
        when(skillRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // when
        SkillResource version = service.publishVersion(
                new PublishSkillVersionCommand(skillId, "修复若干问题", newContent, null));

        // then
        assertEquals(skillId, version.skillId());
        assertEquals(2, version.versionNumber());
        assertEquals("数据清洗", version.name());
        assertEquals("修复若干问题", version.description());
        assertEquals(Sha256Util.hex(newContent), version.contentSha256());
        verify(skillRepository).findMaxVersionForUpdate(skillId);
    }

    @Test
    void should_throwNotFound_when_publishVersion_given_unknownSkill() {
        // given
        when(skillRepository.findMaxVersionForUpdate("skill-unknown")).thenReturn(Optional.empty());

        // when
        // then
        assertThrows(ResourceNotFoundException.class, () -> service.publishVersion(
                new PublishSkillVersionCommand("skill-unknown", null, content(), null)));
    }

    @Test
    void should_rejectSha256Mismatch_when_createSkill_given_wrongDeclaredSha256() {
        // given
        byte[] content = content();
        CreateSkillCommand command = new CreateSkillCommand(
                "文本分析工具", null, SkillType.CUSTOM, content, "a".repeat(64));

        // when
        // then
        assertThrows(IllegalArgumentException.class, () -> service.createSkill(command));
    }

    @Test
    void should_rejectEmptyContent_when_createSkill_given_emptyContent() {
        // given
        CreateSkillCommand command = new CreateSkillCommand(
                "文本分析工具", null, SkillType.CUSTOM, new byte[0], null);

        // when
        // then
        assertThrows(IllegalArgumentException.class, () -> service.createSkill(command));
    }

    @Test
    void should_rejectOverSizeContent_when_createSkill_given_oversizedContent() {
        // given
        byte[] oversized = new byte[(int) storageProperties.getMaxSize() + 1];
        CreateSkillCommand command = new CreateSkillCommand(
                "文本分析工具", null, SkillType.CUSTOM, oversized, null);

        // when
        // then
        assertThrows(IllegalArgumentException.class, () -> service.createSkill(command));
    }

    @Test
    void should_returnStoredContent_when_downloadContent_given_versionExists() {
        // given
        String skillId = "skill-1";
        byte[] content = content();
        String storageKey = skillId + "/1/" + skillId + "-1.zip";
        when(skillRepository.findBySkillIdAndVersion(skillId, 1))
                .thenReturn(Optional.of(buildVersion(skillId, 1, "数据清洗", content)));
        when(skillContentStore.get(storageKey)).thenReturn(content);

        // when
        byte[] downloaded = service.downloadContent(skillId, 1);

        // then
        assertArrayEquals(content, downloaded);
    }

    @Test
    void should_throwNotFound_when_downloadContent_given_unknownVersion() {
        // given
        when(skillRepository.findBySkillIdAndVersion("skill-1", 99)).thenReturn(Optional.empty());

        // when
        // then
        assertThrows(ResourceNotFoundException.class, () -> service.downloadContent("skill-1", 99));
    }

    @Test
    void should_throwNotFound_when_getSkillVersions_given_deletedSkill() {
        // given
        when(skillRepository.listBySkillId("skill-deleted")).thenReturn(List.of());

        // when
        // then
        assertThrows(ResourceNotFoundException.class, () -> service.getSkillVersions("skill-deleted"));
    }

    @Test
    void should_deleteAllVersionsAndContentFiles_when_deleteSkill_given_existingSkill() {
        // given
        String skillId = "skill-1";
        when(skillRepository.listBySkillId(skillId)).thenReturn(List.of(buildVersion(skillId, 1, "数据清洗", content())));

        // when
        service.deleteSkill(skillId);

        // then
        verify(skillRepository).deleteBySkillId(skillId);
        verify(skillContentStore).delete(skillId + "/1/" + skillId + "-1.zip");
    }

    @Test
    void should_deleteStoredContent_when_createSkill_given_domainValidationFailsAfterPut() {
        // given
        when(skillContentStore.put(anyString(), anyInt(), any())).thenReturn("skill-1/1/skill-1-1.zip");
        // 名称以数字开头触发 SkillResource 领域不变量（NAME_PATTERN），此时文件已落盘
        CreateSkillCommand command = new CreateSkillCommand(
                "1非法名称", null, SkillType.CUSTOM, content(), null);

        // when
        // then
        assertThrows(IllegalArgumentException.class, () -> service.createSkill(command));
        verify(skillContentStore).delete("skill-1/1/skill-1-1.zip");
    }

    @Test
    void should_throwConflictAndCompensateFile_when_publishVersion_given_uniqueKeyConflict() {
        // given
        String skillId = "skill-1";
        byte[] newContent = "并发发布内容".getBytes(StandardCharsets.UTF_8);
        when(skillRepository.findMaxVersionForUpdate(skillId))
                .thenReturn(Optional.of(buildVersion(skillId, 1, "数据清洗", content())));
        when(skillContentStore.put(anyString(), anyInt(), any())).thenReturn("skill-1/2/skill-1-2.zip");
        // 并发下后发事务读到旧 max，落库时撞唯一索引 uk_skill_version
        when(skillRepository.save(any()))
                .thenThrow(new DuplicateKeyException("duplicate key value violates unique constraint uk_skill_version"));

        // when / then
        ResourceConflictException ex = assertThrows(ResourceConflictException.class, () -> service.publishVersion(
                new PublishSkillVersionCommand(skillId, "修复若干问题", newContent, null)));
        assertTrue(ex.getMessage().contains("并发发布"));
        verify(skillContentStore).delete("skill-1/2/skill-1-2.zip");
    }

    @Test
    void should_completeLogicalDelete_when_deleteSkill_given_contentFileDeleteFails() {
        // given
        String skillId = "skill-1";
        String storageKey = skillId + "/1/" + skillId + "-1.zip";
        when(skillRepository.listBySkillId(skillId))
                .thenReturn(List.of(buildVersion(skillId, 1, "数据清洗", content())));
        // 磁盘 IO 失败时仅降级 WARN，不阻断台账逻辑删除
        doThrow(new RuntimeException("IO 失败")).when(skillContentStore).delete(storageKey);

        // when
        service.deleteSkill(skillId);

        // then
        verify(skillRepository).deleteBySkillId(skillId);
    }

    @Test
    void should_throwNotFound_when_deleteSkill_given_unknownSkill() {
        // given
        when(skillRepository.listBySkillId("skill-unknown")).thenReturn(List.of());

        // when
        // then
        assertThrows(ResourceNotFoundException.class, () -> service.deleteSkill("skill-unknown"));
    }

    @Test
    void should_listLatestVersions_when_listSkills_given_query() {
        // given
        byte[] content = content();
        ListSkillQuery query = new ListSkillQuery("数据", 1, 20);
        when(skillRepository.findLatestByCondition("数据", 1, 20))
                .thenReturn(List.of(buildVersion("skill-1", 2, "数据清洗", content)));
        when(skillRepository.countSkillsByCondition("数据")).thenReturn(1L);

        // when
        List<SkillResource> skills = service.listSkills(query);
        long total = service.countSkills(query);

        // then
        assertEquals(1, skills.size());
        assertEquals(2, skills.get(0).versionNumber());
        assertEquals(1L, total);
        assertTrue(skills.stream().allMatch(s -> s.versionNumber() >= 1));
    }
}