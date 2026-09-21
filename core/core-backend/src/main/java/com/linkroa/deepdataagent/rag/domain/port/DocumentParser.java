package com.linkroa.deepdataagent.rag.domain.port;

import com.linkroa.deepdataagent.knowledgebase.domain.model.MultiModelConfig;
import com.linkroa.deepdataagent.knowledgebase.domain.model.S3File;
import com.linkroa.deepdataagent.rag.domain.model.ParsedDocument;

/**
 * 文档解析端口：把 S3 源文件解析为结构化内容块列表（ContentBlockVO 五类块）。
 * <p>对应摄入链路的文档解析层，由两个实现按知识库解析配置（provider）选择：
 * LOCAL → 本地 Tika 解析（{@code LocalTikaDocumentParser}），MINERU → 远程 MinerU 解析
 * （{@code MineruDocumentParser}）。注意：<b>无自动回退</b>——配置决定解析方式，
 * 解析失败时抛异常交由调用方将文档置为 FAILED，不得自动切换另一种解析引擎。</p>
 */
public interface DocumentParser {

    /**
     * 解析 S3 源文件为结构化内容块列表。
     *
     * @param s3File       源文件对象引用
     * @param chunkStrategy 分块策略标识（当前解析层不消费，为端口签名一致性保留）
     * @param multiModel   多模态模型配置（当前解析层不消费，为端口签名一致性保留）
     * @return 解析结果（内容哈希 + 结构化块列表；内容块为空视为解析失败抛异常）
     * @throws IllegalArgumentException 解析失败（文件为空 / 无任何可解析内容 / 远程结果缺失等）
     */
    ParsedDocument parse(S3File s3File, String chunkStrategy, MultiModelConfig multiModel);
}