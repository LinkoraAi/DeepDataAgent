package com.linkroa.deepdataagent.file.infrastructure.assembly;

import com.linkroa.deepdataagent.file.domain.repository.FileRepository;
import com.linkroa.deepdataagent.shared.storage.ObjectMetadata;
import com.linkroa.deepdataagent.shared.storage.ObjectStorage;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 文件孤儿对象启动对账器（file BC 自有命名空间，不跨 BC）。
 * <p>以低 phase {@link SmartLifecycle} 在 Web 容器放行流量前执行一次：列举 {@code files/}
 * 前缀，凡在 files 表<b>无存活行</b>（行不存在或已逻辑删除——本 BC 删除语义即内容同删）
 * 的对象，判定为崩溃窗口 / 补偿失败残留孤儿并删除。逐项容错（单条失败仅 WARN），
 * 无法解析为合法 {@code file_} ID 的 key 一律跳过 + WARN，绝不含糊删除。</p>
 */
@Component
public class FileObjectReconciler implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(FileObjectReconciler.class);

    /** 与启动恢复同级，早于 Web 服务器（phase 接近最大值）放行流量。 */
    private static final int RECONCILE_PHASE = Integer.MIN_VALUE;

    /** 文件对象 key 前缀。 */
    private static final String FILES_PREFIX = "files/";

    /** 合法 file_id 形态（file_ + UUID 字符集，长度有界）。 */
    private static final Pattern SAFE_FILE_ID = Pattern.compile("file_[A-Za-z0-9-]{1,64}");

    private final AtomicBoolean running = new AtomicBoolean(false);

    @Resource
    private ObjectStorage objectStorage;
    @Resource
    private FileRepository fileRepository;

    @Override
    public void start() {
        reconcileOnce();
        running.set(true);
    }

    @Override
    public void stop() {
        running.set(false);
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public int getPhase() {
        return RECONCILE_PHASE;
    }

    /**
     * 一次性对账入口（幂等；可由生命周期触发，也可测试显式调用）。
     */
    public void reconcileOnce() {
        List<ObjectMetadata> objects;
        try {
            objects = objectStorage.list(FILES_PREFIX);
        } catch (RuntimeException ex) {
            // 存储已在 ObjectStorage 装配期 ensureReady；列举失败不阻断启动，留待下次重启对账
            log.error("文件孤儿对象对账列举失败，本次跳过（不阻断启动）", ex);
            return;
        }
        int removed = 0;
        for (ObjectMetadata metadata : objects) {
            String fileId = parseFileId(metadata.key());
            if (fileId == null) {
                log.warn("文件孤儿对账跳过无法解析的对象 key: {}", metadata.key());
                continue;
            }
            try {
                if (fileRepository.findByFileId(fileId).isEmpty()) {
                    objectStorage.delete(metadata.key());
                    removed++;
                }
            } catch (RuntimeException ex) {
                // 逐项容错：单条判定 / 删除失败不影响其余对象对账
                log.warn("文件孤儿对象对账单项失败（继续处理其余）: key={}", metadata.key(), ex);
            }
        }
        if (removed > 0) {
            log.info("文件孤儿对象对账完成，回收无存活元数据的对象: count={}", removed);
        }
    }

    /**
     * 从对象 key 严格解析 file_id；非 {@code files/<file_id>} 单段形态返回 null。
     *
     * @param key 对象 key
     * @return 合法 file_id；无法精确解析返回 null
     */
    private static String parseFileId(String key) {
        if (key == null || !key.startsWith(FILES_PREFIX)) {
            return null;
        }
        String fileId = key.substring(FILES_PREFIX.length());
        // 仅允许单段（file_id 本身不含 /），且匹配 file_ + UUID 字符集
        if (fileId.indexOf('/') >= 0 || !SAFE_FILE_ID.matcher(fileId).matches()) {
            return null;
        }
        return fileId;
    }
}
