package com.linkroa.deepdataagent.file.application.command;

/**
 * 上传文件命令（multipart 入口装配）。
 * <p>purpose 为契约原始字符串（五态，必填，缺省由应用服务解析为 400）；
 * mime_type 不在命令中——一律由服务端探测，不接受客户端申报值。</p>
 *
 * @param filename 文件名（必填，不含 / \ ..）
 * @param purpose  文件用途契约值（user_upload / tool_output / skill_output / session_resource / agent_output）
 * @param metadata 元数据 JSON 文本（可空，归一 {}，≤8KB）
 * @param content  文件原始字节内容（仅文本类，≤50MB）
 */
public record CreateFileCommand(
        String filename,
        String purpose,
        String metadata,
        byte[] content
) {
}
