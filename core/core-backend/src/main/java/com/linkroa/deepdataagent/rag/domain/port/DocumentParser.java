package com.linkroa.deepdataagent.rag.domain.port;

import com.linkroa.deepdataagent.knowledgebase.domain.model.MultiModelConfig;
import com.linkroa.deepdataagent.knowledgebase.domain.model.S3File;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.FileType;
import com.linkroa.deepdataagent.rag.domain.model.ParsedDocument;

import java.util.Set;

/**
 * 文档解析端口：把 S3 源文件解析为结构化内容块列表（ContentBlockVO 五类块）。
 * <p>对应摄入链路的文档解析层，由两个实现按知识库解析配置（provider）选择：
 * LOCAL → 本地 Tika 解析（{@code LocalTikaDocumentParser}），MINERU → 远程 MinerU 解析
 * （{@code MineruDocumentParser}）。注意：<b>无自动回退</b>——配置决定解析方式，
 * 解析失败时抛异常交由调用方将文档置为 FAILED，不得自动切换另一种解析引擎。</p>
 * <p><b>类型路由</b>：解析选择层先按 {@link #supportedTypes()} 判定文档类型是否属本引擎支持集合，
 * 支持才调用本引擎解析；不支持该类型时不调用引擎，改走本地直读兜底（见
 * {@code DefaultDocumentParserRegistry} 的类型路由）。类型路由与「解析失败回退」不同——前者是
 * 解析前的选型，后者仍然禁止。</p>
 */
public interface DocumentParser {

    /**
     * 解析 S3 源文件为结构化内容块列表。
     *
     * @param s3File       源文件对象引用
     * @param chunkStrategy 分块策略标识（当前解析层不消费，为端口签名一致性保留）
     * @param multiModel   多模态模型配置（当前解析层不消费，为端口签名一致性保留）
     * @return 解析结果（内容哈希 + 结构化块列表；解析成功但无有效文本时产出占位 TEXT 块，恒非空）
     * @throws IllegalArgumentException 真实解析失败（文件对象缺失 / 引擎协议错误等，交由调用方置 FAILED）
     */
    ParsedDocument parse(S3File s3File, String chunkStrategy, MultiModelConfig multiModel);

    /**
     * 本解析引擎原生支持的文档类型集合。
     * <p>用于解析选择层的<b>类型路由</b>：文档类型落在本集合内才调用本引擎解析，否则跳过引擎
     * 改走本地直读兜底，MUST NOT 因「引擎不支持该类型」把文档置为 FAILED。</p>
     *
     * @return 本引擎支持的文档类型集合（不可变、非 null）
     */
    Set<FileType> supportedTypes();
}