package com.linkroa.deepdataagent.file.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linkroa.deepdataagent.file.application.command.CreateFileCommand;
import com.linkroa.deepdataagent.file.application.port.FileContentPort;
import com.linkroa.deepdataagent.file.application.query.ListFilesQuery;
import com.linkroa.deepdataagent.file.domain.model.File;
import com.linkroa.deepdataagent.file.domain.model.FileCursor;
import com.linkroa.deepdataagent.file.domain.model.enums.FilePurpose;
import com.linkroa.deepdataagent.file.domain.repository.FileRepository;
import com.linkroa.deepdataagent.file.domain.service.FileContentTypeDomainService;
import com.linkroa.deepdataagent.shared.exception.FileContentIntegrityException;
import com.linkroa.deepdataagent.shared.exception.ForbiddenException;
import com.linkroa.deepdataagent.shared.exception.ResourceNotFoundException;
import com.linkroa.deepdataagent.shared.security.AuthContext;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 文件应用服务：上传（multipart）/ 游标列表 / 详情 / 直接下载（owner 隔离 + downloadable 门禁）。
 * <p>purpose 五态必填（缺省 / 非法 → 400）；mime_type 服务端探测且仅接受文本类
 * （非文本 → 400）；metadata 必须为合法 JSON（≤8KB）；内容写对象存储 + 元数据入库
 * 由 {@link FileContentPort} 编排。不提供 Managed delete / search 端点（删除仅 Forward
 * 面，清理随 Session 生命周期承载）。</p>
 */
@Slf4j
@Service
public class FileApplicationService {

    /** metadata JSON 合法性校验用（只读树解析，不持有状态） */
    private static final ObjectMapper METADATA_READER = new ObjectMapper();

    @Resource
    private FileRepository fileRepository;
    @Resource
    private FileContentPort fileContentPort;
    @Resource
    private FileContentTypeDomainService contentTypeDomainService;

    /**
     * 上传文件（purpose 必填、mime 服务端探测、文本类准入、metadata JSON 校验）。
     *
     * @param command 上传命令（文件名 / 用途 / 元数据 / 内容字节）
     * @return 持久化后的文件聚合
     * @throws IllegalArgumentException purpose 缺省或非法 / 非文本内容 / metadata 非法（400 语义）
     */
    public File upload(CreateFileCommand command) {
        Long ownerId = AuthContext.requireUserId();
        FilePurpose purpose = FilePurpose.fromCode(command.purpose());
        byte[] content = command.content() == null ? new byte[0] : command.content();
        // 契约（files spec）：目录段剥离后落库；清理后为空 / 超 255 字节由领域构造器拒绝（400）
        String filename = File.sanitizeFilename(command.filename());
        String mimeType = contentTypeDomainService.resolveTextMimeType(filename, content);
        validateMetadataJson(command.metadata());
        File file = File.create(
                File.FILE_ID_PREFIX + UUID.randomUUID(),
                ownerId,
                filename,
                mimeType,
                purpose,
                null,
                command.metadata(),
                content);
        return fileContentPort.register(file, content);
    }

    /**
     * 游标分页列出当前用户文件（purpose / scope_id 过滤，{@code (created_at, id)} 升序）。
     *
     * @param query 列表查询（purposeCode / scopeId / cursor / size）
     * @return 分页结果（data 至多 {@code size} 条 + nextCursor）
     */
    public FilePage list(ListFilesQuery query) {
        Long ownerId = AuthContext.requireUserId();
        FilePurpose purpose = StringUtils.isBlank(query.purposeCode())
                ? null : FilePurpose.fromCode(query.purposeCode());
        FileCursor cursor = FileCursor.parse(query.cursor());
        int fetchLimit = query.size() + 1;
        List<File> fetched = fileRepository.findByFilters(
                ownerId, purpose, query.scopeId(), cursor, fetchLimit);
        boolean hasMore = fetched.size() > query.size();
        List<File> data = hasMore
                ? List.copyOf(fetched.subList(0, query.size()))
                : List.copyOf(fetched);
        String nextCursor = null;
        if (hasMore && !data.isEmpty()) {
            File last = data.get(data.size() - 1);
            nextCursor = FileCursor.of(last.createdAt(), last.id()).encode();
        }
        return new FilePage(data, nextCursor);
    }

    /**
     * 文件详情（元数据，owner 隔离，越权 / 缺失统一 404）。
     */
    public File getMeta(String fileId) {
        return findOwned(fileId);
    }

    /**
     * 下载文件内容（owner 隔离 + downloadable 门禁 + SHA-256 一致性校验）。
     *
     * @param fileId 文件业务 ID
     * @return 下载载体（文件名 / MIME / 内容字节）
     * @throws ResourceNotFoundException      文件不存在或越权（404 语义）
     * @throws ForbiddenException             purpose 派生 downloadable=false（403 语义）
     * @throws FileContentIntegrityException  记录存在但内容对象缺失 / 校验不一致（500 语义）
     */
    public FileDownload downloadContent(String fileId) {
        File file = findOwned(fileId);
        if (!file.downloadable()) {
            throw new ForbiddenException("该文件用途（" + file.purpose().code() + "）不支持直接下载");
        }
        Optional<byte[]> content = fileContentPort.readContent(file.fileId());
        if (content.isEmpty()) {
            log.error("文件一致性事故：记录存在但内容对象缺失（下载读取）: fileId={}", file.fileId());
            throw new FileContentIntegrityException("文件内容存储缺失");
        }
        if (!file.contentMatches(content.get())) {
            log.error("文件一致性事故：内容对象 SHA-256 校验不一致（下载读取）: fileId={}", file.fileId());
            throw new FileContentIntegrityException("文件内容校验不一致");
        }
        return new FileDownload(file.filename(), file.mimeType(), content.get());
    }

    /**
     * 找文件并执行 owner 归属比对（越权与不存在统一 404，不泄露存在性）。
     */
    private File findOwned(String fileId) {
        File file = fileRepository.findByFileId(fileId)
                .orElseThrow(() -> new ResourceNotFoundException("文件不存在"));
        if (!file.ownerId().equals(AuthContext.requireUserId())) {
            throw new ResourceNotFoundException("文件不存在");
        }
        return file;
    }

    /**
     * metadata JSON 合法性校验（空值放行由领域归一 {}；非法 JSON → 400 语义）。
     */
    private void validateMetadataJson(String metadata) {
        if (StringUtils.isBlank(metadata)) {
            return;
        }
        try {
            if (!METADATA_READER.readTree(metadata).isObject()) {
                throw new IllegalArgumentException("文件元数据必须为 JSON 对象");
            }
        } catch (IOException ex) {
            throw new IllegalArgumentException("文件元数据不是合法 JSON: " + ex.getMessage());
        }
    }

    /**
     * 游标分页结果。
     *
     * @param data       当前页数据（至多请求 size 条）
     * @param nextCursor 下一页游标（null 表示已到末页）
     */
    public record FilePage(List<File> data, String nextCursor) {
    }

    /**
     * 文件下载载体。
     *
     * @param filename 文件名（Content-Disposition 依据）
     * @param mimeType 内容类型（Content-Type 依据）
     * @param content  已通过一致性校验的内容字节
     */
    public record FileDownload(String filename, String mimeType, byte[] content) {
    }
}
