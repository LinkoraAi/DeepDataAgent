package com.linkroa.deepdataagent.rag.controller.request;

import com.linkroa.deepdataagent.rag.application.contract.QueryImageDTO;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 检索 REST 端点请求体（两形态端点共用同一请求契约）。
 * <p>字段覆盖 {@code RetrievalQuery} 内部执行契约的全部可外露组件：{@code kbId} 与
 * {@code query} 必填；关键词、召回数量与 token 预算可空（缺省走契约默认值归一规则，
 * 与进程内调用口径一致）；{@code images} 为可选内联附图（数量、单图字节、内容类型与
 * 真实性由共享校验器在 controller 入口同步校验，任一非法整请求真实 HTTP 400 拒绝）。
 * 两开关为包装类型：请求体缺省字段视为 {@code true}（维持条件自动触发
 * 行为），由 controller 归一后组装多模态参数值对象下传，契约内不出现三态。</p>
 * <p>切片形态端点（{@code /rag/retrievals/chunks}）不作答，故三个 token 预算分量与
 * {@code answerImageDirectRead} 在该端点不参与决策（传入即忽略，宽容式不拒绝）。</p>
 *
 * @param kbId                  所属知识库ID（必填，单库隔离维度）
 * @param query                 用户原始问题（必填，检索链路输入底稿）
 * @param hlKeywords            显式高层关键词（可选，非空时跳过 LLM 提取）
 * @param llKeywords            显式低层关键词（可选，非空时跳过 LLM 提取）
 * @param topK                  每通道向量召回数量（可选，缺省走契约默认值）
 * @param chunkTopK             chunk 召回数量（可选，缺省回退 topK）
 * @param maxTotalTokens        上下文总 token 预算（可选，缺省走契约默认值）
 * @param maxEntityTokens       实体上下文 token 预算（可选，缺省走契约默认值）
 * @param maxRelationTokens     关系上下文 token 预算（可选，缺省走契约默认值）
 * @param images                检索附图列表（可选，contentType + base64 内联形态，
 *                              复用共享校验器的 API 入参 record，避免同形双写）
 * @param queryImageTranscribe  通路 A（附图转译）开关（可选，缺省视为 true；
 *                              false 时忽略附图并 WARN，按纯文本检索）
 * @param answerImageDirectRead 通路 B（作答原图直读）开关（可选，缺省视为 true；
 *                              false 时作答跳过原图解析与直读，零对象存储读取）
 */
public record RetrievalRequest(

        @NotNull(message = "知识库ID不能为空")
        Long kbId,

        @NotBlank(message = "检索问题不能为空")
        String query,

        List<String> hlKeywords,

        List<String> llKeywords,

        Integer topK,

        Integer chunkTopK,

        Integer maxTotalTokens,

        Integer maxEntityTokens,

        Integer maxRelationTokens,

        List<QueryImageDTO> images,

        Boolean queryImageTranscribe,

        Boolean answerImageDirectRead
) {
}
