package com.linkroa.deepdataagent.knowledgebase.infrastructure.assembly;

import com.linkroa.deepdataagent.knowledgebase.application.port.KbAssetStoragePort;
import com.linkroa.deepdataagent.shared.storage.ObjectMetadata;
import com.linkroa.deepdataagent.shared.storage.ObjectStorage;
import com.linkroa.deepdataagent.shared.storage.ObjectStorageErrorKind;
import com.linkroa.deepdataagent.shared.storage.ObjectStorageException;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Optional;

/**
 * {@link KbAssetStoragePort} 进程内实现：knowledgebase / rag 业务侧唯一接触
 * shared 对象存储技术类型的装配点。
 * <p>承担技术异常翻译与读取编排：</p>
 * <ul>
 *   <li>写入：字节流直透 {@link ObjectStorage#put}（流由技术端口消费与关闭）；</li>
 *   <li>读取：以 {@code list(对象键)} 过滤 key 严格相等精确取字节数（杜绝 {@code x.png}
 *       误配 {@code x.png.bak}），对象缺失（列举未命中或 get 抛 NOT_FOUND）统一翻译为
 *       {@link Optional#empty()}；其他技术异常原样上抛（fail-closed）；</li>
 *   <li>删除与清退：直透幂等删除与前缀清退，空白键 / 前缀在业务边界即拒绝。</li>
 * </ul>
 */
@Service
public class DefaultKbAssetStoragePort implements KbAssetStoragePort {

    /** shared 对象存储技术端口（桶固定于配置，启动期已就绪）。 */
    private final ObjectStorage objectStorage;

    /**
     * 构造知识库对象资产存储访问端口实现。
     *
     * @param objectStorage shared 对象存储技术端口（桶固定于配置，启动期已就绪）
     */
    public DefaultKbAssetStoragePort(ObjectStorage objectStorage) {
        this.objectStorage = objectStorage;
    }

    @Override
    public void putSource(String objectKey, byte[] content, String contentType) {
        putInternal(objectKey, content, contentType);
    }

    @Override
    public void putMedia(String objectKey, byte[] content, String contentType) {
        putInternal(objectKey, content, contentType);
    }

    @Override
    public Optional<OpenedObject> open(String objectKey) {
        if (StringUtils.isBlank(objectKey)) {
            throw new IllegalArgumentException("对象键不能为空");
        }
        Optional<ObjectMetadata> hit = objectStorage.list(objectKey).stream()
                .filter(metadata -> objectKey.equals(metadata.key()))
                .findFirst();
        if (hit.isEmpty()) {
            return Optional.empty();
        }
        long size = hit.get().size();
        try {
            return Optional.of(new OpenedObject(objectStorage.get(objectKey), size));
        } catch (ObjectStorageException ex) {
            // 列举与读取之间的竞态缺失同样翻译为「对象不存在」
            if (ex.kind() == ObjectStorageErrorKind.NOT_FOUND) {
                return Optional.empty();
            }
            throw ex;
        }
    }

    @Override
    public void delete(String objectKey) {
        if (StringUtils.isBlank(objectKey)) {
            throw new IllegalArgumentException("对象键不能为空");
        }
        // 技术端口删除幂等：对象不存在静默成功
        objectStorage.delete(objectKey);
    }

    @Override
    public void cleanupPrefix(String prefix) {
        if (StringUtils.isBlank(prefix)) {
            throw new IllegalArgumentException("清理前缀不能为空");
        }
        objectStorage.deletePrefix(prefix);
    }

    /**
     * 写入对象统一实现（源文件与媒体图片共用，语义差异由调用方键规划表达）。
     *
     * @param objectKey   对象键（非空白）
     * @param content     内容字节（非空）
     * @param contentType 内容类型（可为 null）
     */
    private void putInternal(String objectKey, byte[] content, String contentType) {
        if (StringUtils.isBlank(objectKey)) {
            throw new IllegalArgumentException("对象键不能为空");
        }
        if (ObjectUtils.isEmpty(content)) {
            throw new IllegalArgumentException("对象内容不能为空: " + objectKey);
        }
        try (InputStream in = new ByteArrayInputStream(content)) {
            objectStorage.put(objectKey, in, content.length, contentType);
        } catch (IOException ex) {
            // ByteArrayInputStream.close 不抛 IO，理论不可达
            throw new UncheckedIOException("对象内容流关闭失败: " + objectKey, ex);
        }
    }
}
