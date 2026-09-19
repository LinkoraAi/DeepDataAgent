package com.linkroa.deepdataagent.runtime.api;

import com.linkroa.deepdataagent.runtime.api.dto.SchedulerLaunchDTO;

/**
 * 调度器触发启动服务契约（跨 BC 服务边界，未来 Feign 落点）。
 * <p>agent BC 的调度器（Deployment）触发时经本接口新建打标 Session 并驱动首个 turn，
 * 实现「触发即新建全新 Session、不复用、可追溯」。当前由 {@code DefaultSchedulerSessionApi}
 * 进程内实现，未来接入 Feign 时仅需在本接口追加 {@code @FeignClient} 注解并移除进程内实现，
 * 消费方（agent BC）无需改动。</p>
 */
public interface SchedulerSessionApi {

    /**
     * 调度器触发启动：新建打标 Session（触发来源类型 + 调度器ID）并驱动首个 turn。
     * <p>每次调用 MUST 新建全新 Session（绝不复用历史会话）；会话创建按其绑定版本
     * 实时装配，装配失败（Agent 不存在 / 版本缺失）由运行时装配校验抛出并整体回滚。</p>
     *
     * @param request 调度器触发启动契约
     * @return 新建会话的业务 ID（sessionId）
     */
    String launch(SchedulerLaunchDTO request);
}