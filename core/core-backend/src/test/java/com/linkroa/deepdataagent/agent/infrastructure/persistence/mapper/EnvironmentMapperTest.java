package com.linkroa.deepdataagent.agent.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.linkroa.deepdataagent.agent.domain.model.EnvironmentListFilter;
import com.linkroa.deepdataagent.agent.infrastructure.persistence.entity.EnvironmentEntity;
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
 * {@link EnvironmentMapper} 游标查询 SQL 断言单测（6.6 Cursor 约定）。
 * <p>mock Mapper + {@code doCallRealMethod} 真实装配 default 方法，捕获条件包装器
 * 断言 keyset 行值比较、metadata JSONB 包含（物理列名 metadata）、创建时间区间、
 * 排序方向与 LIMIT 片段。</p>
 */
class EnvironmentMapperTest {

    static {
        // 初始化实体 TableInfo：lambda 方法引用 ⇄ 列名解析所需的元数据缓存
        MapperBuilderAssistant assistant =
                new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, EnvironmentEntity.class);
    }

    private static final OffsetDateTime CURSOR_AT = OffsetDateTime.parse("2026-09-01T10:00:00+08:00");

    @SuppressWarnings({"unchecked", "null"})
    private LambdaQueryWrapper<EnvironmentEntity> captureCursorWrapper(EnvironmentListFilter filter, int limit) {
        EnvironmentMapper mapper = mock(EnvironmentMapper.class);
        when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        doCallRealMethod().when(mapper).selectByCursor(anyLong(), any(EnvironmentListFilter.class), anyInt());

        mapper.selectByCursor(1L, filter, limit);

        ArgumentCaptor<Wrapper<EnvironmentEntity>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).selectList(captor.capture());
        return (LambdaQueryWrapper<EnvironmentEntity>) captor.getValue();
    }

    @Test
    void should_filterOnlyOwner_when_selectByCursor_given_blankFilter() {
        // given（首页空白过滤：仅 owner 隔离 + 默认排序 + 探针 LIMIT）
        EnvironmentListFilter filter = new EnvironmentListFilter(null, null, null, null, null, false);

        // when
        String sql = captureCursorWrapper(filter, 21).getSqlSegment();

        // then
        assertTrue(sql.contains("owner_id ="), sql);
        assertFalse(sql.contains("metadata"), sql);
        assertFalse(sql.contains("(created_at, id)"), sql);
        assertTrue(sql.contains("ORDER BY created_at DESC"), sql);
        assertTrue(sql.contains("LIMIT 21"), sql);
    }

    @Test
    void should_applyMetadataContainmentAndTimeRange_when_selectByCursor_given_fullFilters() {
        // given（metadata JSONB 包含 + 创建时间闭区间）
        EnvironmentListFilter filter = new EnvironmentListFilter(
                "{\"team\":\"data\"}",
                OffsetDateTime.parse("2026-08-01T00:00:00+08:00"),
                OffsetDateTime.parse("2026-08-31T23:59:59+08:00"),
                null, null, false);

        // when
        Wrapper<EnvironmentEntity> wrapper = captureCursorWrapper(filter, 20);
        String sql = wrapper.getSqlSegment();

        // then（物理列名 metadata 的 @> 包含 + ge/le 双边界 + 区间参数入占位表）
        assertTrue(sql.contains("metadata @> cast("), sql);
        assertTrue(sql.contains("created_at >="), sql);
        assertTrue(sql.contains("created_at <="), sql);
        assertFalse(sql.contains("(created_at, id)"), sql);
    }

    @Test
    void should_applyKeysetLessThan_when_selectByCursor_given_afterCursor() {
        // given（after_id 方向：降序读取更旧页）
        EnvironmentListFilter filter =
                new EnvironmentListFilter(null, null, null, CURSOR_AT, 7L, false);

        // when
        LambdaQueryWrapper<EnvironmentEntity> wrapper = captureCursorWrapper(filter, 20);

        // then
        String sql = wrapper.getSqlSegment();
        assertTrue(sql.contains("(created_at, id) < ("), sql);
        assertTrue(wrapper.getParamNameValuePairs().containsValue(7L), sql);
        assertTrue(wrapper.getParamNameValuePairs().containsValue(CURSOR_AT), sql);
    }

    @Test
    void should_flipKeysetAndOrderAsc_when_selectByCursor_given_beforeCursor() {
        // given（before_id 方向：升序读取更新侧，应用层翻转回降序）
        EnvironmentListFilter filter =
                new EnvironmentListFilter(null, null, null, CURSOR_AT, 7L, true);

        // when
        String sql = captureCursorWrapper(filter, 20).getSqlSegment();

        // then（两向 apply 条件式：reverse 仅装配大于方向）
        assertTrue(sql.contains("(created_at, id) > ("), sql);
        assertFalse(sql.contains("(created_at, id) < ("), sql);
        assertTrue(sql.contains("ORDER BY created_at ASC"), sql);
    }
}
