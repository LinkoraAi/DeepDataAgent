package com.linkroa.deepdataagent.runtime.infrastructure.client;

import com.linkroa.deepdataagent.runtime.domain.model.MemoryStoreRef;
import io.agentscope.core.tool.Tool;

import java.util.List;

/**
 * 记忆检索占位工具（AgentScope 官方 {@code @Tool} 注解对象）。
 * <p>随 Agent 版本记忆库引用自动装配（未引用不装配）：本期仅提供只读的
 * {@code memory_store_list}，让 LLM 了解当前 Agent 被授权的记忆库清单。
 * 真实的记忆内容读取 / 写入检索，待 memory BC 建立 {@code MemoryStoreProvider}
 * 内容端口后再补全，此处仅做占位装配，不跨 BC 存取记忆内容。</p>
 */
public class MemoryStoreListTool {

    private final List<MemoryStoreRef> memoryStoreRefs;

    public MemoryStoreListTool(List<MemoryStoreRef> memoryStoreRefs) {
        this.memoryStoreRefs = List.copyOf(memoryStoreRefs);
    }

    @Tool(name = "memory_store_list", description = "列出当前 Agent 被授权的记忆库清单（ID / 名称 / 类型）。当前为占位实现，仅提供清单，不支持读取或写入记忆内容", readOnly = true)
    public String listMemoryStores() {
        if (memoryStoreRefs.isEmpty()) {
            return "当前无可用记忆库";
        }
        StringBuilder sb = new StringBuilder();
        for (MemoryStoreRef ref : memoryStoreRefs) {
            sb.append(ref.memoryStoreId()).append(" - ").append(ref.name()).append("（").append(ref.type()).append("）\n");
        }
        return sb.toString();
    }
}