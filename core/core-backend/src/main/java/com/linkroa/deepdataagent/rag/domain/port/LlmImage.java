package com.linkroa.deepdataagent.rag.domain.port;

import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;

/**
 * LLM 多模态请求中的单张图片载荷。
 * <p>图片以<b>内联字节</b>形态随 {@link LlmChatRequest} 传递，由基础设施层编码为
 * {@code data:<contentType>;base64,<...>} URI 后进入 user message（不走预签名 URL，
 * 模型网关不可达内网对象存储）。字节参与缓存键摘要（SHA-256），因此同一图片内容
 * 必须产生同一键；数量与单图字节上限见 {@link MultimodalConstraints}，
 * 由调用侧在装载前校验并按超限跳过。</p>
 * <p>{@code contentType} 只做非空白校验、不强约束 {@code image/*} 前缀：MIME 白名单与
 * 魔数核验属于 API 消费侧职责（通路 A 入参校验），本值对象不承担该策略。</p>
 *
 * @param contentType 图片 MIME 类型（如 {@code image/jpeg}，必填）
 * @param content     图片原始字节（非空数组，构造时做防御性拷贝）
 * @author DeepDataAgent
 */
public record LlmImage(String contentType, byte[] content) {

    /**
     * 紧凑构造器：不变量校验与字节防御性拷贝（避免调用方后续修改数组污染缓存键语义）。
     */
    public LlmImage {
        if (StringUtils.isBlank(contentType)) {
            throw new IllegalArgumentException("图片 contentType 不能为空");
        }
        if (ObjectUtils.isEmpty(content)) {
            throw new IllegalArgumentException("图片 content 不能为空");
        }
        content = content.clone();
    }
}
