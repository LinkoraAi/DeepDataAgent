package com.linkroa.deepdataagent.file.infrastructure.assembly;

import com.linkroa.deepdataagent.file.application.dto.FileMountMaterializationDTO;
import com.linkroa.deepdataagent.file.application.port.FileContentPort;
import com.linkroa.deepdataagent.file.application.port.FileMountMaterializationPort;
import com.linkroa.deepdataagent.file.domain.model.File;
import com.linkroa.deepdataagent.file.domain.repository.FileRepository;
import com.linkroa.deepdataagent.shared.exception.FileContentIntegrityException;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * {@link FileMountMaterializationPort} 进程内实现（file BC 宿主物化出口，载荷永不 Feign 化）。
 * <p>先施加与 {@code DefaultFileApi} 同一道 {@link File#mountableBy(Long)} 领域门禁（owner 归属
 * + ready），再经 {@link FileContentPort#copyToHost} 从对象存储流式复制到宿主目标路径并拿到
 * 实际 SHA-256 摘要；归属 / 就绪 / 内容对象缺失统一收敛为 {@link Optional#empty()}（不泄露文件
 * 存在性）。</p>
 * <p><b>摘要不符</b>：删除残缺副本后抛 {@link FileContentIntegrityException}（500），
 * 而非返回空——数据一致性事故必须以显式失败呈现，不允许静默成「无副本」让上层留下半成品挂载。</p>
 * <p>复用领域谓词 {@link File#mountableBy} 而非 {@code DefaultFileApi} 的包级静态门禁：端口实现
 * 不得反向依赖会在 Feign 化时整体移除的契约实现（sandbox-workspace-file-mounts D7）。</p>
 */
@Slf4j
@Service
public class DefaultFileMountMaterializationPort implements FileMountMaterializationPort {

    @Resource
    private FileRepository fileRepository;

    @Resource
    private FileContentPort fileContentPort;

    @Override
    public Optional<FileMountMaterializationDTO> materialize(String fileId, Long ownerId, Path targetFile) {
        Optional<File> owned = findOwnedReady(fileId, ownerId);
        if (owned.isEmpty()) {
            return Optional.empty();
        }
        File file = owned.get();
        Optional<String> digest = fileContentPort.copyToHost(file.fileId(), targetFile);
        if (digest.isEmpty()) {
            log.error("文件一致性事故：记录存在但内容对象缺失（挂载物化）: fileId={}", file.fileId());
            return Optional.empty();
        }
        if (!file.contentSha256Matches(digest.get())) {
            deleteResidueQuietly(targetFile);
            throw new FileContentIntegrityException("挂载物化 SHA-256 校验不一致: " + file.fileId());
        }
        return Optional.of(toDTO(file, targetFile));
    }

    /**
     * 按 fileId 查找并施加「归属 + 就绪」领域门禁（任一不满足统一返回空，不触碰磁盘复制）。
     */
    private Optional<File> findOwnedReady(String fileId, Long ownerId) {
        if (fileId == null || ownerId == null) {
            return Optional.empty();
        }
        return fileRepository.findByFileId(fileId)
                .filter(file -> file.mountableBy(ownerId));
    }

    /** 删除残缺副本（尽力而为，失败仅告警，不覆盖摘要不符主异常）。 */
    private void deleteResidueQuietly(Path targetFile) {
        try {
            Files.deleteIfExists(targetFile);
        } catch (IOException ex) {
            log.warn("挂载物化残缺副本清理失败（不覆盖主异常）: target={}", targetFile, ex);
        }
    }

    /** 领域聚合 + 已就位副本实际字节数 → 物化结果载荷。 */
    private FileMountMaterializationDTO toDTO(File file, Path targetFile) {
        long sizeBytes;
        try {
            sizeBytes = Files.size(targetFile);
        } catch (IOException ex) {
            throw new UncheckedIOException("挂载物化副本体积读取失败: " + file.fileId(), ex);
        }
        return new FileMountMaterializationDTO(file.fileId(), sizeBytes, file.contentSha256(), targetFile);
    }
}
