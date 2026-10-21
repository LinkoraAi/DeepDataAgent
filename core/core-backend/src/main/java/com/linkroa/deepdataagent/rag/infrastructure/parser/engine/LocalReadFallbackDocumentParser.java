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
 * 本地直读兜底解析器（<b>仅 LOCAL 引擎启用</b>）：当所选解析引擎不支持某文档类型时
 * <b>不调用引擎</b>，直接读取源文件字节，产出与常规解析产物<b>同构</b>的结果。
 * <ul>
 *   <li>可解码文本（txt/md/csv 等）→ 按空行分段为真实 TEXT 块；</li>
 *   <li>图片文件（字节签名命中 PNG/JPG/JPEG）→ <b>视觉直通</b>：单个 IMAGE 块 +
 *       以文件基名为键的自身字节载荷，经既有 Stage1a 持久化 / Stage2 真看图描述接入通用视觉链路；
 *       LOCAL 引擎的图片能力即由本路径承载（Tika 不解析图片）。</li>
 *   <li>可解码但无有效文本（空文件 / 近空文本）→ 产出含文件名的<b>占位 TEXT 块</b>，
 *       保证文档终态可 PROCESSED 且切片数 ≥ 1；</li>
 *   <li>不可解码且非图片（典型如引擎不支持的二进制格式）→ 抛出可读异常，按解析失败如实上报，
 *       MUST NOT 以占位块伪装成解析成功。</li>
 * </ul>
 * <p><b>路由范围</b>：由 {@code DefaultDocumentParserRegistry} 在
 * 「LOCAL 引擎 + 类型不在内置解析集合内」时选中；<b>MINERU 引擎不启用本兜底</b>——
 * MinerU 遇不支持类型直接按失败上报（严格模式）。</p>
 * <p>本解析器不承载任何引擎调用，故对真实解析失败无兜底职责；图片直通只做字节级识别与产物封装，
 * 不做解码、OCR 或版面分析。</p>
 */
public class LocalReadFallbackDocumentParser implements DocumentParser {

    private static final Logger log = LoggerFactory.getLogger(LocalReadFallbackDocumentParser.class);

    /** 读取对象失败提示前缀。 */
    private static final String ERROR_READ_OBJECT_FAILED = "读取源文件对象失败: ";

    /** 引擎不支持该类型且源内容不可直读的失败提示（%s 为文件名）。 */
    private static final String ERROR_UNSUPPORTED_FILE_TYPE =
            "当前解析引擎不支持该文件类型，且文件内容无法作为文本或图片读取：%s；"
                    + "请改用受支持的格式，或在知识库配置中切换解析引擎";

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
     * 获取本兜底解析器声明的可读类型集合（不可变副本）。
     * <p>供解析引擎能力查询复用：LOCAL 引擎的可上传类型 = 内置解析集合 ∪ 本集合。</p>
     *
     * @return 可读的文件类型集合（不可变，覆盖全部白名单类型）
     */
    public static Set<FileType> declaredSupportedTypes() {
        return Set.copyOf(SUPPORTED_TYPES);
    }

    /**
     * 本地直读解析：读取源文件字节 → 图片签名嗅探（命中即产视觉直通产物）→ 尝试 UTF-8 严格解码 →
     * 可解码则按空行切真实 TEXT 块（无有效文本时产占位块）；不可解码且非图片则抛异常；不调用任何解析引擎。
     * <p>图片嗅探<b>先于</b>文本解码判定执行：文件自身即图片，直通是无损路径而非降级。</p>
     * <p>注意：chunkStrategy / multiModel 当前解析层不消费（为端口签名一致性保留）。</p>
     *
     * @param s3File        源文件对象引用
     * @param chunkStrategy 分块策略标识（本实现不消费）
     * @param multiModel    多模态模型配置（本实现不消费）
     * @return 解析结果（TEXT 块 / 图片直通 IMAGE 块+载荷 / 占位 TEXT 块；恒非空）
     * @throws IllegalArgumentException 源文件对象不存在，或引擎不支持该类型且内容不可直读
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
        if (text == null) {
            // 既非图片也不可解码：所选引擎不支持该类型，无法产出任何真实内容 → 如实上报失败
            log.warn("本地直读兜底：源内容不可解码且非图片，判定为引擎不支持该类型, objectKey={}",
                    s3File.objectKey());
            throw new IllegalArgumentException(String.format(ERROR_UNSUPPORTED_FILE_TYPE, fileNameHint));
        }
        List<ContentBlockVO> blocks = LocalReadParseSupport.splitToTextBlocks(text);
        if (CollectionUtils.isEmpty(blocks)) {
            // 可解码但无有效文本（空文件 / 近空文本）：与类型无关，按既有口径产占位块保证切片数 ≥ 1
            log.info("本地直读兜底：源内容可解码但无有效文本，产出占位块, objectKey={}", s3File.objectKey());
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