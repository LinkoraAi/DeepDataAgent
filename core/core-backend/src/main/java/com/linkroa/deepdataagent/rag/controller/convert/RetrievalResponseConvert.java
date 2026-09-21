package com.linkroa.deepdataagent.rag.controller.convert;

import com.linkroa.deepdataagent.rag.application.contract.RetrievalChunkView;
import com.linkroa.deepdataagent.rag.controller.response.ChunkReferenceResponse;
import com.linkroa.deepdataagent.rag.controller.response.RetrievalAnswerResponse;
import com.linkroa.deepdataagent.rag.controller.response.RetrievalChunksResponse;
import com.linkroa.deepdataagent.shared.constant.api.ApiVersionConstants;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;
import org.mapstruct.factory.Mappers;

import java.util.List;

/**
 * 检索编排产物 → REST 响应 DTO 转换器。
 * <p>目标类型为 record（按位置传参），统一以 default 方法显式完成字段装配，
 * 与仓库 convert 包惯例（{@code KnowledgeBaseResponseConvert}）一致。
 * 两个端点各投一个形态、互不携带对方分量：答案形态只包 {@code answer}，
 * 切片形态只包 {@code chunks}（逐条含正文、来源文件与多模态资源路径）。
 * 引用列表、降级标记以及 {@code contextData} / {@code rawData} 两个内部分量一律不外露。</p>
 *
 * @author DeepDataAgent
 */
@Mapper(unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface RetrievalResponseConvert {

    /** 转换器单例（仓库 convert 包惯例，Mappers 工厂获取） */
    RetrievalResponseConvert INSTANCE = Mappers.getMapper(RetrievalResponseConvert.class);

    /**
     * 多模态资源可访问路径模板：知识库切片媒体在线预览端点（inline 流式代理），
     * 浏览器直接打开即可看图，鉴权与同源口径与其他 API 一致；
     * 仅暴露切片 ID，不外泄对象键、对象存储端点与桶凭据（桶概念已退役）。
     */
    String MODAL_FILE_URL_TEMPLATE = "/api/v" + ApiVersionConstants.CURRENT_API_VERSION
            + "/knowledge-base/chunks/%s/media";

    /**
     * 答案文本 → 答案形态响应 DTO。
     * <p>不做任何兜底改写：答案生成降级时 {@code answer} 即为 {@code null}，
     * 由调用方按「可能无答案」处理。</p>
     *
     * @param answer 编排层产出的最终答案文本，可为 {@code null}
     * @return 答案形态响应 DTO，永不为 {@code null}
     */
    default RetrievalAnswerResponse toAnswerResponse(String answer) {
        return new RetrievalAnswerResponse(answer);
    }

    /**
     * 切片明细 → 切片形态响应 DTO（只含 chunks 一个分量）。
     *
     * @param chunkViews 编排层产出的切片明细列表，可为 {@code null}
     * @return 切片形态响应 DTO，永不为 {@code null}
     */
    default RetrievalChunksResponse toChunksResponse(List<RetrievalChunkView> chunkViews) {
        return new RetrievalChunksResponse(toChunks(chunkViews));
    }

    /**
     * 切片明细 → 响应 chunk 列表（按相关性序）。
     * <p>明细缺失（无命中切片、关键词失败终止或正文回取失败）时归一为空列表，
     * 响应契约承诺 chunks 恒非 null。</p>
     *
     * @param chunkViews 编排层产出的切片明细列表，可为 {@code null}
     * @return chunk 明细响应列表，永不为 {@code null}
     */
    default List<ChunkReferenceResponse> toChunks(List<RetrievalChunkView> chunkViews) {
        if (CollectionUtils.isEmpty(chunkViews)) {
            return List.of();
        }
        return chunkViews.stream()
                .filter(ObjectUtils::isNotEmpty)
                .map(this::toChunk)
                .toList();
    }

    /**
     * 单条明细 → 响应 DTO（多模态引用两分量齐备才产出可访问路径）。
     *
     * @param view 切片明细（非空）
     * @return chunk 明细响应
     */
    default ChunkReferenceResponse toChunk(RetrievalChunkView view) {
        return new ChunkReferenceResponse(view.chunkId(), view.chunkContent(), view.sourceFile(),
                toModalFile(view), view.score());
    }

    /**
     * 多模态图片引用 → 可访问资源路径（前端点击直接查看）。
     * <p>以切片 ID 构造预览端点 URL（对象键不外泄，由服务端按切片登记的引用定位）；
     * 无引用返回 {@code null}，前端据此判定非图片切片。</p>
     *
     * @param view 切片明细
     * @return 预览端点相对 URL；无多模态资源时为 {@code null}
     */
    default String toModalFile(RetrievalChunkView view) {
        if (ObjectUtils.isEmpty(view.chunkId()) || StringUtils.isBlank(view.mediaObjectKey())) {
            return null;
        }
        return String.format(MODAL_FILE_URL_TEMPLATE, view.chunkId());
    }
}
