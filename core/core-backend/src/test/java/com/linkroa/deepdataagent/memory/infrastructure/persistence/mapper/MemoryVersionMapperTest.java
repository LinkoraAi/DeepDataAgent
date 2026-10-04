package com.linkroa.deepdataagent.memory.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.linkroa.deepdataagent.memory.infrastructure.persistence.entity.MemoryVersionEntity;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link MemoryVersionMapper} 版本历史排序 SQL 形状单测（集成测试 D6）。
 * <p>断言 {@code selectByEntryIdOrderByVersionAsc} 生成的查询按 {@code version} 升序（时间正序）：
 * 原实现降序，导致版本历史倒序返回。删除后的历史含 {@code deleted} 墓碑行，仍经同一查询可见。</p>
 * <p>mock Mapper + {@code doCallRealMethod} 真实装配 default 方法，捕获条件包装器比对 SQL 片段。</p>
 */
class MemoryVersionMapperTest {

    static {
        // 初始化实体 TableInfo：lambda 方法引用 ⇄ 列名解析所需的元数据缓存
        MapperBuilderAssistant assistant =
                new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, MemoryVersionEntity.class);
    }

    private static final String ENTRY_ID = "mem_1";

    /**
     * 断言查询片段包含 {@code version} 升序、{@code entry_id} 等值，且不含 {@code DESC}。
     */
    @Test
    void should_orderByVersionAscending_when_selectByEntryIdOrderByVersionAsc_given_entryId() {
        // given
        MemoryVersionMapper mapper = mock(MemoryVersionMapper.class);
        when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        doCallRealMethod().when(mapper).selectByEntryIdOrderByVersionAsc(anyString());

        // when
        mapper.selectByEntryIdOrderByVersionAsc(ENTRY_ID);

        // then
        LambdaQueryWrapper<MemoryVersionEntity> wrapper = captured(mapper);
        String sqlSegment = wrapper.getSqlSegment();
        assertTrue(sqlSegment.contains("version"), sqlSegment);
        assertTrue(sqlSegment.contains("ASC"), sqlSegment);
        assertFalse(sqlSegment.contains("DESC"), "版本历史须时间正序，不得降序: " + sqlSegment);
        assertTrue(sqlSegment.contains("entry_id ="), sqlSegment);
        assertTrue(wrapper.getParamNameValuePairs().containsValue(ENTRY_ID),
                String.valueOf(wrapper.getParamNameValuePairs().values()));
    }

    /** 捕获 {@code selectByEntryIdOrderByVersionAsc} 装配的查询包装器。 */
    @SuppressWarnings("unchecked")
    private static LambdaQueryWrapper<MemoryVersionEntity> captured(MemoryVersionMapper mapper) {
        ArgumentCaptor<Wrapper<MemoryVersionEntity>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).selectList(captor.capture());
        Wrapper<MemoryVersionEntity> wrapper = captor.getValue();
        assertTrue(wrapper instanceof LambdaQueryWrapper, "版本查询必须经 LambdaQueryWrapper 装配");
        return (LambdaQueryWrapper<MemoryVersionEntity>) wrapper;
    }
}