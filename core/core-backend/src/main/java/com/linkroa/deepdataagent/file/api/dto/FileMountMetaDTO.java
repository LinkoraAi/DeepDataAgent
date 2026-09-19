package com.linkroa.deepdataagent.file.api.dto;

import org.apache.commons.lang3.StringUtils;

/**
 * 文件挂载元数据发布语言 DTO（跨 BC 共享载体，纯 record 无业务逻辑）。
 * <p>供运行时在会话追加挂载文件时一次拿齐就绪文件的计费 / 配额字段
 * （字节数用于 Session 挂载总量校验），不携带文件内容（内容物化走
 * {@code file.application.port.FileMountMaterializationPort}，流式复制为宿主挂载副本、
 * 全文不进内存契约）；本类型经 {@code file.api} 服务契约提供，属可 Feign 化的轻量面。</p>
 *
 * @param fileId    文件业务 ID（前缀 file_）
 * @param filename  文件名
 * @param sizeBytes 文件字节数（用于挂载总量配额校验）
 */
public record FileMountMetaDTO(
        String fileId,
        String filename,
        long sizeBytes
) {

    /**
     * 紧凑构造器：契约不变量校验。
     *
     * @throws IllegalArgumentException 文件 ID / 文件名空白或字节数为负
     */
    public FileMountMetaDTO {
        if (StringUtils.isBlank(fileId)) {
            throw new IllegalArgumentException("文件ID不能为空");
        }
        if (StringUtils.isBlank(filename)) {
            throw new IllegalArgumentException("文件名不能为空");
        }
        if (sizeBytes < 0) {
            throw new IllegalArgumentException("文件字节数不能为负: " + sizeBytes);
        }
    }
}
