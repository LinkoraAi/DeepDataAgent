package com.linkroa.deepdataagent.file.infrastructure.assembly;

import com.linkroa.deepdataagent.file.application.port.FileContentPort;
import com.linkroa.deepdataagent.file.domain.model.File;
import com.linkroa.deepdataagent.file.domain.repository.FileRepository;
import com.linkroa.deepdataagent.shared.storage.ObjectStorage;
import com.linkroa.deepdataagent.shared.storage.ObjectStorageErrorKind;
import com.linkroa.deepdataagent.shared.storage.ObjectStorageException;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

/**
 * {@link FileContentPort} 进程内实现：file BC 内唯一接触 shared 对象存储技术类型的装配点。
 * <p>承担对象 key 规划（恒为 {@code files/<file_id>}）、双载体编排与技术异常翻译：</p>
 * <ul>
 *   <li>登记：先 {@code put} 对象、后插元数据；元数据失败补偿删除已写对象并上抛；</li>
 *   <li>删除：先逻辑删元数据，再尽力删对象（对象失败仅 error 告警，不回滚元数据）；</li>
 *   <li>读取 / 物化：对象缺失（NOT_FOUND）翻译为 {@link Optional#empty()}，
 *       由调用方映射「记录在、内容缺」的一致性事故；物化流式复制 + SHA-256 + 原子就位。</li>
 * </ul>
 */
@Slf4j
@Service
public class DefaultFileContentPort implements FileContentPort {

    /** 文件对象 key 前缀（后接 file_id）。 */
    private static final String FILE_KEY_PREFIX = "files/";

    @Resource
    private FileRepository fileRepository;

    @Resource
    private ObjectStorage objectStorage;

    @Override
    public File register(File file, byte[] content) {
        String objectKey = objectKey(file.fileId());
        // 先写对象，后写元数据；任一元数据异常都补偿删除对象，避免无归属孤儿
        try (InputStream in = new ByteArrayInputStream(content)) {
            objectStorage.put(objectKey, in, content.length, file.mimeType());
        } catch (IOException ex) {
            // ByteArrayInputStream.close 不抛 IO，理论不可达
            throw new UncheckedIOException("文件内容流关闭失败: " + file.fileId(), ex);
        }
        try {
            return fileRepository.save(file);
        } catch (RuntimeException ex) {
            compensateDelete(objectKey, file.fileId());
            throw ex;
        }
    }

    @Override
    public int deleteFile(String fileId) {
        // 先逻辑删元数据，再删对象；对象删除失败不回滚元数据，仅记录供人工关注
        int rows = fileRepository.deleteByFileId(fileId);
        try {
            objectStorage.delete(objectKey(fileId));
        } catch (RuntimeException ex) {
            log.error("文件内容对象清理失败（需人工关注，元数据已逻辑删除）: fileId={}", fileId, ex);
        }
        return rows;
    }

    @Override
    public Optional<byte[]> readContent(String fileId) {
        try (InputStream in = objectStorage.get(objectKey(fileId))) {
            return Optional.of(in.readAllBytes());
        } catch (ObjectStorageException ex) {
            if (ex.kind() == ObjectStorageErrorKind.NOT_FOUND) {
                return Optional.empty();
            }
            throw ex;
        } catch (IOException ex) {
            throw new UncheckedIOException("文件内容对象读取失败: " + fileId, ex);
        }
    }

    @Override
    public Optional<String> copyToHost(String fileId, Path targetFile) {
        Path tmp = targetFile.resolveSibling(targetFile.getFileName() + ".tmp-" + System.nanoTime());
        boolean moved = false;
        try (InputStream source = openSource(fileId)) {
            if (source == null) {
                return Optional.empty();
            }
            Files.createDirectories(targetFile.getParent());
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = new DigestInputStream(source, digest)) {
                Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            }
            try {
                Files.move(tmp, targetFile, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(tmp, targetFile, StandardCopyOption.REPLACE_EXISTING);
            }
            moved = true;
            return Optional.of(HexFormat.of().formatHex(digest.digest()));
        } catch (ObjectStorageException ex) {
            if (ex.kind() == ObjectStorageErrorKind.NOT_FOUND) {
                return Optional.empty();
            }
            throw ex;
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 摘要计算不可用", ex);
        } catch (IOException ex) {
            throw new UncheckedIOException("挂载物化流式复制失败: " + fileId, ex);
        } finally {
            if (!moved) {
                deleteQuietly(tmp, fileId);
            }
        }
    }

    /**
     * 打开对象输入流；对象不存在返回 null（供 copyToHost 翻译为空）。
     *
     * @param fileId 文件业务 ID
     * @return 对象输入流；不存在返回 null
     */
    private InputStream openSource(String fileId) {
        try {
            return objectStorage.get(objectKey(fileId));
        } catch (ObjectStorageException ex) {
            if (ex.kind() == ObjectStorageErrorKind.NOT_FOUND) {
                return null;
            }
            throw ex;
        }
    }

    /**
     * 元数据入库失败后的对象补偿删除（补偿再失败仅告警，不覆盖主异常）。
     *
     * @param objectKey 对象 key
     * @param fileId    文件业务 ID（仅日志）
     */
    private void compensateDelete(String objectKey, String fileId) {
        try {
            objectStorage.delete(objectKey);
        } catch (RuntimeException deleteEx) {
            log.warn("元数据入库失败后补偿删除对象也失败（可能残留孤儿，待启动对账回收）: fileId={}",
                    fileId, deleteEx);
        }
    }

    /**
     * 尽力删除物化临时文件（失败仅告警，不覆盖主异常）。
     *
     * @param tmp    临时文件
     * @param fileId 文件业务 ID（仅日志）
     */
    private static void deleteQuietly(Path tmp, String fileId) {
        try {
            Files.deleteIfExists(tmp);
        } catch (IOException cleanupEx) {
            log.warn("挂载物化临时文件清理失败（不覆盖主异常）: fileId={}", fileId, cleanupEx);
        }
    }

    /**
     * file_id → 对象 key。
     *
     * @param fileId 文件业务 ID
     * @return 对象 key（{@code files/<file_id>}）
     */
    private static String objectKey(String fileId) {
        return FILE_KEY_PREFIX + fileId;
    }
}
