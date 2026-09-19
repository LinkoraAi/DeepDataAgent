package com.linkroa.deepdataagent.agent.controller.request;

/**
 * 调度器触发会话挂载资源项（格式对齐会话创建契约 {@code resources} 数组项，
 * 扁平结构按类型取用字段；触发时透传 runtime 侧解释装配）。
 *
 * @param type                资源类型（{@code file / github_repository / memory_store}）
 * @param file_id             文件资源：已上传文件业务 ID（前缀 {@code file_}）
 * @param mount_path          文件资源：挂载路径（可空，缺省自动补 {@code mounts/<file_id>}；
 *                            自定义必须为 {@code mounts/} 前缀的工作区相对路径）
 * @param url                 GitHub 资源：仓库地址
 * @param authorization_token GitHub 资源：访问令牌（只写不读）
 * @param checkout            GitHub 资源：检出分支 / 标签（可空）
 * @param memory_store_id     记忆库资源：记忆库业务 ID（前缀 {@code ms_}）
 * @param access              记忆库资源：访问模式（可空=不覆盖）
 * @param instructions        记忆库资源：挂载级附加指令（可空）
 */
public record DeploymentResourceRequest(
        String type,
        String file_id,
        String mount_path,
        String url,
        String authorization_token,
        String checkout,
        String memory_store_id,
        String access,
        String instructions
) {
}
