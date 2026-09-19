package com.linkroa.deepdataagent.file.application.port;

/**
 * 运行时产出物写入端口（进程内出站端口，敏感 / 重载荷跨 BC 传递）。
 * <p>工具 / 技能 / Agent 执行产出（含内容字节）MUST 经本端口登记为 File 一等资源：
 * purpose 限定产出三态（{@code tool_output} / {@code skill_output} / {@code agent_output}），
 * scope 携带产出会话（{@code {id: sess_..., type: session}}），内容落盘与元数据入库
 * 由 file BC 仓储保证同写。Session 删除（终止）时经本端口清理其 scope=session 文件
 * （同删元数据与磁盘内容）。byte[] 内容属重载荷，不进入 {@code api} 服务契约面。</p>
 */
public interface ArtifactRegistrationPort {

    /**
     * 登记会话产出文件（落盘 + 入库，purpose 仅接受产出三态）。
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
