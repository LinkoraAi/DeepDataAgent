package com.linkroa.deepdataagent.runtime.infrastructure.assembly;

import com.linkroa.deepdataagent.runtime.api.CoordinationLeaseApi;
import com.linkroa.deepdataagent.runtime.application.service.CoordinationLeaseService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

/**
 * 协调层租约跨 BC 服务契约的进程内实现（{@link CoordinationLeaseApi}）。
 *
 * <p>薄适配：委托 {@link CoordinationLeaseService} 完成调度触发防重租约的获取 / 释放。
 * 未来接入 Feign 时移除本实现、接口追加 {@code @FeignClient} 即可，消费方无需改动。</p>
 */
@Component
public class DefaultCoordinationLeaseApi implements CoordinationLeaseApi {

    @Resource
    private CoordinationLeaseService coordinationLeaseService;

    @Override
    public boolean tryAcquireFireLease(String schedulerId) {
        return coordinationLeaseService.tryAcquireFireLease(schedulerId);
    }

    @Override
    public void releaseFireLease(String schedulerId) {
        coordinationLeaseService.releaseFireLease(schedulerId);
    }
}
