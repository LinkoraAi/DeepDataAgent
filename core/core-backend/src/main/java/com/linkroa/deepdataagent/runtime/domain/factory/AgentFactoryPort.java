package com.linkroa.deepdataagent.runtime.domain.factory;

import com.linkroa.deepdataagent.runtime.domain.model.AgentAssemblySpec;
import com.linkroa.deepdataagent.runtime.domain.model.ModelAccess;

/**
 * Agent 组装出向端口（依赖倒置）。
 * <p>领域层仅依赖本接口与不透明句柄 {@link BuiltAgent}；AgentScope 框架类型
 * （HarnessAgent / AgentStateStore / AgentEvent）不进入 domain 层，
 * 其中 Fabric 实现位于 infrastructure.client。</p>
 * <p>生命周期语义为「每请求构建 + 用后释放」：{@link #build} 每次返回
 * 全新实例（不缓存），调用方在轮次结束后必须经 {@link BuiltAgent#close()} 释放；
 * 中断/终止由 {@link BuiltAgent#interrupt()} 幂等触发。</p>
 */
public interface AgentFactoryPort {

    /**
     * 装配全新的 Agent 实例（每次构建，无缓存）。
     *
     * @param spec        装配规格（领域模型，不承载凭证）
     * @param modelAccess 模型访问配置（凭证/API 端点，仅注入工厂装配）
     * @return 不透明句柄（用后须 {@code close()}
     */
    BuiltAgent build(AgentAssemblySpec spec, ModelAccess modelAccess);
}