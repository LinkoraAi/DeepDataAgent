package com.linkroa.deepdataagent.rag.controller.rest;

import com.linkroa.deepdataagent.rag.application.contract.RetrievalMultimodalOptions;
import com.linkroa.deepdataagent.rag.application.contract.RetrievalQuery;
import com.linkroa.deepdataagent.rag.application.service.RetrievalApplicationService;
import com.linkroa.deepdataagent.rag.application.validation.QueryImageValidator;
import com.linkroa.deepdataagent.rag.controller.convert.RetrievalResponseConvert;
import com.linkroa.deepdataagent.rag.controller.request.RetrievalRequest;
import com.linkroa.deepdataagent.rag.controller.response.RetrievalAnswerResponse;
import com.linkroa.deepdataagent.rag.controller.response.RetrievalChunksResponse;
import com.linkroa.deepdataagent.rag.domain.port.LlmImage;
import com.linkroa.deepdataagent.shared.constant.api.ApiVersionConstants;
import com.linkroa.deepdataagent.shared.exception.InvalidQueryImageException;
import com.linkroa.deepdataagent.shared.result.ApiResponse;
import jakarta.validation.Valid;
import org.apache.commons.lang3.ObjectUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 检索能力对外 REST 控制器（检索唯一对外 HTTP 入口，两种返回形态各占一个子端点）。
 * <p>实际暴露（随仓库 URL 版本化规则）：
 * {@code POST /api/v1/rag/retrievals/answer} —— 只返回 LLM 最终答案，无引用/明细/降级等附带信息；
 * {@code POST /api/v1/rag/retrievals/chunks} —— 只返回精排后全量命中切片，不作答。</p>
 * <p>薄层协议适配：① 附图先经共享校验器 {@link QueryImageValidator} 校验解码（任一非法抛
 * {@link InvalidQueryImageException}，由全局异常处理映射真实 HTTP 400，校验先于任何检索/模型调用）；
 * ② 包装类型开关缺省归一为 true 后组装多模态参数值对象，与其余参数一并构造
 * {@link RetrievalQuery} 契约下传（两端点共用同一请求体与同一条组装口径）；
 * ③ 调用编排应用服务并经 {@link RetrievalResponseConvert} 转换为对应形态响应。
 * 通路 A/B 的开关消费与阶段裁剪均在 application/domain 层生效点完成，本层不含任何判断逻辑。</p>
 *
 * @author DeepDataAgent
 */
@RestController
@RequestMapping(path = "/rag/retrievals", version = ApiVersionConstants.CURRENT_API_VERSION)
public class RetrievalController {

    /** 检索编排应用服务（Stage 0~5 全链路入口，本层按其答案/切片两个形态方法下传） */
    private final RetrievalApplicationService retrievalApplicationService;

    /**
     * 构造检索能力对外 REST 控制器。
     *
     * @param retrievalApplicationService 检索编排应用服务（Stage 0~5 全链路入口）
     */
    public RetrievalController(RetrievalApplicationService retrievalApplicationService) {
        this.retrievalApplicationService = retrievalApplicationService;
    }

    /**
     * 检索并只返回 LLM 最终答案（同步）。
     * <p>链路跑完 Stage 0~5；引用列表、命中切片与降级标记均不随响应发布，
     * 降级仅留痕于服务端检索摘要日志。</p>
     *
     * @param request 检索请求体（kbId/query 必填，预算与开关可选，images 可选内联附图）
     * @return 统一包装的答案形态响应（数据体只含 {@code answer}，答案生成降级时为 {@code null}）
     * @throws InvalidQueryImageException 任一附图非法（数量/字节/类型白名单/base64/魔数），整请求拒绝
     */
    @PostMapping("/answer")
    public ApiResponse<RetrievalAnswerResponse> retrieveAnswer(@Valid @RequestBody RetrievalRequest request) {
        String answer = retrievalApplicationService.generateAnswer(toQuery(request));
        return ApiResponse.success(RetrievalResponseConvert.INSTANCE.toAnswerResponse(answer));
    }

    /**
     * 检索并只返回命中切片（同步，不作答）。
     * <p>链路跑至 Stage 3 精排即止，跳过上下文构建与答案生成，故响应条数不受 token 预算裁剪；
     * 请求体沿用同一 DTO，其中 token 预算分量与通路 B 开关（{@code answerImageDirectRead}）
     * 在本端点不参与决策，传入即忽略（宽容式，不拒绝请求）。</p>
     *
     * @param request 检索请求体（kbId/query 必填，关键词与召回数量可选，images 可选内联附图）
     * @return 统一包装的切片形态响应（数据体只含 {@code chunks}，按相关性序、空表非 {@code null}）
     * @throws InvalidQueryImageException 任一附图非法（数量/字节/类型白名单/base64/魔数），整请求拒绝
     */
    @PostMapping("/chunks")
    public ApiResponse<RetrievalChunksResponse> retrieveChunks(@Valid @RequestBody RetrievalRequest request) {
        return ApiResponse.success(RetrievalResponseConvert.INSTANCE.toChunksResponse(
                retrievalApplicationService.retrieveChunks(toQuery(request))));
    }

    /**
     * 请求体 → 检索执行契约（两端点共用的唯一组装口径）。
     * <p>附图校验解码与开关缺省归一在此完成：校验先于任何检索与模型调用，
     * 开关缺省视为 true（维持条件自动触发基线）。</p>
     *
     * @param request 检索请求体（已通过 jakarta 必填校验）
     * @return 检索执行契约（预算字段 null 由契约紧凑构造器归一为默认值）
     * @throws InvalidQueryImageException 任一附图非法
     */
    private RetrievalQuery toQuery(RetrievalRequest request) {
        List<LlmImage> images = QueryImageValidator.validateAndDecode(request.images());
        RetrievalMultimodalOptions options = new RetrievalMultimodalOptions(
                ObjectUtils.defaultIfNull(request.queryImageTranscribe(), Boolean.TRUE),
                ObjectUtils.defaultIfNull(request.answerImageDirectRead(), Boolean.TRUE));
        // 图谱召回上限（关联边 Top / 每源 chunk 数）不入 REST 契约：请求体不承载，恒传 null
        // 由 RetrievalQuery 紧凑构造器回落 RetrievalConstants 默认值（与常量化时代行为逐字一致）
        return new RetrievalQuery(request.kbId(), request.query(), request.hlKeywords(), request.llKeywords(),
                request.topK(), request.chunkTopK(), request.maxTotalTokens(), request.maxEntityTokens(),
                request.maxRelationTokens(), images, options, null, null);
    }
}
