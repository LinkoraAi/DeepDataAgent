package com.linkroa.deepdataagent.shared.storage.local;

import com.linkroa.deepdataagent.shared.storage.ObjectMetadata;
import com.linkroa.deepdataagent.shared.storage.ObjectStorage;
import com.linkroa.deepdataagent.shared.storage.ObjectStorageErrorKind;
import com.linkroa.deepdataagent.shared.storage.ObjectStorageException;
import com.linkroa.deepdataagent.shared.storage.ObjectKeys;
import com.linkroa.deepdataagent.shared.storage.config.ObjectStorageProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 本地磁盘布局的 {@link ObjectStorage} 实现（单机 / 开发形态）。
 * <p>对象 key 直接映射为存储根下的相对路径（{@code <root>/<key>}）：</p>
 * <ul>
 *   <li>写入采用「临时文件 + 原子移动」，读者只会看到完整旧对象或完整新对象；</li>
 *   <li>key 经 {@link ObjectKeys} 校验并做路径规范化 containment 双重防穿越；</li>
 *   <li>{@link #list(String)} 以目录 walk 天然全量，无分页截断；</li>
 *   <li>对象缺失统一抛 {@link ObjectStorageErrorKind#NOT_FOUND} 技术异常。</li>
 * </ul>
 */
public class LocalDiskObjectStorage implements ObjectStorage {

    private static final Logger log = LoggerFactory.getLogger(LocalDiskObjectStorage.class);

    /** 存储根（绝对、规范化）。 */
    private final Path root;

    /**
     * @param properties 对象存储配置（取 local.root）
     */
    public LocalDiskObjectStorage(ObjectStorageProperties properties) {
        this.root = Path.of(properties.getLocal().getRoot()).toAbsolutePath().normalize();
    }

    @Override
    public void put(String key, InputStream content, long size, String contentType) {
        ObjectKeys.validateKey(key);
        Path target = resolvePath(key);
        Path tmp = target.resolveSibling(tmpName(target));
        boolean moved = false;
        try {
            Files.createDirectories(target.getParent());
            try (InputStream in = content) {
                Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            }
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            moved = true;
        } catch (IOException ex) {
            throw new ObjectStorageException(ObjectStorageErrorKind.IO_ERROR,
                    "本地对象写入失败: " + key, ex);
        } finally {
            if (!moved) {
                deleteQuietly(tmp, key);
            }
        }
    }

    @Override
    public InputStream get(String key) {
        ObjectKeys.validateKey(key);
        Path target = resolvePath(key);
        if (!Files.isRegularFile(target)) {
            throw new ObjectStorageException(ObjectStorageErrorKind.NOT_FOUND, "本地对象不存在: " + key);
        }
        try {
            return Files.newInputStream(target);
        } catch (IOException ex) {
            throw new ObjectStorageException(ObjectStorageErrorKind.IO_ERROR,
                    "本地对象读取失败: " + key, ex);
        }
    }

    @Override
    public List<ObjectMetadata> list(String prefix) {
        ObjectKeys.validatePrefix(prefix);
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        List<ObjectMetadata> result = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile).forEach(path -> {
                String objectKey = toObjectKey(path);
                if (objectKey.startsWith(prefix)) {
                    result.add(toMetadata(path, objectKey));
                }
            });
        } catch (IOException ex) {
            throw new ObjectStorageException(ObjectStorageErrorKind.IO_ERROR,
                    "本地对象列举失败: " + prefix, ex);
        }
        return result;
    }

    @Override
    public void delete(String key) {
        ObjectKeys.validateKey(key);
        Path target = resolvePath(key);
        try {
            // deleteIfExists 语义幂等：对象不存在时静默成功
            Files.deleteIfExists(target);
        } catch (IOException ex) {
            throw new ObjectStorageException(ObjectStorageErrorKind.IO_ERROR,
                    "本地对象删除失败: " + key, ex);
        }
    }

    @Override
    public void deletePrefix(String prefix) {
        ObjectKeys.validatePrefix(prefix);
        for (ObjectMetadata metadata : list(prefix)) {
            delete(metadata.key());
        }
        cleanupEmptyDirectories(prefix);
    }

    @Override
    public void ensureReady() {
        try {
            Files.createDirectories(root);
        } catch (IOException ex) {
            throw new ObjectStorageException(ObjectStorageErrorKind.IO_ERROR,
                    "本地对象存储根目录创建失败: " + root, ex);
        }
        log.warn("对象存储运行于本地磁盘布局(local)，仅支持单机 / 开发形态；多实例部署 MUST 改用 S3 系共享后端。root={}",
                root);
    }

    /**
     * 删除前缀下对象后，尽力清理前缀目录内的空子目录（对象模型不感知目录，仅保持磁盘整洁）。
     *
     * @param prefix key 前缀（以 / 结尾）
     */
    private void cleanupEmptyDirectories(String prefix) {
        Path prefixDir = resolvePathLoose(prefix);
        if (!Files.isDirectory(prefixDir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(prefixDir)) {
            walk.sorted(Comparator.reverseOrder())
                    .filter(Files::isDirectory)
                    .forEach(dir -> {
                        try {
                            Files.deleteIfExists(dir);
                        } catch (IOException ignored) {
                            // 非空目录删除失败属预期（仍含对象），静默跳过
                        }
                    });
        } catch (IOException ex) {
            throw new ObjectStorageException(ObjectStorageErrorKind.IO_ERROR,
                    "本地对象前缀空目录清理失败: " + prefix, ex);
        }
    }

    /**
     * key → 存储根内绝对路径（规范化 containment 校验，杜绝穿越）。
     *
     * @param key 对象 key
     * @return 根内绝对路径
     */
    private Path resolvePath(String key) {
        Path target = root.resolve(key).normalize();
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("对象 key 路径越出存储根: " + key);
        }
        return target;
    }

    /**
     * 前缀（可结尾 /）→ 目录路径，同样做 containment 校验。
     *
     * @param prefix key 前缀
     * @return 根内目录路径（可能尚不存在）
     */
    private Path resolvePathLoose(String prefix) {
        String trimmed = prefix.endsWith("/") ? prefix.substring(0, prefix.length() - 1) : prefix;
        Path target = root.resolve(trimmed).normalize();
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("对象前缀路径越出存储根: " + prefix);
        }
        return target;
    }

    /**
     * 绝对路径 → 相对对象 key（分隔符归一为 /）。
     *
     * @param path 存储根内文件绝对路径
     * @return 对象 key
     */
    private String toObjectKey(Path path) {
        return root.relativize(path).toString().replace('\\', '/');
    }

    /**
     * 文件路径 → 元数据 DTO（本地无 contentType / etag，置 null）。
     *
     * @param path      文件绝对路径
     * @param objectKey 对象 key
     * @return 元数据
     */
    private ObjectMetadata toMetadata(Path path, String objectKey) {
        try {
            return new ObjectMetadata(objectKey, Files.size(path), null,
                    Files.getLastModifiedTime(path).toInstant(), null);
        } catch (IOException ex) {
            throw new ObjectStorageException(ObjectStorageErrorKind.IO_ERROR,
                    "本地对象元数据读取失败: " + objectKey, ex);
        }
    }

    /**
     * 生成同级临时文件名（纳秒后缀避免并发碰撞）。
     *
     * @param target 目标文件
     * @return 临时文件名
     */
    private static String tmpName(Path target) {
        return target.getFileName().toString() + ".tmp-" + System.nanoTime();
    }

    /**
     * 尽力删除临时文件（失败仅告警，不覆盖主异常）。
     *
     * @param tmp 临时文件
     * @param key 关联对象 key（仅日志）
     */
    private static void deleteQuietly(Path tmp, String key) {
        try {
            Files.deleteIfExists(tmp);
        } catch (IOException cleanupEx) {
            log.warn("本地对象临时文件清理失败（不覆盖主异常）: key={}", key, cleanupEx);
        }
    }

    /**
     * 提供存储根（供诊断 / 测试）。
     *
     * @return 绝对存储根
     */
    public Path root() {
        return root;
    }
}
