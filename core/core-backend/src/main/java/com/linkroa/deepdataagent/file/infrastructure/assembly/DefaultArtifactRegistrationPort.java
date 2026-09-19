package com.linkroa.deepdataagent.file.infrastructure.assembly;

import com.linkroa.deepdataagent.file.application.port.ArtifactRegistrationPort;
import com.linkroa.deepdataagent.file.application.port.FileContentPort;
import com.linkroa.deepdataagent.file.domain.model.File;
import com.linkroa.deepdataagent.file.domain.model.FileScope;
import com.linkroa.deepdataagent.file.domain.model.enums.FilePurpose;
import com.linkroa.deepdataagent.file.domain.repository.FileRepository;
import com.linkroa.deepdataagent.file.domain.service.FileContentTypeDomainService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * 运行时产出物写入端口实现（进程内依赖倒置，file BC 承载）。
 * <p>登记：purpose 白名单限定产出三态（user_upload / session_resource 拒绝——非运行时产出），
 * MIME 探测走产出登记平面（允许任意字节，图片 / 办公文档等按扩展名映射具体 MIME，
 * 未知兜底 application/octet-stream），size / SHA-256 / 50MB 上限由 {@link File} 领域不变量派生校验，
 * scope 固定为产出会话；对象写入 + 元数据入库由 {@link FileContentPort} 保证同写同删。
 * 清理：经元数据仓储按 scope 检索后逐文件调用 {@link FileContentPort#deleteFile}
 * （元数据逻辑删除 + 对象删除），单文件异常记录后继续。</p>
 */
@Slf4j
@Service
public class DefaultArtifactRegistrationPort implements ArtifactRegistrationPort {

    @Resource
    private FileRepository fileRepository;
    @Resource
    private FileContentPort fileContentPort;
    @Resource
    private FileContentTypeDomainService contentTypeDomainService;

    @Override
    public String registerSessionArtifact(String sessionId, Long ownerId, String purposeCode,
                                          String filename, byte[] content) {
        if (StringUtils.isBlank(sessionId)) {
            throw new IllegalArgumentException("产出会话ID(sessionId)不能为空");
        }
        if (StringUtils.isBlank(filename)) {
            throw new IllegalArgumentException("产出文件名(filename)不能为空");
        }
        if (content == null || content.length == 0) {
            throw new IllegalArgumentException("产出文件内容不能为空");
        }
        FilePurpose purpose = FilePurpose.fromCode(purposeCode);
        if (!isArtifactPurpose(purpose)) {
            throw new IllegalArgumentException("产出登记仅支持 tool_output/skill_output/agent_output: " + purposeCode);
        }
        String mimeType = contentTypeDomainService.resolveArtifactMimeType(filename, content);
        File file = File.create(
                File.FILE_ID_PREFIX + UUID.randomUUID(),
                ownerId,
                filename,
                mimeType,
                purpose,
                FileScope.ofSession(sessionId),
                null,
                content);
        fileContentPort.register(file, content);
        log.info("运行时产出登记完成: fileId={}, sessionId={}, purpose={}, size={}",
                file.fileId(), sessionId, purpose.code(), file.sizeBytes());
        return file.fileId();
    }

    @Override
    public int deleteSessionArtifacts(String sessionId) {
        if (StringUtils.isBlank(sessionId)) {
            return 0;
        }
        List<File> scoped = fileRepository.findByScope(sessionId);
        int deleted = 0;
        for (File file : scoped) {
            try {
                deleted += fileContentPort.deleteFile(file.fileId());
            } catch (RuntimeException ex) {
                // 单文件清理失败不阻断其余产出清理（对象删除已在端口内尽力执行）
                log.warn("会话产出文件清理失败（继续处理其余文件）: fileId={}, sessionId={}",
                        file.fileId(), sessionId, ex);
            }
        }
        if (deleted > 0) {
            log.info("会话产出文件随删除清理: sessionId={}, deleted={}", sessionId, deleted);
        }
        return deleted;
    }

    /** 产出用途白名单：仅工具 / 技能 / Agent 执行产出三态。 */
    private static boolean isArtifactPurpose(FilePurpose purpose) {
        return purpose == FilePurpose.TOOL_OUTPUT
                || purpose == FilePurpose.SKILL_OUTPUT
                || purpose == FilePurpose.AGENT_OUTPUT;
    }
}
