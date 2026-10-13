package com.linkroa.deepdataagent.rag.infrastructure.parser.engine;

import com.linkroa.deepdataagent.knowledgebase.application.port.KbAssetStoragePort;
import com.linkroa.deepdataagent.knowledgebase.domain.model.MultiModelConfig;
import com.linkroa.deepdataagent.knowledgebase.domain.model.S3File;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.FileType;
import com.linkroa.deepdataagent.rag.domain.model.ContentBlockVO;
import com.linkroa.deepdataagent.rag.domain.model.ParsedDocument;
import com.linkroa.deepdataagent.rag.domain.port.DocumentParser;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.tika.exception.TikaException;
import org.apache.tika.exception.ZeroByteFileException;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.io.InputStream;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * 本地 Tika 文档解析实现（provider=LOCAL 时使用）。
 * <p>基于 Apache Tika 4.0 的 {@link AutoDetectParser} 在本地进程内解析 S3 源文件，
 * 提取的纯文本按空行分段切分为多个 TEXT 内容块。本实现只产出纯文本块、不产出坐标标签
 * （坐标抽取能力由远程 MinerU 承担），meta 统一携带 page=0 占位。输出与 MinerU 均为
 * ContentBlockVO 五类统一结构，是后续分块层对解析引擎无感知的统一输入。
 * 图片类型不在本引擎支持集内：独立图片文件由本地直读兜底的视觉直通承载
 * （{@link LocalReadFallbackDocumentParser}），不经 Tika。</p>
 * <p><b>空文本处置</b>：解析成功但未提取到任何文本（空文件 / 近空文本）时不再抛异常，
 * 而是产出含文件名的占位 TEXT 块，保证文档终态可 PROCESSED 且切片数 ≥ 1；仅真实解析失败
 * （Tika 协议异常 / 读取 IO 异常）仍抛异常交由调用方置 FAILED。</p>
 */
public class LocalTikaDocumentParser implements DocumentParser {

    /** BodyContentHandler 无大小上限标记。 */
    private static final int NO_WRITE_LIMIT = -1;

    /** 解析失败提示。 */
    private static final String ERROR_PARSE_FAILED = "文档解析失败";

    /** 读取对象失败提示前缀。 */
    private static final String ERROR_READ_OBJECT_FAILED = "读取源文件对象失败: ";

    /** 本引擎支持解析的文档类型集合（LOCAL：文本 / 办公文档类，不含图片）。 */
    private static final Set<FileType> SUPPORTED_TYPES = EnumSet.of(
            FileType.PDF, FileType.DOC, FileType.DOCX, FileType.XLS, FileType.XLSX,
            FileType.PPT, FileType.PPTX, FileType.TXT, FileType.MD, FileType.CSV);

    /** 知识库对象资产存储访问端口（跨 BC 消费，读取源文件内容流）。 */
    private final KbAssetStoragePort kbAssetStoragePort;

    /**
     * 复用的 Tika 自动探测解析器：无参构造经 ServiceLoader 装配标准解析器集（重成本编译产物），
     * 本实例构造一次全程复用。
     * <p><b>线程安全依据</b>：Tika 的 {@code Parser} 实例本身无解析态（状态承载于每次调用的
     * {@code ContentHandler} / {@code Metadata} / {@code ParseContext}），且并发复用已经
     * openspec change {@code converge-rag-hot-path-object-creation} design D3 实证
     * （{@code LocalTikaDocumentParserTest} 两组并发用例：共享实例产出与串行逐条一致、零交叉污染）。</p>
     */
    private final AutoDetectParser autoDetectParser = new AutoDetectParser();

    /**
     * 构造器：注入对象资产存储访问端口。
     *
     * @param kbAssetStoragePort 对象资产存储访问端口（读取源文件内容流）
     */
    public LocalTikaDocumentParser(KbAssetStoragePort kbAssetStoragePort) {
        this.kbAssetStoragePort = kbAssetStoragePort;
    }

    @Override
    public Set<FileType> supportedTypes() {
        return SUPPORTED_TYPES;
    }

    /**
     * 解析源文件：经存储访问端口读取内容流 → Tika 提取纯文本 → 空行分段为 TEXT 块 → MD5 全文哈希。
     * <p>无有效文本时产出含文件名的占位 TEXT 块（不再抛异常）。</p>
     * <p>注意：chunkStrategy / multiModel 当前解析层不消费（为端口签名一致性保留）。</p>
     *
     * @param s3File        源文件对象引用
     * @param chunkStrategy 分块策略标识（本实现不消费）
     * @param multiModel    多模态模型配置（本实现不消费）
     * @return 解析结果（全文哈希 + TEXT 块列表；无有效文本时返回占位块产物）
     * @throws IllegalArgumentException Tika 解析协议失败（真实失败，交由调用方置 FAILED）/ 源文件对象不存在
     * @throws RuntimeException         读取对象 IO 失败
     */
    @Override
    public ParsedDocument parse(S3File s3File, String chunkStrategy, MultiModelConfig multiModel) {
        try (InputStream in = kbAssetStoragePort.open(s3File.objectKey())
                .orElseThrow(() -> new IllegalArgumentException("源文件对象不存在: " + s3File.objectKey()))
                .content();
             TikaInputStream tikaIn = TikaInputStream.get(in)) {
            // handler / Metadata / ParseContext 承载单次解析的状态与输出，必须每次新建（复用会串数据）；
            // 仅重量级编译产物 autoDetectParser 作为实例字段复用（线程安全依据见其字段注释与 design D3）。
            BodyContentHandler handler = new BodyContentHandler(NO_WRITE_LIMIT);
            autoDetectParser.parse(tikaIn, handler, new Metadata(), new ParseContext());
            String fullText = handler.toString();
            List<ContentBlockVO> blocks = LocalReadParseSupport.splitToTextBlocks(fullText);
            if (CollectionUtils.isEmpty(blocks)) {
                // 解析成功但无有效文本：产出占位块，保证切片数 ≥ 1
                return LocalReadParseSupport.placeholderDocument(
                        LocalReadParseSupport.baseName(s3File.objectKey()));
            }
            return new ParsedDocument(LocalReadParseSupport.computeMd5(fullText), blocks);
        } catch (IOException e) {
            throw new RuntimeException(ERROR_READ_OBJECT_FAILED + s3File.objectKey(), e);
        } catch (ZeroByteFileException e) {
            // 空文件（Tika 判定零字节）：按「无有效文本」处置，产出占位块，不视为真实解析失败
            return LocalReadParseSupport.placeholderDocument(LocalReadParseSupport.baseName(s3File.objectKey()));
        } catch (TikaException | SAXException e) {
            throw new IllegalArgumentException(ERROR_PARSE_FAILED, e);
        }
    }
}