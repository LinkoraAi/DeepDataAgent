package com.linkroa.deepdataagent.memory.application.service;

import com.linkroa.deepdataagent.agent.api.AgentReferenceApi;
import com.linkroa.deepdataagent.memory.application.command.CreateMemoryStoreCommand;
import com.linkroa.deepdataagent.memory.application.query.ListMemoryStoreQuery;
import com.linkroa.deepdataagent.memory.application.validation.MemoryStoreValidator;
import com.linkroa.deepdataagent.memory.domain.model.MemoryStore;
import com.linkroa.deepdataagent.memory.domain.repository.MemoryStoreRepository;
import com.linkroa.deepdataagent.shared.exception.ResourceNotFoundException;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

/**
 * 记忆库应用服务（增删查）
 */
@Service
public class MemoryStoreApplicationService {

    private static final String DEFAULT_WORKSPACE_ID = "default";

    @Resource
    private MemoryStoreRepository memoryStoreRepository;
    @Resource
    private AgentReferenceApi agentReferenceApi;
    @Resource
    private TransactionTemplate transactionTemplate;

    public MemoryStore create(CreateMemoryStoreCommand command) {
        MemoryStore store = MemoryStore.create(
                UUID.randomUUID().toString(),
                command.name(),
                command.type(),
                DEFAULT_WORKSPACE_ID
        );
        return transactionTemplate.execute(status -> memoryStoreRepository.save(store));
    }

    public List<MemoryStore> list(ListMemoryStoreQuery query) {
        return memoryStoreRepository.findByPage(query.page(), query.size());
    }

    public long count() {
        return memoryStoreRepository.countAll();
    }

    public MemoryStore get(String memoryId) {
        return memoryStoreRepository.findByMemoryId(memoryId)
                .orElseThrow(() -> new ResourceNotFoundException("记忆库不存在"));
    }

    public void delete(String memoryId) {
        transactionTemplate.executeWithoutResult(status -> {
            MemoryStore store = memoryStoreRepository.findByMemoryIdForUpdate(memoryId)
                    .orElseThrow(() -> new ResourceNotFoundException("记忆库不存在"));
            long refCount = agentReferenceApi.countMemoryStoreReferences(memoryId);
            MemoryStoreValidator.validateDelete(store, refCount);
            memoryStoreRepository.deleteByMemoryId(memoryId);
        });
    }
}