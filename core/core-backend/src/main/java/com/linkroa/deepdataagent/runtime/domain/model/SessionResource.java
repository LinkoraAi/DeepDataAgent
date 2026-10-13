package com.linkroa.deepdataagent.runtime.domain.model;

import org.apache.commons.lang3.StringUtils;

import java.util.UUID;

/**
 * 会话挂载资源值对象（对应 Session {@code resources} 数组项，以 jsonb 落库于 agent_session.resources）。
 * <p><b>本期仅支持 file 类型</b>（扁平结构，按 {@code type} 取用对应字段）：</p>
 * <ul>
 *   <li>{@code file}：{@code fileId}（前缀 {@code file_}）+ 可选 {@code mountPath}，
 *       缺省挂载路径 {@code mounts/<file_id>}，自定义必须为 {@code mounts/} 前缀的
 *       工作区相对路径（沙箱内可见路径 = {@code /workspace/<mount_path>}，只读）。</li>
 * </ul>
 * <p>非 file 类型（如 {@code github_repository} / {@code git_repository} / {@code memory_store}）
 * 一律拒绝（{@link IllegalArgumentException} → 400，仅支持 file 资源）。</p>
 *
 * <p>资源项具有独立业务 ID（{@code id}，前缀 {@code sesr_}，资源对象契约）：
 * 创建时未提供则由紧凑构造器自动生成，落库后保持稳定。</p>
 *
 * @param id        资源业务 ID（前缀 {@code sesr_}，空则自动生成）
 * @param type      资源类型（本期仅 {@code file}）
 * @param fileId    文件资源：已上传文件业务 ID（前缀 {@code file_}）
 * @param mountPath 文件资源：挂载路径（沙箱工作区相对路径；空则缺省 {@code mounts/<file_id>}；
 *                  非空必须 {@code mounts/} 前缀，不含绝对路径 / {@code ..} / 反斜杠 / 空路径段）
 */
public record SessionResource(
        String id,
        String type,
        String fileId,
        String mountPath
) {

    /** 资源项业务 ID 前缀（契约 {@code sesr_} 资源标识）。 */
    public static final String RESOURCE_ID_PREFIX = "sesr_";

    /** 文件资源类型标识（{@code resources[]} 项 {@code type=file} 契约词汇）。 */
    public static final String FILE_TYPE = "file";

    /** 会话挂载根目录名（宿主 mounts 目录与沙箱 {@code /workspace/mounts} 的共用段，bind mount 的 key）。 */
    public static final String MOUNTS_ROOT = "mounts";

    /** 文件挂载缺省路径前缀（缺省挂载路径 = 本前缀 + file_id，工作区相对路径）。 */
    public static final String DEFAULT_MOUNT_PATH_PREFIX = MOUNTS_ROOT + "/";

    /**
     * 紧凑构造器：按资源类型做领域不变量校验与缺省值归一。
     *
     * @throws IllegalArgumentException 类型非法 / 必填字段缺失 / 前缀或取值非法
     */
    public SessionResource {
        // 资源 ID 缺省自动生成（sesr_ + 32 位十六进制），旧 jsonb 行反查时同样补齐
        if (StringUtils.isBlank(id)) {
            id = RESOURCE_ID_PREFIX + UUID.randomUUID().toString().replace("-", "");
        }
        if (StringUtils.isBlank(type)) {
            throw new IllegalArgumentException("资源类型不能为空");
        }
        switch (type) {
            case FILE_TYPE -> {
                if (StringUtils.isBlank(fileId)) {
                    throw new IllegalArgumentException("资源文件ID不能为空");
                }
                if (!fileId.startsWith("file_")) {
                    throw new IllegalArgumentException("资源文件ID非法: " + fileId);
                }
                // mount_path 缺省 mounts/<file_id>；自定义必须为 mounts/ 前缀的工作区相对路径
                if (StringUtils.isBlank(mountPath)) {
                    mountPath = DEFAULT_MOUNT_PATH_PREFIX + fileId;
                } else {
                    validateMountPath(mountPath);
                }
            }
            default -> throw new IllegalArgumentException("不支持的资源类型: " + type);
        }
    }

    /**
     * 挂载路径不变量：必须为 {@code mounts/} 前缀的沙箱工作区相对路径，
     * 不得为绝对路径、不得含 {@code ..} / 反斜杠 / 空路径段、不得等于 {@code mounts} 本身。
     *
     * @param mountPath 待校验挂载路径（非空）
     * @throws IllegalArgumentException 违反任一形态约束
     */
    private static void validateMountPath(String mountPath) {
        if (mountPath.startsWith("/")) {
            throw new IllegalArgumentException("挂载路径必须为工作区相对路径（不得以 / 开头）: " + mountPath);
        }
        if (mountPath.contains("\\")) {
            throw new IllegalArgumentException("挂载路径不得包含反斜杠: " + mountPath);
        }
        if (mountPath.equals(MOUNTS_ROOT) || !mountPath.startsWith(DEFAULT_MOUNT_PATH_PREFIX)) {
            throw new IllegalArgumentException("挂载路径必须位于 mounts/ 目录下且不得等于 mounts 本身: " + mountPath);
        }
        // mounts/ 之后的余部逐段校验：拒绝空段（mounts//a、结尾 /）、当前段（.，归一后与既有路径
        // 指向同一物理目标、可绕过判重）与上级段（..）
        for (String segment : mountPath.substring(DEFAULT_MOUNT_PATH_PREFIX.length()).split("/", -1)) {
            if (segment.isEmpty()) {
                throw new IllegalArgumentException("挂载路径不得包含空路径段: " + mountPath);
            }
            if (".".equals(segment)) {
                throw new IllegalArgumentException("挂载路径不得包含当前目录段: " + mountPath);
            }
            if ("..".equals(segment)) {
                throw new IllegalArgumentException("挂载路径不得包含上级目录段: " + mountPath);
            }
        }
    }

    /**
     * 创建文件挂载资源项。
     *
     * @param fileId    已上传文件的业务 ID
     * @param mountPath 挂载路径（可空，缺省 {@code mounts/<file_id>}；非空必须 {@code mounts/} 前缀相对路径）
     * @return 挂载资源值对象
     */
    public static SessionResource file(String fileId, String mountPath) {
        return new SessionResource(null, FILE_TYPE, fileId, mountPath);
    }
}