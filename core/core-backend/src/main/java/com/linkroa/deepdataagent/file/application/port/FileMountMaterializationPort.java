package com.linkroa.deepdataagent.file.application.port;

import com.linkroa.deepdataagent.file.application.dto.FileMountMaterializationDTO;

import java.nio.file.Path;
import java.util.Optional;

/**
 * 文件挂载物化端口（进程内出站端口，宿主磁盘载荷）。
 * <p>将归属用户「已就绪」的文件内容<b>流式复制</b>到调用方给定的宿主目标路径
 * （边复制边校验 SHA-256，字节不经堆），返回物化结果 {@link FileMountMaterializationDTO}，
 * 供 runtime BC 把会话挂载落到沙箱 bind mount 可见的宿主目录。</p>
 * <p><b>为何不入 {@code FileApi}</b>：入参携带宿主 {@link Path}、语义是磁盘字节物化，
 * 属「不可跨网络载荷」——一旦置于 {@code api}（未来 client jar 导出面），Feign 化即等于
 * 让内部 RPC 承诺宿主文件系统语义。故元数据查询走 {@code FileApi}，字节物化走本端口。
 * 与 {@code VaultCredentialResolutionPort}（明文凭证 materialization）同一语义定位。</p>
 * <p>由同 BC {@code infrastructure} 的 {@code DefaultFileMountMaterializationPort} 进程内实现，
 * <b>仅进程内依赖倒置，不 Feign 化</b>。</p>
 */
public interface FileMountMaterializationPort {

    /**
     * 将归属用户已就绪文件流式物化到宿主目标路径。
     * <p>owner 归属与 ready 门禁复用 {@code File.mountableBy} 领域谓词；文件不存在、
     * 非本人 owner、未就绪、磁盘内容缺失，统一返回 {@link Optional#empty()}
     * （不泄露文件存在性）。摘要校验不符时删除残缺副本并抛
     * {@code FileContentIntegrityException}（一致性事故不允许以「无副本」静默呈现）。</p>
     *
     * @param fileId     文件业务 ID（前缀 {@code file_}）
     * @param ownerId    归属用户 ID
     * @param targetFile 宿主目标文件绝对路径（父目录不存在时由实现创建）
     * @return 物化结果载荷；不可物化时返回 {@link Optional#empty()}
     */
    Optional<FileMountMaterializationDTO> materialize(String fileId, Long ownerId, Path targetFile);
}
