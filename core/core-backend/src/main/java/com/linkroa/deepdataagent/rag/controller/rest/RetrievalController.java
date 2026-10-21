package com.linkroa.deepdataagent.rag.controller.rest;

import com.linkroa.deepdataagent.knowledgebase.domain.model.RetrievalStrategyConfig;
import com.linkroa.deepdataagent.rag.application.contract.AnswerStream;
import com.linkroa.deepdataagent.rag.application.contract.RetrievalMultimodalOptions;
import com.linkroa.deepdataagent.rag.application.contract.RetrievalQuery;
import com.linkroa.deepdataagent.rag.application.service.RetrievalApplicationService;
import com.linkroa.deepdataagent.rag.application.validation.QueryImageValidator;
import com.linkroa.deepdataagent.rag.controller.convert.RetrievalResponseConvert;
import com.linkroa.deepdataagent.rag.controller.request.RetrievalRequest;
import com.linkroa.deepdataagent.rag.controller.response.RetrievalChunksResponse;
import com.linkroa.deepdataagent.rag.controller.stream.AnswerStreamSession;
import com.linkroa.deepdataagent.rag.domain.port.LlmImage;
import com.linkroa.deepdataagent.rag.infrastructure.config.RetrievalStreamProperties;
import com.linkroa.deepdataagent.shared.constant.api.ApiVersionConstants;
import com.linkroa.deepdataagent.shared.exception.InvalidQueryImageException;
import com.linkroa.deepdataagent.shared.result.ApiResponse;
import jakarta.validation.Valid;
import org.apache.commons.lang3.ObjectUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.concurrent.Executor;

/**
 * 检索能力对外 REST 控制器（检索唯一对外 HTTP 入口，两种返回形态各占一个子端点）。
 * <p>实际暴露（随仓库 URL 版本化规则）：
 * {@code POST /api/v1/rag/retrievals/answer} —— SSE 流式返回 LLM 最终答案；
 * {@code POST /api/v1/rag/retrievals/chunks} —— 只返回精排后全量命中切片，不作答。</p>
 * <p><b>答案端点（SSE）</b>：建流前在主线程完成请求体校验、附图解码校验与知识库可检索性闸门
 * （{@link RetrievalApplicationService#assertRetrievable(Long)}），任一失败按既有 JSON 错误信封
 * 返回真实 HTTP 状态码；全部通过后才创建 {@code SseEmitter}，把 Stage 0~5 与增量下发交给
 * 专用虚拟线程与 {@link AnswerStreamSession}。归属不符与不存在仍折叠为同一 404 形态。</p>
 * <p><b>切片端点</b>：薄层协议适配——附图先经共享校验器 {@link QueryImageValidator} 校验解码
 * （任一非法抛 {@link InvalidQueryImageException}，由全局异常处理映射真实 HTTP 400），
 * 包装类型开关缺省归一为 true 后组装多模态参数值对象，与其余参数一并构造
 * {@link RetrievalQuery} 契约下传。</p>
 *
 * @author DeepDataAgent
 */
@RestController
@RequestMapping(path = "/rag/retrievals", version = ApiVersionConstants.CURRENT_API_VERSION)
public class RetrievalController {

    /** 检索编排应用服务（建流前闸门与流式答案入口） */
    private final RetrievalApplicationService retrievalApplicationService;

    /** 检索答案流参数（SSE 超时、合帧窗口、保活间隔） */
    private final RetrievalStreamProperties streamProperties;

    /** 检索答案流专用虚拟线程执行器 */
    private final Executor streamExecutor;

    /**
     * 构造检索能力对外 REST 控制器。
     *
     * @param retrievalApplicationService 检索编排应用服务
     * @param streamProperties            检索答案流参数
     * @param streamExecutor              检索答案流执行器
     */
    public RetrievalController(RetrievalApplicationService retrievalApplicationService,
                               RetrievalStreamProperties streamProperties,
                               @Qualifier("ragRetrievalStreamExecutor") Executor streamExecutor) {
        this.retrievalApplicationService = retrievalApplicationService;
        this.streamProperties = streamProperties;
        this.streamExecutor = streamExecutor;
    }

    /**
     * 检索并以 SSE 流式返回 LLM 最终答案。
     * <p>事件序列见 {@code AnswerStreamEventCodec}：{@code answer.started} →
     * {@code answer.delta}×N → {@code answer.completed}（正常）或 {@code answer.error}（异常）。
     * 建流前的任何校验失败都不会创建流，而是返回既有 JSON 错误信封。</p>
     *
     * @param request 检索请求体（kbId/query 必填，预算与开关可选，images 可选内联附图）
     * @return SSE 连接（HTTP 200）；各阶段结果经事件帧下发
     * @throws InvalidQueryImageException 任一附图非法（数量/字节/类型白名单/base64/魔数），整请求拒绝
     */
    @PostMapping(value = "/answer", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter retrieveAnswer(@Valid @RequestBody RetrievalRequest request) {
        RetrievalQuery query = toQuery(request);
        // 建流前同步闸门：归属 + ACTIVE + 策略快照；失败保持真实 HTTP 状态码（防枚举语义不变）
        RetrievalStrategyConfig cfg = retrievalApplicationService.assertRetrievable(query.kbId());
        SseEmitter emitter = new SseEmitter(streamProperties.getSseTimeout().toMillis());
        AnswerStream stream = retrievalApplicationService.generateAnswerStream(query, cfg);
        streamExecutor.execute(() -> new AnswerStreamSession(emitter, stream, streamProperties, query.kbId())
                .start());
        return emitter;
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
