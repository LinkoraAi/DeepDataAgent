package com.linkroa.deepdataagent.file.application.port;

import com.linkroa.deepdataagent.file.domain.model.File;

import java.nio.file.Path;
import java.util.Optional;

/**
 * 文件内容双载体生命周期出站端口（file BC 业务语义端口，进程内依赖倒置）。
 * <p>统一承载「对象存储内容 + files 表元数据」的协同：登记（先写对象后入元数据、
 * 失败补偿）、删除（逻辑删元数据 + 删对象）、下载读取与挂载物化。对象 key 恒为
 * {@code files/<file_id>}，由实现侧规划，<b>不渗入领域模型与调用方</b>。</p>
 * <p>本端口不依赖 shared 对象存储技术类型：对象缺失统一以 {@link Optional#empty()}
 * 表达（「记录在、内容缺」的一致性事故语义由调用方映射），对象存储的其他技术异常
 * 不上抛为业务可恢复语义。</p>
 */
public interface FileContentPort {

    /**
     * 登记文件：先写内容对象，后写元数据；元数据失败时补偿删除已写对象并上抛。
     *
     * @param file    未持久化的文件聚合（已含由内容推导的 size / SHA-256）
     * @param content 文件原始字节
     * @return 持久化后的文件聚合（含主键与时间戳）
     */
    File register(File file, byte[] content);

    /**
     * 删除文件：元数据逻辑删除 + 内容对象删除（对象删除失败仅告警、不回滚元数据）。
     *
     * @param fileId 文件业务 ID
     * @return 元数据逻辑删除影响行数
     */
    int deleteFile(String fileId);

    /**
     * 读取文件全部内容字节（下载链路，单文件 ≤50MB）。
     *
     * @param fileId 文件业务 ID
     * @return 内容字节；元数据记录存在但对象缺失时返回 {@link Optional#empty()}，
     *         由调用方映射为一致性事故（500）
     */
    Optional<byte[]> readContent(String fileId);

    /**
     * 流式复制内容到宿主目标路径（挂载物化专用，字节不经堆）。
     * <p>边复制边算 SHA-256，写同级临时文件后原子移动就位；源对象缺失返回空。
     * 摘要由调用方与登记值比对（不符清理残缺副本并显式失败）。</p>
     *
     * @param fileId     文件业务 ID
     * @param targetFile 宿主目标文件绝对路径（父目录自动创建）
     * @return 复制内容的 SHA-256 hex；对象缺失返回 {@link Optional#empty()}
     */
    Optional<String> copyToHost(String fileId, Path targetFile);
}
