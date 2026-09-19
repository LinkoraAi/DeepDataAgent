package com.linkroa.deepdataagent.runtime.application.port;

/**
 * 会话产出投递出站端口（进程内依赖倒置，fix-runtime-layering 4.2，design D3）。
 * <p>沙箱产出登记为 scope=session File 与随会话删除的产出清理，经本端口收敛；
 * 实现 {@code infrastructure.assembly.DefaultArtifactDeliveryPort} 内部委托
 * file BC {@code ArtifactRegistrationPort}——runtime 各层不再直接依赖他 BC 端口
 * （他 BC application.port/dto 的 import 收敛到 assembly 适配器单点）。
 * byte[] 内容属重载荷，仅进程内流转、不进入任何 api 服务契约面。</p>
 */
public interface ArtifactDeliveryPort {

    /**
     * 登记会话产出文件（落盘 + 入库，用途仅接受产出三态
     * tool_output / skill_output / agent_output）。
     *
     * @param sessionId   产出会话业务 ID（前缀 sess_，登记为文件 scope）
     * @param ownerId     文件归属用户 ID（通常为会话 owner）
     * @param purposeCode 产出用途契约值（tool_output / skill_output / agent_output）
     * @param filename    文件名（扩展名参与 MIME 探测）
     * @param content     产出内容字节（非空、≤50MB、文本类准入）
     * @return 登记成功的文件业务 ID（前缀 file_）
     * @throws IllegalArgumentException 会话 ID / 文件名为空、用途非产出三态、
     *                                  内容为空 / 非文本 / 超 50MB（400 语义）
     */
    String registerSessionArtifact(String sessionId, Long ownerId, String purposeCode,
                                   String filename, byte[] content);

    /**
     * 清理指定会话的全部 scope=session 产出文件（元数据逻辑删除 + 磁盘内容删除）。
     * <p>幂等：无关联文件时返回 0；单文件磁盘清理失败不影响其余文件继续清理。</p>
     *
     * @param sessionId 会话业务 ID（前缀 sess_）
     * @return 成功清理（元数据删除生效）的文件数
     */
    int deleteSessionArtifacts(String sessionId);
}
