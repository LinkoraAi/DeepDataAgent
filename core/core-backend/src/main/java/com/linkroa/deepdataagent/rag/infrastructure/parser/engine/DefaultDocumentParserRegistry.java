package com.linkroa.deepdataagent.rag.infrastructure.parser.engine;

import com.linkroa.deepdataagent.knowledgebase.application.port.KbAssetStoragePort;
import com.linkroa.deepdataagent.knowledgebase.domain.model.DocumentParseConfig;
import com.linkroa.deepdataagent.knowledgebase.domain.model.EngineConfig;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.FileType;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.ParseEngineProvider;
import com.linkroa.deepdataagent.rag.domain.port.DocumentParser;
import com.linkroa.deepdataagent.rag.domain.port.DocumentParserRegistry;
import com.linkroa.deepdataagent.rag.infrastructure.client.MineruClient;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * {@link DocumentParserRegistry} 默认实现：LOCAL 返回进程内单例解析器，
 * MINERU 按引擎参数每次新建 {@link MineruClient} 并包装 {@link MineruDocumentParser}。
 * <p>provider 为空按 LOCAL 兜底；MINERU 引擎参数键（engineConfig.params）与
 * {@link MineruClient} 构造参数同名：apiBaseUrl（必填，缺失快速失败）、backend、
 * modelServerUrl、parseMethod、language（字符串列表或逗号分隔串）、quality，
 * 缺省时取 {@link MineruClient} 的既有默认值常量。无失败自动回退。</p>
 * <p><b>类型路由（按引擎分治）</b>：文档类型不在所选引擎声明集合内时——
 * <b>LOCAL</b> 回落本地直读兜底解析器（{@link LocalReadFallbackDocumentParser}，
 * 承载图片视觉直通与纯文本直读）；<b>MINERU</b> 直接抛 {@link IllegalArgumentException}
 * 并列出支持类型，<b>不提供任何兜底</b>（严格模式，避免以兜底产物伪装成解析成功）。
 * 上传入口另经 {@code ParserCapabilityApi} 以同一口径提前拦截。</p>
 *
 * @author DeepDataAgent
 */
@Component
public class DefaultDocumentParserRegistry implements DocumentParserRegistry {

    /** params 键：MinerU 服务地址（MINERU 必填）。 */
    private static final String PARAM_API_BASE_URL = "apiBaseUrl";

    /** params 键：解析后端。 */
    private static final String PARAM_BACKEND = "backend";

    /** params 键：模型服务地址（仅 *-http-client 后端需要）。 */
    private static final String PARAM_MODEL_SERVER_URL = "modelServerUrl";

    /** params 键：解析方法。 */
    private static final String PARAM_PARSE_METHOD = "parseMethod";

    /** params 键：语言列表（字符串列表或逗号分隔串）。 */
    private static final String PARAM_LANGUAGE = "language";

    /** params 键：解析质量档位。 */
    private static final String PARAM_QUALITY = "quality";

    /** language 逗号分隔串的切分符。 */
    private static final String LANGUAGE_SEPARATOR = ",";

    /** 解析配置缺失提示。 */
    private static final String ERROR_CONFIG_NULL = "解析配置为空，无法选择文档解析器";

    /** 未知解析提供方提示。 */
    private static final String ERROR_UNKNOWN_PROVIDER = "未知的解析引擎提供方：";

    /** 类型不受引擎支持提示：文件类型 + 支持清单两段占位。 */
    private static final String ERROR_UNSUPPORTED_FILE_TYPE =
            "当前解析引擎不支持该文件类型（%s）；支持的类型为：%s";

    /** 未知文件类型在提示文案中的占位词。 */
    private static final String UNKNOWN_FILE_TYPE_LABEL = "未知类型";

    /** 类型清单在提示文案中的连接符。 */
    private static final String TYPE_JOINER = "、";

    /** 知识库对象资产存储访问端口（构建各解析器的读源依赖，跨 BC 消费）。 */
    private final KbAssetStoragePort kbAssetStoragePort;

    /** LOCAL 解析器进程内单例（无引擎参数，构造一次复用）。 */
    private final LocalTikaDocumentParser localTikaDocumentParser;

    /** 本地直读兜底解析器进程内单例（仅 LOCAL 引擎在类型不受支持时使用）。 */
    private final LocalReadFallbackDocumentParser localReadFallbackDocumentParser;

    /**
     * 构造器：注入对象资产存储访问端口并预建 LOCAL 与本地直读兜底单例解析器。
     *
     * @param kbAssetStoragePort 对象资产存储访问端口（解析器读取源文件的依赖）
     */
    public DefaultDocumentParserRegistry(KbAssetStoragePort kbAssetStoragePort) {
        this.kbAssetStoragePort = kbAssetStoragePort;
        this.localTikaDocumentParser = new LocalTikaDocumentParser(kbAssetStoragePort);
        this.localReadFallbackDocumentParser = new LocalReadFallbackDocumentParser(kbAssetStoragePort);
    }

    @Override
    public DocumentParser resolve(DocumentParseConfig parseConfig) {
        if (ObjectUtils.isEmpty(parseConfig)) {
            throw new IllegalArgumentException(ERROR_CONFIG_NULL);
        }
        ParseEngineProvider provider = ObjectUtils.defaultIfNull(parseConfig.provider(), ParseEngineProvider.LOCAL);
        return switch (provider) {
            case LOCAL -> localTikaDocumentParser;
            case MINERU -> buildMineruParser(parseConfig.engineConfig());
            default -> throw new IllegalArgumentException(ERROR_UNKNOWN_PROVIDER + provider);
        };
    }

    /**
     * 按解析配置 + 文档类型路由：先按 provider 选引擎，再校验引擎是否声明支持该文档类型。
     * <p>支持 → 返回引擎解析器。不支持时按引擎分治：<b>LOCAL</b> 返回本地直读兜底解析器
     * （图片视觉直通 / 纯文本直读）；<b>MINERU</b> 抛 {@link IllegalArgumentException}
     * 并列出该引擎支持的类型，不回落任何兜底。</p>
     *
     * @param parseConfig 知识库解析配置
     * @param fileType    文档类型，可为 null（未知类型）
     * @return 引擎解析器（引擎声明支持该类型），或 LOCAL 引擎下的本地直读兜底解析器
     * @throws IllegalArgumentException 解析配置为 null、provider 未知、MINERU 必填参数缺失，
     *                                  或 MINERU 遇不受支持的文档类型
     */
    @Override
    public DocumentParser resolve(DocumentParseConfig parseConfig, FileType fileType) {
        DocumentParser engine = resolve(parseConfig);
        if (ObjectUtils.isNotEmpty(fileType) && engine.supportedTypes().contains(fileType)) {
            return engine;
        }
        ParseEngineProvider provider = ObjectUtils.defaultIfNull(parseConfig.provider(), ParseEngineProvider.LOCAL);
        if (ParseEngineProvider.MINERU == provider) {
            throw new IllegalArgumentException(String.format(ERROR_UNSUPPORTED_FILE_TYPE,
                    ObjectUtils.isEmpty(fileType) ? UNKNOWN_FILE_TYPE_LABEL : fileType.name(),
                    joinSupportedTypes(engine.supportedTypes())));
        }
        return localReadFallbackDocumentParser;
    }

    /**
     * 支持类型清单按名称升序拼接为提示文案（顺序稳定，便于用户与日志对照）。
     *
     * @param supportedTypes 引擎声明的支持类型集合
     * @return 以顿号连接的名称串
     */
    private static String joinSupportedTypes(Set<FileType> supportedTypes) {
        return supportedTypes.stream().map(Enum::name).sorted(Comparator.naturalOrder())
                .collect(Collectors.joining(TYPE_JOINER));
    }

    /**
     * 按引擎参数新建 MINERU 解析器：每次调用构造新的 {@link MineruClient}
     * （不同知识库的服务地址与解析参数可能不同，不做缓存）。
     *
     * @param engineConfig 引擎配置（可空：视为全默认参数）
     * @return MinerU 文档解析器
     * @throws IllegalArgumentException apiBaseUrl 缺失（由 {@link MineruClient} 构造器校验）
     */
    private DocumentParser buildMineruParser(EngineConfig engineConfig) {
        Map<String, Object> params = resolveParams(engineConfig);
        MineruClient mineruClient = new MineruClient(
                stringParam(params, PARAM_API_BASE_URL),
                StringUtils.defaultIfBlank(stringParam(params, PARAM_BACKEND), MineruClient.DEFAULT_BACKEND),
                stringParam(params, PARAM_MODEL_SERVER_URL),
                StringUtils.defaultIfBlank(stringParam(params, PARAM_PARSE_METHOD), MineruClient.DEFAULT_PARSE_METHOD),
                languageParam(params),
                StringUtils.defaultIfBlank(stringParam(params, PARAM_QUALITY), MineruClient.DEFAULT_QUALITY),
                null);
        return new MineruDocumentParser(mineruClient, kbAssetStoragePort);
    }

    /**
     * 取引擎参数映射：配置为空或参数为 null 时视为空参数（全部走默认值）。
     */
    private static Map<String, Object> resolveParams(EngineConfig engineConfig) {
        if (ObjectUtils.isEmpty(engineConfig) || ObjectUtils.isEmpty(engineConfig.params())) {
            return Map.of();
        }
        return engineConfig.params();
    }

    /**
     * 读取字符串型参数：仅接受字符串值（首尾空白剥离），缺失 / 空白 / 非字符串均视为未提供。
     */
    private static String stringParam(Map<String, Object> params, String key) {
        Object value = params.get(key);
        if (value instanceof String text) {
            return StringUtils.trimToNull(text);
        }
        return null;
    }

    /**
     * 读取语言列表参数：兼容字符串列表与逗号分隔串两种写法，
     * 缺失或过滤后为空时回退 {@link MineruClient#DEFAULT_LANGUAGE}。
     */
    private static List<String> languageParam(Map<String, Object> params) {
        Object value = params.get(PARAM_LANGUAGE);
        if (value instanceof List<?> items && CollectionUtils.isNotEmpty(items)) {
            List<String> languages = items.stream()
                    .filter(item -> item instanceof String)
                    .map(item -> StringUtils.trim((String) item))
                    .filter(StringUtils::isNotBlank)
                    .toList();
            if (CollectionUtils.isNotEmpty(languages)) {
                return languages;
            }
        }
        if (value instanceof String text && StringUtils.isNotBlank(text)) {
            return Arrays.stream(StringUtils.split(text, LANGUAGE_SEPARATOR))
                    .map(StringUtils::trim)
                    .filter(StringUtils::isNotBlank)
                    .toList();
        }
        return MineruClient.DEFAULT_LANGUAGE;
    }
}
