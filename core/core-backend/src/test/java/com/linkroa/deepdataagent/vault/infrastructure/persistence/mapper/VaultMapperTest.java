package com.linkroa.deepdataagent.vault.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.linkroa.deepdataagent.vault.domain.model.VaultListFilter;
import com.linkroa.deepdataagent.vault.infrastructure.persistence.entity.VaultEntity;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link VaultMapper} 游标查询 SQL 断言单测（6.6 Cursor 约定）。
 * <p>无数据库依赖路线：mock Mapper + {@code doCallRealMethod} 触发 default 方法真实装配，
 * 捕获传给 {@code selectList} 的条件包装器，断言 keyset 行值比较方向、归档三态、
 * metadata JSONB 包含、排序与 LIMIT 片段（lambda 列名解析依赖 TableInfo 预初始化）。</p>
 */
class VaultMapperTest {

    static {
        // 初始化实体 TableInfo：lambda 方法引用 ⇄ 列名解析所需的元数据缓存
        MapperBuilderAssistant assistant =
                new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, VaultEntity.class);
    }

    private static final OffsetDateTime CURSOR_AT = OffsetDateTime.parse("2026-09-01T10:00:00+08:00");

    @SuppressWarnings({"unchecked", "null"})
    private LambdaQueryWrapper<VaultEntity> captureCursorWrapper(VaultListFilter filter, int limit) {
        VaultMapper mapper = mock(VaultMapper.class);
        when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        doCallRealMethod().when(mapper).selectByCursor(anyLong(), any(VaultListFilter.class), anyInt());

        mapper.selectByCursor(1L, filter, limit);

        ArgumentCaptor<Wrapper<VaultEntity>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).selectList(captor.capture());
        return (LambdaQueryWrapper<VaultEntity>) captor.getValue();
    }

    @Test
    void should_filterOwnerAndArchivedAndMetadata_when_selectByCursor_given_firstPage() {
        // given（首页：无游标位点，仅未归档 + metadata 包含过滤）
        VaultListFilter filter = new VaultListFilter(null, "{\"team\":\"data\"}", false, null, null, false);

        // when
        String sql = captureCursorWrapper(filter, 21).getSqlSegment();

        // then（owner 隔离 + archived_at IS NULL + metadata_json 物理列 JSONB 包含 + 降序 + 探针 LIMIT）
        assertTrue(sql.contains("owner_id ="), sql);
        assertTrue(sql.contains("archived_at IS NULL"), sql);
        assertTrue(sql.contains("metadata_json @> cast("), sql);
        assertFalse(sql.contains("(created_at, id)"), sql);
        assertTrue(sql.contains("ORDER BY created_at DESC"), sql);
        assertTrue(sql.contains("LIMIT 21"), sql);
    }

    @Test
    void should_applyKeysetLessThan_when_selectByCursor_given_afterCursor() {
        // given（after_id 方向：降序读取更旧页）
        VaultListFilter filter = new VaultListFilter(null, null, null, CURSOR_AT, 7L, false);

        // when
        LambdaQueryWrapper<VaultEntity> wrapper = captureCursorWrapper(filter, 20);

        // then（keyset 行值比较小于游标位点，游标参数进入占位参数表）
        String sql = wrapper.getSqlSegment();
        assertTrue(sql.contains("(created_at, id) < ("), sql);
        assertTrue(wrapper.getParamNameValuePairs().containsValue(7L), sql);
        assertTrue(wrapper.getParamNameValuePairs().containsValue(CURSOR_AT), sql);
    }

    @Test
    void should_flipKeysetAndOrderAsc_when_selectByCursor_given_beforeCursor() {
        // given（before_id 方向：升序读取更新侧，由应用层翻转回降序）
        VaultListFilter filter = new VaultListFilter(null, null, null, CURSOR_AT, 7L, true);

        // when
        String sql = captureCursorWrapper(filter, 20).getSqlSegment();

        // then（比较方向与排序同步翻转）
        assertTrue(sql.contains("(created_at, id) > ("), sql);
        assertTrue(sql.contains("ORDER BY created_at ASC"), sql);
    }

    @Test
    void should_filterOnlyArchived_when_selectByCursor_given_archivedTrue() {
        // given（仅归档视图）
        VaultListFilter filter = new VaultListFilter(null, null, true, null, null, false);

        // when
        String sql = captureCursorWrapper(filter, 20).getSqlSegment();

        // then
        assertTrue(sql.contains("archived_at IS NOT NULL"), sql);
    }

    @Test
    void should_skipArchivedCondition_when_selectByCursor_given_archivedNull() {
        // given（include_archived：归档态不限）
        VaultListFilter filter = new VaultListFilter(null, null, null, null, null, false);

        // when
        String sql = captureCursorWrapper(filter, 20).getSqlSegment();

        // then（不出现归档列条件）
        assertFalse(sql.contains("archived_at"), sql);
    }

    @Test
    void should_skipKeyset_when_selectByCursor_given_cursorHalfPairAbsent() {
        // given（record 成对校验保证游标位点同现；无游标即首页，两向比较均不装配）
        VaultListFilter filter = new VaultListFilter(null, null, false, null, null, false);

        // when
        String sql = captureCursorWrapper(filter, 20).getSqlSegment();

        // then
        assertFalse(sql.contains("(created_at, id)"), sql);
    }
}
