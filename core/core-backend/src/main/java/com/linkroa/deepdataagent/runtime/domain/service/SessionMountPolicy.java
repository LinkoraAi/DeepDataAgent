package com.linkroa.deepdataagent.runtime.domain.service;

import com.linkroa.deepdataagent.runtime.domain.model.MountViolationException;
import com.linkroa.deepdataagent.runtime.domain.model.SessionResource;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 会话挂载规则（纯内存、零 IO）：判重 / 路径占用 / 就绪结论 / 总量配额的唯一事实源。
 * <p>创建路径（无既有挂载）与追加路径（既有 + 新增合并判定）复用同一套规则，
 * 状态码口径由应用层按入口映射（创建 400/404、追加 409/400）。</p>
 * <p><b>字节清单入参化</b>：文件字节数来自 file BC（应用层经
 * {@code FileApi.findReadyMountMeta} 材料化），聚合与策略 MUST NOT 触达仓储或跨 BC 端口，
 * 故入参是「已材料化的 {@code fileId → sizeBytes} 清单」；清单缺键即「无可用就绪元数据」，
 * 对既有项按「元数据缺失计 0」口径参与求和，对新增项判为不可挂载。</p>
 * <p><b>判定顺序与逐元素短路</b>：{@code fileId} 判重 → 挂载路径占用 → 就绪结论 → 配额累加，
 * 与规则搬迁前的循环顺序逐位一致（同一批内哪一条先报错的口径不变）。</p>
 */
public final class SessionMountPolicy {

    /** Session 挂载文件总量上限（500MB，契约会话挂载配额；既有 + 新增合并后超限即拒绝挂载）。 */
    public static final long MAX_MOUNTED_TOTAL_BYTES = 500L * 1024 * 1024;

    private SessionMountPolicy() {
    }

    /**
     * 追加项类型门禁：本期仅 {@code file} 类型资源可追加挂载
     * （{@code github_repository / memory_store} 只允许创建时挂载）。
     * <p>应用层在材料化字节清单前先调用本门禁（避免为必然非法的批次做无谓的就绪查询），
     * {@link #validateAppend} 内亦复用同一实现（聚合直调路径的兜底）。</p>
     *
     * @param additions 待追加的挂载资源（可空 = 无追加项）
     * @throws MountViolationException 存在非 file 类型条目
     */
    public static void requireFileOnly(List<SessionResource> additions) {
        if (additions == null) {
            return;
        }
        for (SessionResource addition : additions) {
            if (!SessionResource.FILE_TYPE.equals(addition.type())) {
                throw MountViolationException.appendTypeUnsupported(addition.type());
            }
        }
    }

    /**
     * 追加挂载校验（整批全有或全无：任一非法条目即抛错，调用方不得留下任何变更）。
     *
     * @param existingResources 既有挂载资源（含非 file 类型，内部过滤；创建路径传空列表）
     * @param additions         本批新增 file 类挂载资源（{@code mount_path} 已由领域构造器归一）
     * @param sizeBytesByFileId 已材料化的字节清单（既有 + 新增一次取齐；缺键语义见类注释）
     * @throws MountViolationException 类型非法 / 判重 / 路径占用 / 文件不可挂载 / 总量超限
     */
    public static void validateAppend(List<SessionResource> existingResources,
                                      List<SessionResource> additions,
                                      Map<String, Long> sizeBytesByFileId) {
        requireFileOnly(additions);
        Map<String, Long> sizes = sizeBytesByFileId == null ? Map.of() : sizeBytesByFileId;
        long totalBytes = 0L;
        Set<String> seenFileIds = new HashSet<>();
        Set<String> seenPaths = new HashSet<>();
        // 既有挂载：判重基准 + 累计字节（清单缺键计 0，与规则搬迁前口径一致）
        if (existingResources != null) {
            for (SessionResource existing : existingResources) {
                if (!SessionResource.FILE_TYPE.equals(existing.type())) {
                    continue;
                }
                seenFileIds.add(existing.fileId());
                seenPaths.add(existing.mountPath());
                Long existingBytes = sizes.get(existing.fileId());
                totalBytes += existingBytes == null ? 0L : existingBytes;
            }
        }
        if (additions == null) {
            return;
        }
        for (SessionResource addition : additions) {
            if (!seenFileIds.add(addition.fileId())) {
                throw MountViolationException.duplicateFile(addition.fileId());
            }
            if (!seenPaths.add(addition.mountPath())) {
                throw MountViolationException.duplicateMountPath(addition.mountPath());
            }
            Long sizeBytes = sizes.get(addition.fileId());
            if (sizeBytes == null) {
                throw MountViolationException.fileNotMountable(addition.fileId());
            }
            totalBytes += sizeBytes;
            if (totalBytes > MAX_MOUNTED_TOTAL_BYTES) {
                throw MountViolationException.quotaExceeded(addition.fileId());
            }
        }
    }
}
