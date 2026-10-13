package com.linkroa.deepdataagent.runtime.application.command;

/**
 * 会话挂载资源项（已归一的命令入参：创建会话 {@code resources[]} 与追加挂载请求体共用的扁平结构）。
 * <p>由接口层完成形态归一后下传（协议字段原样承载，按 {@code type} 取用对应字段），
 * 应用层只接收本类型而非原始 JSON，避免在 convert / validator 内重复解析请求体形态。</p>
 * <p><b>本期仅支持 file 类型</b>：{@code file_id} 必填；{@code mount_path} 可空
 * （缺省 {@code mounts/<file_id>}，自定义必须为 {@code mounts/} 前缀的工作区相对路径，
 * 沙箱内可见路径 = {@code /workspace/<mount_path>}、只读）。
 * 非 file 类型（github_repository / git_repository / memory_store 等）在装配器按类型分派时拒绝（400）。</p>
 *
 * @param type       资源类型（本期仅 {@code file}）
 * @param file_id    文件资源：已上传文件业务 ID（前缀 {@code file_}）
 * @param mount_path 文件资源：挂载路径（可空，缺省自动补全）
 */
public record SessionResourceItem(
        String type,
        String file_id,
        String mount_path
) {
}