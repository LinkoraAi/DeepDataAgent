package com.linkroa.deepdataagent.file.infrastructure.repository;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.linkroa.deepdataagent.file.domain.model.File;
import com.linkroa.deepdataagent.file.domain.model.FileCursor;
import com.linkroa.deepdataagent.file.domain.model.FileScope;
import com.linkroa.deepdataagent.file.domain.model.enums.FilePurpose;
import com.linkroa.deepdataagent.file.infrastructure.persistence.entity.FileEntity;
import com.linkroa.deepdataagent.file.infrastructure.persistence.mapper.FileMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 文件元数据仓储实现单元测试：纯 files 表持久化（插入清主键 / 游标时区换算 /
 * 仅逻辑删除 / 按作用域检索与实体 → 领域转换）。内容字节与对象存储协同已上移
 * {@code DefaultFileContentPort}，本仓储不再触碰对象。
 */
@ExtendWith(MockitoExtension.class)
class JdbcFileRepositoryTest {

    static {
        // 初始化实体 TableInfo：delete 条件包装器 lambda 列名解析所需
        MapperBuilderAssistant assistant =
                new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, FileEntity.class);
    }

    private static final String SHA256_64 = "0".repeat(64);

    @Mock
    private FileMapper mapper;

    private JdbcFileRepository repository;

    @BeforeEach
    void setUp() {
        repository = new JdbcFileRepository();
        ReflectionTestUtils.setField(repository, "mapper", mapper);
    }

    @Test
    void should_convertDomainWithScope_when_findByScope_given_scopedEntity() {
        // given
        when(mapper.selectByScope("sess_1")).thenReturn(List.of(scopedEntity("file_a")));

        // when
        List<File> found = repository.findByScope("sess_1");

        // then
        verify(mapper).selectByScope("sess_1");
        assertThat(found).hasSize(1);
        File file = found.get(0);
        assertThat(file.fileId()).isEqualTo("file_a");
        assertThat(file.purpose()).isEqualTo(FilePurpose.TOOL_OUTPUT);
        assertThat(file.downloadable()).isTrue();
        assertThat(file.scope()).isEqualTo(new FileScope("sess_1", FileScope.TYPE_SESSION));
    }

    @Test
    void should_returnEmptyList_when_findByScope_given_noScopedEntities() {
        // given
        when(mapper.selectByScope("sess_none")).thenReturn(List.of());

        // when
        List<File> found = repository.findByScope("sess_none");

        // then
        assertThat(found).isEmpty();
    }

    @Test
    void should_insertMetadataAndReadBack_when_save_given_newFile() {
        // given（纯元数据插入，仓储不再承载内容字节）
        File file = buildFile();
        when(mapper.selectByFileId("file_a")).thenReturn(scopedEntity("file_a"));

        // when
        File saved = repository.save(file);

        // then（清主键后插入并回读数据库快照）
        ArgumentCaptor<FileEntity> captor = ArgumentCaptor.forClass(FileEntity.class);
        verify(mapper).insert(captor.capture());
        assertThat(captor.getValue().getId()).isNull();
        assertThat(saved.fileId()).isEqualTo("file_a");
    }

    @Test
    void should_mapDomain_when_findByFileId_given_entity() {
        // given
        when(mapper.selectByFileId("file_a")).thenReturn(scopedEntity("file_a"));

        // when
        Optional<File> found = repository.findByFileId("file_a");

        // then
        assertThat(found).isPresent();
        assertThat(found.get().scope()).isEqualTo(new FileScope("sess_1", FileScope.TYPE_SESSION));
    }

    @Test
    void should_convertCursorToShanghaiOffsetAndNullPurpose_when_findByFilters_given_cursorAndNoPurpose() {
        // given（游标 Instant 换算 Asia/Shanghai 偏移时间；purpose=null 透传契约值为空）
        FileCursor cursor = new FileCursor(Instant.parse("2026-09-03T02:00:00Z"), 7L);
        when(mapper.findByFilters(eq(1L), isNull(), eq("sess_1"), any(), eq(7L), anyInt()))
                .thenReturn(List.of());

        // when
        repository.findByFilters(1L, null, "sess_1", cursor, 21);

        // then
        ArgumentCaptor<OffsetDateTime> atCaptor = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(mapper).findByFilters(eq(1L), isNull(), eq("sess_1"),
                atCaptor.capture(), eq(7L), eq(21));
        assertThat(atCaptor.getValue())
                .isEqualTo(OffsetDateTime.of(2026, 9, 3, 10, 0, 0, 0, ZoneOffset.ofHours(8)));
    }

    @Test
    void should_logicalDeleteMetadataOnly_when_deleteByFileId_given_fileId() {
        // given
        when(mapper.delete(any())).thenReturn(1);

        // when
        int rows = repository.deleteByFileId("file_a");

        // then（仅逻辑删元数据行，返回影响行数；内容对象删除由 FileContentPort 编排）
        assertThat(rows).isEqualTo(1);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<FileEntity>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).delete(captor.capture());
        assertThat(captor.getValue().getSqlSegment()).contains("file_id");
    }

    /** 构造待保存的文件聚合夹具。 */
    private static File buildFile() {
        return File.create("file_a", 1L, "out.csv", "text/csv",
                FilePurpose.TOOL_OUTPUT, new FileScope("sess_1", FileScope.TYPE_SESSION),
                "{}", "hello".getBytes(StandardCharsets.UTF_8));
    }

    /** 构造已登记会话作用域的文件实体夹具。 */
    private static FileEntity scopedEntity(String fileId) {
        OffsetDateTime now = OffsetDateTime.parse("2026-09-03T10:00:00+08:00");
        FileEntity entity = new FileEntity();
        entity.setId(11L);
        entity.setFileId(fileId);
        entity.setOwnerId(1L);
        entity.setFilename("out.csv");
        entity.setMimeType("text/csv");
        entity.setSizeBytes(10L);
        entity.setPurpose(FilePurpose.TOOL_OUTPUT.code());
        entity.setStatus("ready");
        entity.setDownloadable(true);
        entity.setScope("{\"id\":\"sess_1\",\"type\":\"session\"}");
        entity.setMetadata("{}");
        entity.setContentSha256(SHA256_64);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        return entity;
    }
}
