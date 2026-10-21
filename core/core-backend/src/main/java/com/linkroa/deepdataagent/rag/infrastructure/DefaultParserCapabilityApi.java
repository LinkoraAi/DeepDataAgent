package com.linkroa.deepdataagent.rag.infrastructure;

import com.linkroa.deepdataagent.knowledgebase.api.ParserCapabilityApi;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.FileType;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.ParseEngineProvider;
import com.linkroa.deepdataagent.rag.infrastructure.parser.engine.LocalReadFallbackDocumentParser;
import com.linkroa.deepdataagent.rag.infrastructure.parser.engine.LocalTikaDocumentParser;
import com.linkroa.deepdataagent.rag.infrastructure.parser.engine.MineruDocumentParser;
import com.linkroa.deepdataagent.shared.exception.DeepDataAgentException;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Comparator;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * {@link ParserCapabilityApi} 契约的默认实现（防腐层 / 契约适配器）。
 * <p>能力集合的唯一真相源是解析器的 {@code declaredSupportedTypes()}；本类只负责
 * 从知识库 {@code rag_engine_config} 中解析出解析提供方，并按提供方取集合，不复制任何类型清单。
 * LOCAL 另并入本地直读兜底的可读集合（图片视觉直通由此承载），MINERU 不启用兜底。</p>
 * <p><b>解析容错</b>：配置空白、非法 JSON、{@code parseEngine} 段缺失或 provider 取值非法时，
 * 一律按默认引擎 {@link ParseEngineProvider#LOCAL} 处置——与
 * {@code DefaultDocumentParserRegistry} 的「provider 为空按 LOCAL 兜底」口径一致；
 * 配置损坏由知识库写入口与摄入读取侧各自报错，本契约不做二次判定。</p>
 *
 * @author DeepDataAgent
 */
@Component
public class DefaultParserCapabilityApi implements ParserCapabilityApi {

    /** 日志器 */
    private static final Logger log = LoggerFactory.getLogger(DefaultParserCapabilityApi.class);

    /** rag_engine_config JSON 字段名：解析引擎配置段 */
    private static final String FIELD_PARSE_ENGINE = "parseEngine";

    /** parseEngine 段字段名：解析提供方 */
    private static final String FIELD_PROVIDER = "provider";

    /** 类型清单在提示文案中的连接符 */
    private static final String TYPE_JOINER = "、";

    /** 内置解析引擎展示名 */
    private static final String LABEL_LOCAL = "内置解析";

    /** MinerU 解析引擎展示名 */
    private static final String LABEL_MINERU = "MinerU";

    /** 上传准入拒绝提示：引擎、文件类型、支持清单三段占位 */
    private static final String ERROR_UNSUPPORTED_FILE_TYPE =
            "当前解析引擎（%s）不支持 %s 文件；该引擎支持的类型为：%s";

    /** JSON 解析器（无状态，进程内共享；与知识库摄入读取侧同栈） */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Override
    public Set<FileType> supportedTypes(String ragEngineConfig) {
        return typesOf(resolveProvider(ragEngineConfig));
    }

    @Override
    public void ensureSupported(String ragEngineConfig, FileType fileType) {
        ParseEngineProvider provider = resolveProvider(ragEngineConfig);
        Set<FileType> supported = typesOf(provider);
        if (supported.contains(fileType)) {
            return;
        }
        throw new DeepDataAgentException(String.format(ERROR_UNSUPPORTED_FILE_TYPE,
                labelOf(provider), fileType.name(), joinSorted(supported)));
    }

    /**
     * 按解析提供方取声明的支持类型集合（能力唯一真相源）。
     * <p><b>MINERU</b> 只取引擎自身集合（严格模式，不支持即拒绝）；
     * <b>LOCAL</b> 取「内置解析集合 ∪ 本地直读兜底可读集合」——兜底承载图片视觉直通与纯文本直读，
     * 故 LOCAL 的准入范围覆盖全部白名单类型。</p>
     *
     * @param provider 解析提供方
     * @return 该引擎可处理的类型集合（不可变）
     */
    private static Set<FileType> typesOf(ParseEngineProvider provider) {
        if (provider == ParseEngineProvider.MINERU) {
            return MineruDocumentParser.declaredSupportedTypes();
        }
        Set<FileType> localTypes = new HashSet<>(LocalTikaDocumentParser.declaredSupportedTypes());
        localTypes.addAll(LocalReadFallbackDocumentParser.declaredSupportedTypes());
        return Set.copyOf(localTypes);
    }

    /**
     * 由知识库 RAG 引擎配置解析解析提供方；任何异常形态按 LOCAL 处置。
     *
     * @param ragEngineConfig 知识库 {@code rag_engine_config} JSON 原文，可为空
     * @return 解析提供方（永不为空）
     */
    private static ParseEngineProvider resolveProvider(String ragEngineConfig) {
        if (StringUtils.isBlank(ragEngineConfig)) {
            return ParseEngineProvider.LOCAL;
        }
        try {
            JsonNode providerNode = OBJECT_MAPPER.readTree(ragEngineConfig)
                    .path(FIELD_PARSE_ENGINE).path(FIELD_PROVIDER);
            if (!providerNode.isTextual()) {
                return ParseEngineProvider.LOCAL;
            }
            String normalized = providerNode.stringValue().trim().toUpperCase(Locale.ROOT);
            for (ParseEngineProvider candidate : ParseEngineProvider.values()) {
                if (candidate.name().equals(normalized)) {
                    return candidate;
                }
            }
            log.warn("解析引擎提供方取值非法，按 LOCAL 处置: provider={}", providerNode.stringValue());
            return ParseEngineProvider.LOCAL;
        } catch (JacksonException e) {
            log.warn("RAG 引擎配置不是合法 JSON，解析能力按 LOCAL 处置: {}", ragEngineConfig, e);
            return ParseEngineProvider.LOCAL;
        }
    }

    /**
     * 解析提供方的用户可读名称。
     *
     * @param provider 解析提供方
     * @return 展示名
     */
    private static String labelOf(ParseEngineProvider provider) {
        return provider == ParseEngineProvider.MINERU ? LABEL_MINERU : LABEL_LOCAL;
    }

    /**
     * 类型清单按名称升序拼接为提示文案（顺序稳定，便于用户与日志对照）。
     *
     * @param types 类型集合
     * @return 以顿号连接的名称串
     */
    private static String joinSorted(Set<FileType> types) {
        return types.stream().map(Enum::name).sorted(Comparator.naturalOrder())
                .collect(Collectors.joining(TYPE_JOINER));
    }
}