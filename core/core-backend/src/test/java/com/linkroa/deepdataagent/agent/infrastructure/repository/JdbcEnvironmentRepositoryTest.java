package com.linkroa.deepdataagent.agent.infrastructure.repository;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.linkroa.deepdataagent.agent.domain.model.Environment;
import com.linkroa.deepdataagent.agent.domain.model.EnvironmentListFilter;
import com.linkroa.deepdataagent.agent.infrastructure.persistence.entity.EnvironmentEntity;
import com.linkroa.deepdataagent.agent.infrastructure.persistence.mapper.EnvironmentMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link JdbcEnvironmentRepository} 单测：保存清主键回读、更新条件与回读、
 * 游标查询委托映射、逻辑删条件装配（6.6 Cursor 约定改造后的仓储语义）。
 */
@ExtendWith(MockitoExtension.class)
class JdbcEnvironmentRepositoryTest {

    static {
        // 初始化实体 TableInfo：delete/update 条件包装器 lambda 列名解析所需
        MapperBuilderAssistant assistant =
                new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, EnvironmentEntity.class);
    }

    @Mock
    private EnvironmentMapper mapper;

    @InjectMocks
    private JdbcEnvironmentRepository repository;

    private final OffsetDateTime now = OffsetDateTime.parse("2026-09-04T10:00:00+08:00");

    private EnvironmentEntity buildEntity(String environmentId) {
        EnvironmentEntity entity = new EnvironmentEntity();
        entity.setId(9L);
        entity.setEnvironmentId(environmentId);
        entity.setName("默认环境");
        entity.setDescription("团队环境");
        entity.setConfig("{\"type\":\"cloud\"}");
        entity.setMetadata("{}");
        entity.setOwnerId(1L);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        return entity;
    }

    private Environment buildEnvironment() {
        return Environment.restore(9L, "env_1", "默认环境", null, null, "{}", 1L, null, now, now, null, null);
    }

    @Test
    void should_insertWithClearedIdAndReadBack_when_save_given_newEnvironment() {
        // given
        when(mapper.selectByEnvironmentId("env_1")).thenReturn(buildEntity("env_1"));

        // when
        Environment saved = repository.save(buildEnvironment());

        // then（主键清空后插入，落库后回读数据库快照）
        ArgumentCaptor<EnvironmentEntity> captor = ArgumentCaptor.forClass(EnvironmentEntity.class);
        verify(mapper).insert(captor.capture());
        assertNull(captor.getValue().getId());
        assertEquals("env_1", saved.environmentId());
        assertEquals(9L, saved.id());
    }

    @Test
    void should_updateByEnvironmentIdConditionAndReadBack_when_update_given_environment() {
        // given
        when(mapper.selectByEnvironmentId("env_1")).thenReturn(buildEntity("env_1"));

        // when
        Environment updated = repository.update(buildEnvironment());

        // then（按业务 ID 条件更新 + 回读）
        verify(mapper).update(any(EnvironmentEntity.class), any(Wrapper.class));
        assertEquals("env_1", updated.environmentId());
    }

    @Test
    void should_delegateAndMapDomain_when_findByCursor_given_filterAndLimit() {
        // given（游标条件对象原样透传，实体流经转换器映射为领域模型）
        EnvironmentListFilter filter =
                new EnvironmentListFilter(null, null, null, now, 9L, false);
        when(mapper.selectByCursor(eq(1L), same(filter), eq(21)))
                .thenReturn(List.of(buildEntity("env_1"), buildEntity("env_2")));

        // when
        List<Environment> page = repository.findByCursor(1L, filter, 21);

        // then
        assertEquals(2, page.size());
        assertEquals("env_1", page.get(0).environmentId());
        assertEquals("默认环境", page.get(1).name());
        assertEquals(9L, page.get(0).id());
    }

    @Test
    void should_deleteByEnvironmentIdCondition_when_deleteByEnvironmentId_given_environmentId() {
        // when（@TableLogic：delete 为逻辑删，按业务 ID 装配条件）
        repository.deleteByEnvironmentId("env_1");

        // then
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<EnvironmentEntity>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).delete(captor.capture());
        assertTrue(captor.getValue().getSqlSegment().contains("environment_id ="),
                captor.getValue().getSqlSegment());
    }
}
