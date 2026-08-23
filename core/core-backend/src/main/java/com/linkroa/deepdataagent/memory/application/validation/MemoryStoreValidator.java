package com.linkroa.deepdataagent.memory.application.validation;

import com.linkroa.deepdataagent.memory.domain.model.MemoryStore;
import com.linkroa.deepdataagent.shared.exception.ResourceConflictException;

/**
 * 记忆库应用级校验器
 */
public class MemoryStoreValidator {

    /**
     * 校验删除冲突：仍被未删除的 Agent 版本引用时不可删除
     *
     * @param store    目标记忆库
     * @param refCount 引用数量
     */
    public static void validateDelete(MemoryStore store, long refCount) {
        if (refCount > 0) {
            throw new ResourceConflictException("记忆库「" + store.name()
                    + "」仍被 " + refCount + " 个 Agent 版本引用，无法删除");
        }
    }
}