package com.linkroa.deepdataagent.runtime.domain.model;

import org.apache.commons.lang3.StringUtils;

import java.util.Set;
import java.util.UUID;

/**
 * 会话挂载资源值对象（对应 Session {@code resources} 数组项，以 jsonb 落库于 agent_session.resources）。
 * <p>Session 资源契约四类型（扁平结构，按 {@code type} 取用对应字段，其余字段为 null）：</p>
 * <ul>
 *   <li>{@code file}：{@code fileId}（前缀 {@code file_}）+ 可选 {@code mountPath}，
 *       缺省挂载路径 {@code mounts/<file_id>}，自定义必须为 {@code mounts/} 前缀的
 *       工作区相对路径（沙箱内可见路径 = {@code /workspace/<mount_path>}，只读）；</li>
 *   <li>{@code github_repository}：{@code url} 必填 + 只写 {@code authorizationToken}
 *       （MUST NOT 出现在任何响应中）+ 可选 {@code checkout}（检出分支 / 标签）；</li>
 *   <li>{@code git_repository}：{@code url} 必填（用户名 MUST 体现在 URL 中）
 *       + 只写 {@code password}（与用户名成对提供，MUST NOT 出现在任何响应中）
 *       + 可选 {@code checkout}（检出分支 / 标签）；</li>
 *   <li>{@code memory_store}：{@code memoryStoreId}（前缀 {@code ms_}）必填
 *       + 可选 {@code access}（{@code read_write / read_only}，缺省不覆盖）+ 可选 {@code instructions}（≤4096 字符）。</li>
 * </ul>
 *
 * <p>资源项具有独立业务 ID（{@code id}，前缀 {@code sesr_}，资源对象契约）：
 * 创建时未提供则由紧凑构造器自动生成，落库后保持稳定。</p>
 *
 * @param id                 资源业务 ID（前缀 {@code sesr_}，空则自动生成）
 * @param type               资源类型（{@code file / github_repository / git_repository / memory_store}）
 * @param fileId             文件资源：已上传文件业务 ID（前缀 {@code file_}）
 * @param mountPath          文件资源：挂载路径（沙箱工作区相对路径；空则缺省 {@code mounts/<file_id>}；
 *                           非空必须 {@code mounts/} 前缀，不含绝对路径 / {@code ..} / 反斜杠 / 空路径段）
 * @param url                仓库资源：仓库地址（git_repository 时用户名 MUST 体现在 URL 中）
 * @param authorizationToken GitHub 资源：访问令牌（只写不读，响应与日志 MUST NOT 泄露）
 * @param password           通用 Git 资源：密码（只写不读，响应与日志 MUST NOT 泄露；与 URL 中用户名成对）
 * @param checkout           仓库资源：检出分支 / 标签（可空）
 * @param memoryStoreId      记忆库资源：记忆库业务 ID（前缀 {@code ms_}）
 * @param access             记忆库资源：访问模式（{@code read_write / read_only}，可空=不覆盖）
 * @param instructions       记忆库资源：挂载级附加指令（可空，≤4096 字符）
 */
public record SessionResource(
        String id,
        String type,
        String fileId,
        String mountPath,
        String url,
        String authorizationToken,
        String password,
        String checkout,
        String memoryStoreId,
        String access,
        String instructions
) {

    /** 资源项业务 ID 前缀（契约 {@code sesr_} 资源标识）。 */
    public static final String RESOURCE_ID_PREFIX = "sesr_";

    /** 文件资源类型标识（{@code resources[]} 项 {@code type=file} 契约词汇）。 */
    public static final String FILE_TYPE = "file";

    /** GitHub 仓库资源类型标识。 */
    public static final String GITHUB_REPO_TYPE = "github_repository";

    /** 通用 Git 仓库资源类型标识（用户名体现在 URL、密码只写）。 */
    public static final String GIT_REPO_TYPE = "git_repository";

    /** 记忆库资源类型标识。 */
    public static final String MEMORY_STORE_TYPE = "memory_store";

    /** 会话挂载根目录名（宿主 mounts 目录与沙箱 {@code /workspace/mounts} 的共用段，bind mount 的 key）。 */
    public static final String MOUNTS_ROOT = "mounts";

    /** 文件挂载缺省路径前缀（缺省挂载路径 = 本前缀 + file_id，工作区相对路径）。 */
    public static final String DEFAULT_MOUNT_PATH_PREFIX = MOUNTS_ROOT + "/";

    /** 记忆库挂载访问模式取值。 */
    public static final Set<String> MEMORY_STORE_ACCESS_MODES = Set.of("read_write", "read_only");

    /** 记忆库挂载级附加指令长度上限（契约：≤4096 字符，超限 400）。 */
    public static final int MAX_MEMORY_STORE_INSTRUCTIONS_LENGTH = 4096;

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
            case GITHUB_REPO_TYPE -> {
                if (StringUtils.isBlank(url)) {
                    throw new IllegalArgumentException("GitHub 仓库地址不能为空");
                }
            }
            case GIT_REPO_TYPE -> {
                if (StringUtils.isBlank(url)) {
                    throw new IllegalArgumentException("Git 仓库地址不能为空");
                }
                // 用户名 MUST 体现在 URL 中，密码与用户名必须成对提供
                if (!hasUrlUsername(url)) {
                    throw new IllegalArgumentException("Git 仓库地址必须包含用户名（https://<username>@host/...）: " + url);
                }
                if (StringUtils.isBlank(password)) {
                    throw new IllegalArgumentException("Git 仓库密码不能为空（与 URL 中用户名成对提供）");
                }
            }
            case MEMORY_STORE_TYPE -> {
                if (StringUtils.isBlank(memoryStoreId)) {
                    throw new IllegalArgumentException("记忆库ID不能为空");
                }
                if (!memoryStoreId.startsWith("ms_")) {
                    throw new IllegalArgumentException("记忆库ID非法: " + memoryStoreId);
                }
                if (access != null && !MEMORY_STORE_ACCESS_MODES.contains(access)) {
                    throw new IllegalArgumentException("记忆库访问模式非法: " + access);
                }
                if (instructions != null && instructions.length() > MAX_MEMORY_STORE_INSTRUCTIONS_LENGTH) {
                    throw new IllegalArgumentException("记忆库附加指令不能超过 "
                            + MAX_MEMORY_STORE_INSTRUCTIONS_LENGTH + " 字符");
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
     * URL 是否携带用户名（userinfo 段，形如 {@code https://<username>@host/path}）。
     * <p>用 {@link java.net.URI} 解析 authority 的 userinfo；非法 URL 视为不含用户名
     * （由上方 URL 形态校验兜底，不在此重复抛形态错误）。</p>
     *
     * @param url 仓库地址（非空）
     * @return true=URL 中携带用户名
     */
    private static boolean hasUrlUsername(String url) {
        try {
            java.net.URI uri = java.net.URI.create(url.trim());
            return StringUtils.isNotBlank(uri.getUserInfo());
        } catch (IllegalArgumentException ex) {
            return false;
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
        return new SessionResource(null, FILE_TYPE, fileId, mountPath,
                null, null, null, null, null, null, null);
    }

    /**
     * 创建 GitHub 仓库挂载资源项（令牌只写不读）。
     *
     * @param url                仓库地址
     * @param authorizationToken 访问令牌（可空）
     * @param checkout           检出分支 / 标签（可空）
     * @return 挂载资源值对象
     */
    public static SessionResource githubRepository(String url, String authorizationToken, String checkout) {
        return new SessionResource(null, GITHUB_REPO_TYPE, null, null,
                url, authorizationToken, null, checkout, null, null, null);
    }

    /**
     * 创建通用 Git 仓库挂载资源项（密码只写不读；用户名 MUST 体现在 URL 中且与密码成对）。
     *
     * @param url      仓库地址（须携带用户名）
     * @param password 密码（只写不读）
     * @param checkout 检出分支 / 标签（可空）
     * @return 挂载资源值对象
     */
    public static SessionResource gitRepository(String url, String password, String checkout) {
        return new SessionResource(null, GIT_REPO_TYPE, null, null,
                url, null, password, checkout, null, null, null);
    }

    /**
     * 创建记忆库挂载资源项。
     *
     * @param memoryStoreId 记忆库业务 ID（前缀 {@code ms_}）
     * @param access        访问模式（{@code read_write / read_only}，可空=不覆盖）
     * @param instructions  挂载级附加指令（可空）
     * @return 挂载资源值对象
     */
    public static SessionResource memoryStore(String memoryStoreId, String access, String instructions) {
        return new SessionResource(null, MEMORY_STORE_TYPE, null, null,
                null, null, null, null, memoryStoreId, access, instructions);
    }

    /**
     * 派生轮换访问令牌后的资源（资源管理 {@code update} 专用，不可变派生）。
     * <p>保留资源 {@code id} 与其余全部字段，仅覆盖 {@code authorizationToken}；
     * 新令牌非空不变量由应用层守卫（空令牌轮换拒绝，避免清空既有凭证）。</p>
     *
     * @param newToken 新访问令牌
     * @return 轮换后的资源值对象
     */
    public SessionResource withAuthorizationToken(String newToken) {
        return new SessionResource(id, type, fileId, mountPath, url, newToken, password, checkout,
                memoryStoreId, access, instructions);
    }
}
