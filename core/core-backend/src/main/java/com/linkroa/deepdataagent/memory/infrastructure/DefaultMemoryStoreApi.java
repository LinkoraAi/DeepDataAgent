package com.linkroa.deepdataagent.memory.infrastructure;

import com.linkroa.deepdataagent.memory.api.MemoryStoreApi;
import com.linkroa.deepdataagent.memory.application.contract.MemoryStoreReferenceDTO;
import com.linkroa.deepdataagent.memory.domain.model.MemoryStore;
import com.linkroa.deepdataagent.memory.domain.repository.MemoryStoreRepository;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 记忆库查询服务契约实现（{@link MemoryStoreApi}）：按业务 ID 批量解析记忆库引用为发布语言 DTO。
 */
@Component
public class DefaultMemoryStoreApi implements MemoryStoreApi {

    private final MemoryStoreRepository memoryStoreRepository;

    public DefaultMemoryStoreApi(MemoryStoreRepository memoryStoreRepository) {
        this.memoryStoreRepository = memoryStoreRepository;
    }

    @Override
    public List<MemoryStoreReferenceDTO> resolveByIds(List<String> memoryStoreIds) {
        if (memoryStoreIds == null || memoryStoreIds.isEmpty()) {
            return List.of();
        }
        return memoryStoreRepository.findByIds(memoryStoreIds).stream()
                .map(DefaultMemoryStoreApi::toReference)
                .toList();
    }

    private static MemoryStoreReferenceDTO toReference(MemoryStore store) {
        return new MemoryStoreReferenceDTO(
                store.memoryId(),
                store.name(),
                store.type().name(),
                store.workspaceId()
        );
    }
}