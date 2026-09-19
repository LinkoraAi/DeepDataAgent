package com.linkroa.deepdataagent.runtime.application.command;

/**
 * 会话挂载资源项（已归一的命令入参：创建会话 {@code resources[]} 与追加挂载请求体共用的扁平结构）。
 * <p>由接口层完成形态归一后下传（协议字段原样承载，按 {@code type} 取用对应字段），
 * 应用层只接收本类型而非原始 JSON，避免在 convert / validator 内重复解析请求体形态。</p>
 * <ul>
 *   <li>{@code file}：{@code file_id} 必填；{@code mount_path} 可空
 *       （缺省 {@code mounts/<file_id>}，自定义必须为 {@code mounts/} 前缀的工作区相对路径，
 *       沙箱内可见路径 = {@code /workspace/<mount_path>}、只读）；</li>
 *   <li>{@code github_repository}：{@code url} 必填；{@code authorization_token} 只写不读
 *       （MUST NOT 出现在任何响应中）；{@code checkout} 可空（检出分支 / 标签）；</li>
 *   <li>{@code git_repository}：{@code url} 必填（用户名 MUST 体现在 URL 中）；{@code password}
 *       只写不读（与用户名成对提供，MUST NOT 出现在任何响应中）；{@code checkout} 可空；</li>
 *   <li>{@code memory_store}：{@code memory_store_id} 必填（前缀 {@code ms_}，校验存在且归属当前用户）；
 *       {@code access} 可空（{@code read_write / read_only}）；{@code instructions} 可空。</li>
 * </ul>
 *
 * @param type                资源类型（{@code file / github_repository / git_repository / memory_store}）
 * @param file_id             文件资源：已上传文件业务 ID（前缀 {@code file_}）
 * @param mount_path          文件资源：挂载路径（可空，缺省自动补全）
 * @param url                 仓库资源：仓库地址（git_repository 时用户名 MUST 体现在 URL 中）
 * @param authorization_token GitHub 资源：访问令牌（只写不读）
 * @param password            通用 Git 资源：密码（只写不读；与 URL 中用户名成对）
 * @param checkout            仓库资源：检出分支 / 标签（可空）
 * @param memory_store_id     记忆库资源：记忆库业务 ID（前缀 {@code ms_}）
 * @param access              记忆库资源：访问模式（可空=不覆盖）
 * @param instructions        记忆库资源：挂载级附加指令（可空）
 */
public record SessionResourceItem(
        String type,
        String file_id,
        String mount_path,
        String url,
        String authorization_token,
        String password,
        String checkout,
        String memory_store_id,
        String access,
        String instructions
) {
}
