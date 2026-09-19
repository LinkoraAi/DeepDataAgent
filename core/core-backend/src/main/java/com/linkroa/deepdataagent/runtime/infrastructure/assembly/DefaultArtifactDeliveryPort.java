package com.linkroa.deepdataagent.runtime.infrastructure.assembly;

import com.linkroa.deepdataagent.file.application.port.ArtifactRegistrationPort;
import com.linkroa.deepdataagent.runtime.application.port.ArtifactDeliveryPort;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

/**
 * {@link ArtifactDeliveryPort} 进程内实现（fix-runtime-layering 4.2，design D3）：
 * runtime BC 内唯一直接依赖 file BC {@code ArtifactRegistrationPort} 的装配适配器，
 * 会话产出的登记 / 清理委托 file BC 端口，登记落盘与 scope 清理语义的权威在 file BC。
 * <p>纯委托、零逻辑：产出三态白名单、50MB / 文本准入、逐文件删除容错等
 * 均在 file BC 端口实现内保证，本类原样透传（含 {@code IllegalArgumentException}
 * 的 400 语义，供交付目标转可读失败结果）。</p>
 */
@Component
public class DefaultArtifactDeliveryPort implements ArtifactDeliveryPort {

    @Resource
    private ArtifactRegistrationPort artifactRegistrationPort;

    @Override
    public String registerSessionArtifact(String sessionId, Long ownerId, String purposeCode,
                                          String filename, byte[] content) {
        return artifactRegistrationPort.registerSessionArtifact(
                sessionId, ownerId, purposeCode, filename, content);
    }

    @Override
    public int deleteSessionArtifacts(String sessionId) {
        return artifactRegistrationPort.deleteSessionArtifacts(sessionId);
    }
}
