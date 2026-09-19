package com.linkroa.deepdataagent.memory.infrastructure.assembly;

import com.linkroa.deepdataagent.memory.api.MemoryStoreApi;
import com.linkroa.deepdataagent.memory.api.dto.MemoryStoreReferenceDTO;
import com.linkroa.deepdataagent.memory.domain.model.MemoryStore;
import com.linkroa.deepdataagent.memory.domain.repository.MemoryStoreRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * {@link DefaultMemoryStoreApi} 服务契约进程内实现单测（批量引用解析）。
 * <p>仅映射业务 ID 与名称（发布语言 DTO）；缺失记忆库由仓储 owner 隔离查询自然缺席，
 * 差集判定由消费方完成。</p>
 */
@ExtendWith(MockitoExtension.class)
class DefaultMemoryStoreApiTest {

    @Mock private MemoryStoreRepository memoryStoreRepository;

    private MemoryStoreApi memoryStoreApi;

    @BeforeEach
    void setUp() {
        memoryStoreApi = new DefaultMemoryStoreApi();
        ReflectionTestUtils.setField(memoryStoreApi, "memoryStoreRepository", memoryStoreRepository);
    }

    @Test
    void should_mapIdAndName_when_resolveByIds_given_ownedStores() {
        // given
        when(memoryStoreRepository.findByIds(1L, List.of("ms_1", "ms_2")))
                .thenReturn(List.of(
                        MemoryStore.create("ms_1", "记忆库一", null, 1L),
                        MemoryStore.create("ms_2", "记忆库二", null, 1L)));

        // when
        List<MemoryStoreReferenceDTO> refs = memoryStoreApi.resolveByIds(1L, List.of("ms_1", "ms_2"));

        // then（DTO 仅携带 storeId 与名称）
        assertEquals(2, refs.size());
        assertEquals("ms_1", refs.get(0).storeId());
        assertEquals("记忆库一", refs.get(0).name());
        assertEquals("ms_2", refs.get(1).storeId());
    }

    @Test
    void should_returnPartialResult_when_resolveByIds_given_missingStores() {
        // given（仅命中一个，缺席项由消费方差集判定）
        when(memoryStoreRepository.findByIds(1L, List.of("ms_1", "ms_gone")))
                .thenReturn(List.of(MemoryStore.create("ms_1", "记忆库一", null, 1L)));

        // when
        List<MemoryStoreReferenceDTO> refs = memoryStoreApi.resolveByIds(1L, List.of("ms_1", "ms_gone"));

        // then
        assertEquals(List.of(new MemoryStoreReferenceDTO("ms_1", "记忆库一")), refs);
    }

    @Test
    void should_returnEmpty_when_resolveByIds_given_nullOrEmptyIds() {
        // when & then（空入参短路，不触碰仓储）
        assertTrue(memoryStoreApi.resolveByIds(1L, null).isEmpty());
        assertTrue(memoryStoreApi.resolveByIds(1L, List.of()).isEmpty());
    }
}
