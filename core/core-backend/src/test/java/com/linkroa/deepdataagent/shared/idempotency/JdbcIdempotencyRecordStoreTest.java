package com.linkroa.deepdataagent.shared.idempotency;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.linkroa.deepdataagent.shared.infrastructure.persistence.entity.IdempotencyRecordEntity;
import com.linkroa.deepdataagent.shared.infrastructure.persistence.mapper.IdempotencyRecordMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link JdbcIdempotencyRecordStore} 单测（owner 隔离读取、插入清主键、并发撞库静默收敛）。
 */
@ExtendWith(MockitoExtension.class)
class JdbcIdempotencyRecordStoreTest {

    static {
        // 初始化实体 TableInfo：lambda 列名解析所需
        MapperBuilderAssistant assistant =
                new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, IdempotencyRecordEntity.class);
    }

    @Mock
    private IdempotencyRecordMapper mapper;

    @InjectMocks
    private JdbcIdempotencyRecordStore store;

    private IdempotencyRecordEntity entity() {
        IdempotencyRecordEntity entity = new IdempotencyRecordEntity();
        entity.setId(9L);
        entity.setIdempotencyKey("key-1");
        entity.setOwnerId(1L);
        entity.setScope("post_agents");
        entity.setRequestHash("hash-1");
        entity.setResponseStatus(200);
        entity.setResponseBody("{\"data\":{}}");
        return entity;
    }

    @Test
    void should_returnRecord_when_find_given_existingRow() {
        // given
        when(mapper.selectByKey(1L, "post_agents", "key-1")).thenReturn(entity());

        // when
        Optional<IdempotencyRecord> found = store.find(1L, "post_agents", "key-1");

        // then
        assertTrue(found.isPresent());
        assertEquals("key-1", found.get().idempotencyKey());
        assertEquals("hash-1", found.get().requestHash());
        assertEquals(200, found.get().responseStatus());
    }

    @Test
    void should_returnEmpty_when_find_given_missingRow() {
        // given
        when(mapper.selectByKey(1L, "post_agents", "key-x")).thenReturn(null);

        // when // then
        assertTrue(store.find(1L, "post_agents", "key-x").isEmpty());
    }

    @Test
    void should_insertWithClearedId_when_save_given_record() {
        // given
        IdempotencyRecord record = new IdempotencyRecord(
                "key-1", 1L, "post_agents", "hash-1", 200, "{\"data\":{}}");

        // when
        store.save(record);

        // then
        ArgumentCaptor<IdempotencyRecordEntity> captor = ArgumentCaptor.forClass(IdempotencyRecordEntity.class);
        verify(mapper).insert(captor.capture());
        assertNull(captor.getValue().getId());
        assertEquals("post_agents", captor.getValue().getScope());
    }

    @Test
    void should_swallowException_when_save_given_duplicateKey() {
        // given（并发同键：唯一索引兜底，先落库者为准）
        doThrow(new DuplicateKeyException("dup")).when(mapper).insert(any(IdempotencyRecordEntity.class));

        // when // then（不抛出，静默收敛）
        store.save(new IdempotencyRecord("key-1", 1L, "post_agents", "hash-1", 200, "{}"));
        verify(mapper).insert(any(IdempotencyRecordEntity.class));
    }
}