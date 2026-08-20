package com.linkroa.deepdataagent.runtime.controller.request;

/**
 * 会话挂载文件资源项（对齐 Managed Agents {@code resources} 项）。
 *
 * @param type       固定为 {@code file}
 * @param file_id    已上传的文件 ID
 * @param mount_path 挂载路径（以 {@code /uploads/} 开头）
 */
public record SessionResourceRequest(
        String type,
        String file_id,
        String mount_path
) {
}