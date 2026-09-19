package com.linkroa.deepdataagent.runtime.domain.event;

import org.apache.commons.lang3.StringUtils;

/**
 * 产物交付信号（领域中性事实载体，5.4）。
 * <p>沙箱交付工具（Harness 内置 {@code deliver_artifact}）经平台侧
 * {@code ArtifactDeliveryTarget} 登记成功一个文件后上抛本信号，由应用层落库并推送一条
 * {@code agent.artifact_delivered} 事件（逐文件一条、保序）。信号只承载「已交付」事实的四个公开字段
 * （file_id / original_filename / size / content_type）+ 交付会话，不含任何文件内容字节
 * （重载荷止步于登记端口，不进事件装配面）。</p>
 * <p>交付失败不产生本信号（失败经工具结果文本表达）。</p>
 *
 * @param sessionId        交付会话业务 ID（前缀 sess_）
 * @param fileId           交付产物的文件业务 ID（前缀 file_）
 * @param originalFilename 原始文件名（框架校验后的纯文件名）
 * @param sizeBytes        交付字节数
 * @param contentType      内容类型（产出登记平面按扩展名探测）
 */
public record ArtifactDeliveredSignal(
        String sessionId,
        String fileId,
        String originalFilename,
        long sizeBytes,
        String contentType
) {

    /**
     * 紧凑构造器：契约不变量校验。
     *
     * @throws IllegalArgumentException 会话 ID / 文件 ID / 文件名 / 内容类型空白，或字节数为负
     */
    public ArtifactDeliveredSignal {
        if (StringUtils.isBlank(sessionId)) {
            throw new IllegalArgumentException("交付会话ID(sessionId)不能为空");
        }
        if (StringUtils.isBlank(fileId)) {
            throw new IllegalArgumentException("交付文件ID(fileId)不能为空");
        }
        if (StringUtils.isBlank(originalFilename)) {
            throw new IllegalArgumentException("交付文件名(originalFilename)不能为空");
        }
        if (sizeBytes < 0) {
            throw new IllegalArgumentException("交付字节数不能为负: " + sizeBytes);
        }
        if (StringUtils.isBlank(contentType)) {
            throw new IllegalArgumentException("交付内容类型(contentType)不能为空");
        }
    }
}