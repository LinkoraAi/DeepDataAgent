package com.linkroa.deepdataagent.file.infrastructure.assembly;

import com.linkroa.deepdataagent.file.api.FileApi;
import com.linkroa.deepdataagent.file.api.dto.FileMountMetaDTO;
import com.linkroa.deepdataagent.file.domain.model.File;
import com.linkroa.deepdataagent.file.domain.repository.FileRepository;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * {@link FileApi} 进程内实现（file BC 服务契约出口，仅元数据面）。
 * <p>经文件仓储按 fileId 查询并施加 {@link File#mountableBy(Long)} 领域谓词（owner 归属 +
 * ready 门禁）：越权、不存在或未就绪统一收敛为 {@code false} /
 * {@link Optional#empty()}，由调用方（runtime BC）映射 404，避免经契约泄露文件存在性。
 * 契约另提供 {@link #existsOwnedBy(String, Long)}（仅 owner 归属、不看 ready），
 * 供调用方在准入失败后区分「不存在 / 越权」（404）与「存在但未就绪」（409）。</p>
 * <p>本实现<b>不触碰磁盘</b>：挂载内容物化由同 BC 的
 * {@code DefaultFileMountMaterializationPort} 承担。
 * 两个实现各自调用同一领域谓词完成判定 —— 门禁规则只在领域层存在一份，因此不会漂移；
 * 刻意不把共享判定下沉为本类的静态方法，避免物化端口反向依赖一个
 * 「未来 Feign 化时会被整体移除」的契约实现类。</p>
 */
@Service
public class DefaultFileApi implements FileApi {

    @Resource
    private FileRepository fileRepository;

    @Override
    public boolean readyForMount(String fileId, Long ownerId) {
        return findOwnedReady(fileId, ownerId).isPresent();
    }

    @Override
    public boolean existsOwnedBy(String fileId, Long ownerId) {
        if (fileId == null || ownerId == null) {
            return false;
        }
        return fileRepository.findByFileId(fileId)
                .filter(file -> file.ownedBy(ownerId))
                .isPresent();
    }

    @Override
    public Optional<FileMountMetaDTO> findReadyMountMeta(String fileId, Long ownerId) {
        return findOwnedReady(fileId, ownerId)
                .map(file -> new FileMountMetaDTO(
                        file.fileId(),
                        file.filename(),
                        file.sizeBytes()));
    }

    /**
     * 按 fileId 查找并施加「归属 + 就绪」领域门禁（任一不满足统一返回空，不泄露存在性）。
     */
    private Optional<File> findOwnedReady(String fileId, Long ownerId) {
        if (fileId == null || ownerId == null) {
            return Optional.empty();
        }
        return fileRepository.findByFileId(fileId)
                .filter(file -> file.mountableBy(ownerId));
    }
}
