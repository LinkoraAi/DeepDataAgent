package com.linkroa.deepdataagent.agent.infrastructure.persistence.repository;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.linkroa.deepdataagent.agent.domain.model.AgentSession;
import com.linkroa.deepdataagent.agent.domain.valueobject.SessionStatus;
import com.linkroa.deepdataagent.agent.infrastructure.persistence.entity.AgentSessionEntity;
import com.linkroa.deepdataagent.agent.infrastructure.persistence.mapper.AgentSessionMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AgentSessionRepositoryImpl} 的单元测试
 * <p>验证会话查询按最后消息时间（last_message_time）倒序排列，而非 updated_time。</p>
 */
@ExtendWith(MockitoExtension.class)
class AgentSessionRepositoryImplTest {

    @Mock
    private AgentSessionMapper mapper;

    @InjectMocks
    private AgentSessionRepositoryImpl repository;

    /**
     * 初始化 MyBatis-Plus 实体元数据缓存，使 LambdaQueryWrapper 能解析列名（last_message_time 等）。
     */
    @BeforeAll
    static void initMybatisPlusMetadata() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                AgentSessionEntity.class);
    }

    @Test
    void should_returnSessionsInMapperOrder_when_findActiveSessions() {
        // given
        AgentSessionEntity newer = buildEntity("s2", LocalDateTime.of(2026, 8, 7, 10, 0));
        AgentSessionEntity older = buildEntity("s1", LocalDateTime.of(2026, 8, 6, 10, 0));
        when(mapper.selectList(any())).thenReturn(List.of(newer, older));

        // when
        List<AgentSession> result = repository.findActiveSessions();

        // then
        assertEquals(2, result.size());
        assertEquals("s2", result.get(0).getId());
        assertEquals("s1", result.get(1).getId());
        verify(mapper).selectList(any());
    }

    @Test
    void should_orderByLastMessageTimeDesc_when_findActiveSessions() {
        // given
        when(mapper.selectList(any())).thenReturn(List.of());

        // when
        repository.findActiveSessions();

        // then：排序字段为 last_message_time 且倒序，而非 updated_time
        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaQueryWrapper<AgentSessionEntity>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(mapper).selectList(captor.capture());
        String targetSql = captor.getValue().getTargetSql();
        assertTrue(targetSql.contains("last_message_time"));
        assertTrue(targetSql.contains("DESC"));
        assertFalse(targetSql.contains("updated_time"));
    }

    @Test
    void should_orderByLastMessageTimeDesc_when_findActiveSessionsPaged() {
        // given
        when(mapper.selectList(any())).thenReturn(List.of());

        // when
        repository.findActiveSessionsPaged(10, 0);

        // then：排序字段为 last_message_time 且倒序
        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaQueryWrapper<AgentSessionEntity>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(mapper).selectList(captor.capture());
        String targetSql = captor.getValue().getTargetSql();
        assertTrue(targetSql.contains("last_message_time"));
        assertTrue(targetSql.contains("DESC"));
        assertFalse(targetSql.contains("updated_time"));
    }

    @Test
    void should_setStatusDeletedAndIsDeleted_when_softDelete() {
        // when
        repository.softDelete("session-1");

        // then：status 置为 DELETED，且 is_deleted 置 1（与全局逻辑删除约定一致）
        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaUpdateWrapper<AgentSessionEntity>> captor =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(mapper).update(isNull(), captor.capture());
        LambdaUpdateWrapper<AgentSessionEntity> wrapper = captor.getValue();
        // 参数化 SQL 中值为占位符，需从参数值对中校验实际值；SET 片段通过 getSqlSet() 获取
        assertTrue(wrapper.getParamNameValuePairs().containsValue(SessionStatus.DELETED.name()));
        assertTrue(wrapper.getParamNameValuePairs().containsValue(1));
        assertTrue(wrapper.getSqlSet().contains("is_deleted"));
        assertTrue(wrapper.getSqlSet().contains("updated_time"));
    }

    /**
     * 构造会话实体
     *
     * @param id               会话 ID
     * @param lastMessageTime  最后消息时间
     * @return 会话实体
     */
    private AgentSessionEntity buildEntity(String id, LocalDateTime lastMessageTime) {
        AgentSessionEntity entity = new AgentSessionEntity();
        entity.setId(id);
        entity.setStatus(SessionStatus.ACTIVE.name());
        entity.setIsDeleted(0);
        entity.setLastMessageTime(lastMessageTime);
        return entity;
    }
}