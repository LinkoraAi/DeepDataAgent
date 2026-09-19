package com.linkroa.deepdataagent.runtime.controller.request;

/**
 * 更新挂载资源请求（资源管理契约 {@code update}：仅 GitHub 仓库资源令牌轮换）。
 * <p>新令牌必须非空（空令牌轮换被拒，避免清空既有凭证）；令牌只写不读，
 * 任何响应与日志 MUST NOT 泄露新旧令牌。</p>
 *
 * @param authorization_token 新的 GitHub 访问令牌
 */
public record UpdateSessionResourceRequest(
        String authorization_token
) {
}
