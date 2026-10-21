package com.linkroa.deepdataagent.knowledgebase.api;

import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.FileType;

import java.util.Set;

/**
 * 解析引擎能力查询契约（跨 BC 只读端口）。
 * <p>提供方为 knowledgebase BC（本契约由知识库定义并发布），实现方为 rag BC
 * （解析器能力声明在 rag.infrastructure.parser.engine 内）；依赖方向与
 * {@link IngestionTaskSubmitter} 同构（rag → knowledgebase 单向），不新增反向编译依赖。</p>
 *
 * <p><b>挂点</b>：文档上传登记前的格式准入校验——按知识库当前解析引擎的能力集合拒绝
 * 不受支持的文件类型，使「引擎不支持」在上传响应中如实给出，而不是等到异步解析阶段才失败。</p>
 */
public interface ParserCapabilityApi {

    /**
     * 按知识库 RAG 引擎配置解析当前解析引擎可处理的文件类型。
     * <p>口径与解析路由一致：<b>MINERU</b> 仅返回引擎自身声明集合（严格模式，不支持即上传拒绝）；
     * <b>LOCAL</b> 返回「内置解析集合 ∪ 本地直读兜底可读集合」（兜底承载图片视觉直通与纯文本直读，
     * 故覆盖全部白名单类型）。配置缺失、空白或无法解析时按默认引擎（LOCAL）能力返回。</p>
     *
     * @param ragEngineConfig 知识库 {@code rag_engine_config} JSON 原文，可为空
     * @return 当前引擎可处理的文件类型集合（不可变，非空）
     */
    Set<FileType> supportedTypes(String ragEngineConfig);

    /**
     * 校验指定文件类型是否被当前解析引擎支持，不支持即抛出可读的业务异常。
     *
     * @param ragEngineConfig 知识库 {@code rag_engine_config} JSON 原文，可为空
     * @param fileType        待校验的文件类型
     * @throws com.linkroa.deepdataagent.shared.exception.DeepDataAgentException 类型不受当前引擎支持（400）
     */
    void ensureSupported(String ragEngineConfig, FileType fileType);
}