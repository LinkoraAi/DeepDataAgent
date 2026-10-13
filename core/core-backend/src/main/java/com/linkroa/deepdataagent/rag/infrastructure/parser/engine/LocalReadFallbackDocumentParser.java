package com.linkroa.deepdataagent.rag.infrastructure.parser.engine;

import com.linkroa.deepdataagent.knowledgebase.application.port.KbAssetStoragePort;
import com.linkroa.deepdataagent.knowledgebase.domain.model.MultiModelConfig;
import com.linkroa.deepdataagent.knowledgebase.domain.model.S3File;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.FileType;
import com.linkroa.deepdataagent.rag.domain.model.ContentBlockVO;
import com.linkroa.deepdataagent.rag.domain.model.ParsedDocument;
import com.linkroa.deepdataagent.rag.domain.port.DocumentParser;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * 本地直读兜底解析器：当所选解析引擎不支持某文档类型时<b>不调用引擎</b>，直接读取源文件字节，
 * 产出与常规解析产物<b>同构</b>的结果（内容块 + 页码元数据，图片文件另携图片载荷）。
 * <ul>
 *   <li>可解码文本（txt/md/csv 等）→ 按空行分段为真实 TEXT 块；</li>
 *   <li>图片文件（字节签名命中 PNG/JPG/JPEG）→ <b>视觉直通</b>：单个 IMAGE 块 +
 *       以文件基名为键的自身字节载荷，经既有 Stage1a 持久化 / Stage2 真看图描述接入通用视觉链路；</li>
 *   <li>其余不可解码 / 无有效文本内容（如空文件、损坏二进制）→ 产出含文件名的<b>占位 TEXT 块</b>，
 *       保证文档终态可 PROCESSED 且切片数 ≥ 1。</li>
 * </ul>
 * <p><b>定位</b>：本解析器是类型路由的兜底出口，由 {@code DefaultDocumentParserRegistry} 在
 * 「文档类型不在所选引擎支持集合内」时显式选中，MUST NOT 因「引擎不支持该类型」把文档置 FAILED。
 * 本解析器不承载任何引擎调用，故对真实解析失败无兜底职责；图片直通只做字节级识别与产物封装，
 * 不做解码、OCR 或版面分析。</p>
 */
public class LocalReadFallbackDocumentParser implements DocumentParser {

    private static final Logger log = LoggerFactory.getLogger(LocalReadFallbackDocumentParser.class);

    /** 读取对象失败提示前缀。 */
    private static final String ERROR_READ_OBJECT_FAILED = "读取源文件对象失败: ";

    /**
     * 兜底可读类型集合：本地直读对全部白名单类型声明可读（读字节而非引擎解析）。
     * <p>路由按「引擎能力优先」，故支持类型仍走对应引擎，本集合仅在引擎不支持时生效。</p>
     */
    private static final Set<FileType> SUPPORTED_TYPES = EnumSet.allOf(FileType.class);

    /** 知识库对象资产存储访问端口（跨 BC 消费，读取源文件字节）。 */
    private final KbAssetStoragePort kbAssetStoragePort;

    /**
     * 构造器：注入对象资产存储访问端口。
     *
     * @param kbAssetStoragePort 对象资产存储访问端口（读取源文件字节）
     */
    public LocalReadFallbackDocumentParser(KbAssetStoragePort kbAssetStoragePort) {
        this.kbAssetStoragePort = kbAssetStoragePort;
    }

    @Override
    public Set<FileType> supportedTypes() {
        return SUPPORTED_TYPES;
    }

    /**
     * 本地直读解析：读取源文件字节 → 图片签名嗅探（命中即产视觉直通产物）→ 尝试 UTF-8 严格解码 →
     * 可解码且非空产出真实 TEXT 块，否则产出含文件名的占位 TEXT 块；不调用任何解析引擎。
     * <p>图片嗅探<b>先于</b>文本解码判定执行（design D1）：文件自身即图片，直通是无损路径而非降级；
     * 非图片字节的既有路径（TEXT / 占位块）分毫不动。</p>
     * <p>注意：chunkStrategy / multiModel 当前解析层不消费（为端口签名一致性保留）。</p>
     *
     * @param s3File        源文件对象引用
     * @param chunkStrategy 分块策略标识（本实现不消费）
     * @param multiModel    多模态模型配置（本实现不消费）
     * @return 解析结果（TEXT 块 / 图片直通 IMAGE 块+载荷 / 占位 TEXT 块；恒非空）
     * @throws IllegalArgumentException 源文件对象不存在
     * @throws RuntimeException         读取对象 IO 失败
     */
    @Override
    public ParsedDocument parse(S3File s3File, String chunkStrategy, MultiModelConfig multiModel) {
        byte[] content = readObjectBytes(s3File);
        String fileNameHint = LocalReadParseSupport.baseName(s3File.objectKey());
        if (LocalReadParseSupport.looksLikeImage(content)) {
            log.info("本地直读：源文件命中图片签名，产出视觉直通产物, objectKey={}, imgName={}",
                    s3File.objectKey(), fileNameHint);
            return LocalReadParseSupport.imagePassthrough(content, fileNameHint);
        }
        String text = LocalReadParseSupport.decodeUtf8OrNull(content);
        if (ObjectUtils.isEmpty(text)) {
            log.info("本地直读兜底：源内容不可解码或无有效文本，产出占位块, objectKey={}", s3File.objectKey());
            return LocalReadParseSupport.placeholderDocument(fileNameHint);
        }
        List<ContentBlockVO> blocks = LocalReadParseSupport.splitToTextBlocks(text);
        if (CollectionUtils.isEmpty(blocks)) {
            return LocalReadParseSupport.placeholderDocument(fileNameHint);
        }
        return new ParsedDocument(LocalReadParseSupport.computeMd5(text), blocks);
    }

    /**
     * 经存储访问端口读取源文件对象全部字节（try-with-resources 保证流关闭）。
     *
     * @param s3File 源文件对象引用
     * @return 文件内容字节
     */
    private byte[] readObjectBytes(S3File s3File) {
        try (InputStream in = kbAssetStoragePort.open(s3File.objectKey())
                .orElseThrow(() -> new IllegalArgumentException("源文件对象不存在: " + s3File.objectKey()))
                .content()) {
            if (ObjectUtils.isEmpty(in)) {
                throw new IllegalArgumentException("源文件对象不存在: " + s3File.objectKey());
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new RuntimeException(ERROR_READ_OBJECT_FAILED + s3File.objectKey(), e);
        }
    }
}