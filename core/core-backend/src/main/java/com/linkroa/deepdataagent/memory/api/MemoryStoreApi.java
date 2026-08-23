package com.linkroa.deepdataagent.memory.api;

import com.linkroa.deepdataagent.memory.application.contract.MemoryStoreReferenceDTO;

import java.util.List;

/**
 * 记忆库查询服务契约（跨 BC 服务边界，未来 Feign 落点）。
 * <p>与领域出站端口分离：本接口是 memory BC 对外暴露的跨 BC 查询能力面，
 * 消费方（agent / runtime）依赖本接口而非进程内领域端口实现。当前由
 * {@code DefaultMemoryStoreApi} 进程内实现，未来接入 Feign 时仅需在本接口
 * 追加 {@code @FeignClient} 注解 + 提供远程实现，消费方无需改动。</p>
 */
public interface MemoryStoreApi {

    /**
     * 批量按业务 ID 解析记忆库引用（只返回存在的记忆库，缺失的 id 不出现在结果中）。
     *
     * @param memoryStoreIds 记忆库业务 ID 列表（可空 / 空）
     * @return 记忆库引用契约列表（输入为空时返回空列表）
     */
    List<MemoryStoreReferenceDTO> resolveByIds(List<String> memoryStoreIds);
}