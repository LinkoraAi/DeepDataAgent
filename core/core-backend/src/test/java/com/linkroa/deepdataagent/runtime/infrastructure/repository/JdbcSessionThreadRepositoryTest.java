package com.linkroa.deepdataagent.runtime.infrastructure.repository;

import com.linkroa.deepdataagent.runtime.domain.model.SessionThread;
import com.linkroa.deepdataagent.runtime.infrastructure.convert.RuntimePersistenceConvert;
import com.linkroa.deepdataagent.runtime.infrastructure.persistence.entity.SessionThreadEntity;
import com.linkroa.deepdataagent.runtime.infrastructure.persistence.mapper.SessionThreadMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link JdbcSessionThreadRepository} 仓储实现单测（mock MyBatis Mapper）：
 * Session Thread API 面下线后仅覆盖保留的主线程数据底座——新增回读与删除级联，
 * 均为「委托 + 收敛」语义。
 */
@ExtendWith(MockitoExtension.class)
class JdbcSessionThreadRepositoryTest {

    @Mock
    private SessionThreadMapper mapper;

    private JdbcSessionThreadRepository repository;

    @BeforeEach
    void setUp() {
        repository = new JdbcSessionThreadRepository();
        ReflectionTestUtils.setField(repository, "mapper", mapper);
    }

    @Test
    void should_insertAndReload_when_save_given_threadWithoutId() {
        // given（新线程落库后按业务 ID 直调 mapper 回读带行标识的持久化形态）
        SessionThread thread = SessionThread.createMain("sess_1", "{\"id\":\"agent-a\"}");
        SessionThreadEntity entity = RuntimePersistenceConvert.INSTANCE.toEntity(thread);
        entity.setId(9L);
        when(mapper.findByThreadId(thread.threadId())).thenReturn(entity);

        // when
        SessionThread saved = repository.save(thread);

        // then
        assertEquals(9L, saved.id());
        assertEquals(thread.threadId(), saved.threadId());
        verify(mapper).insert(any(SessionThreadEntity.class));
        verify(mapper, never()).updateById(any(SessionThreadEntity.class));
    }

    @Test
    void should_delegateLogicDelete_when_deleteBySessionId_given_sessionId() {
        // given（会话删除级联：受影响行数为线程条数）
        when(mapper.deleteBySessionId("sess_1")).thenReturn(2);

        // when & then
        assertEquals(2, repository.deleteBySessionId("sess_1"));
        verify(mapper).deleteBySessionId("sess_1");
    }
}
