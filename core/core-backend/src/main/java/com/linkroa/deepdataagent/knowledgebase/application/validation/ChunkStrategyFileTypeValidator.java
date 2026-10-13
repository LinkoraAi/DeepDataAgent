package com.linkroa.deepdataagent.knowledgebase.application.validation;

import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.DocumentChunkMode;
import com.linkroa.deepdataagent.knowledgebase.domain.model.enums.FileType;
import com.linkroa.deepdataagent.shared.exception.DeepDataAgentException;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 分块策略 × 文档类型 联合校验器（上传登记前的拒绝式校验）。
 * <p>职责：在校验链同时掌握「文档类型」与「生效分块策略」时，判定该策略是否支持该文档类型；
 * 不匹配即抛 400（{@code invalid_request_error}），且校验 MUST 先于落对象存储与登记事务——
 * 非法即零写入、零残留。与只认识策略的 {@link ChunkStrategyConfigValidator} 职责分离。</p>
 * <p><b>生效策略口径</b>：文档级（请求显式提供）优先，否则取库级；两处均未配置时按默认 GENERAL
 * 处理（GENERAL 允许全部白名单类型）。策略 JSON 形态 {@code {"chunkMode":"GENERAL",...}}，
 * 缺失 / 空白模式名按 GENERAL 处理；兼容「策略 JSON 被再包一层字符串」的存量形态。</p>
 * <p><b>允许矩阵</b>（策略 → 允许的文档类型）：</p>
 * <ul>
 *   <li>GENERAL：全部白名单类型；</li>
 *   <li>BOOK / LAWS：PDF / DOC / DOCX / TXT / MD；</li>
 *   <li>QA：DOC / DOCX / XLS / XLSX / TXT / MD / CSV；</li>
 *   <li>TABLE：XLS / XLSX / TXT / CSV；</li>
 *   <li>PRESENTATION：PDF / PPT / PPTX；</li>
 *   <li>ONE：PDF / DOC / DOCX / XLS / XLSX / PPT / PPTX / TXT / MD。</li>
 * </ul>
 */
public final class ChunkStrategyFileTypeValidator {

    private static final Logger log = LoggerFactory.getLogger(ChunkStrategyFileTypeValidator.class);

    /** 分块策略 JSON 字段名：分块模式 */
    private static final String FIELD_CHUNK_MODE = "chunkMode";

    /** 分块策略 JSON 解析器（无状态，进程内共享） */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** 生效分块模式 → 允许的文档类型集合（矩阵权威定义）。 */
    private static final Map<DocumentChunkMode, Set<FileType>> ALLOWED_TYPES_BY_MODE = Map.of(
            DocumentChunkMode.GENERAL, EnumSet.allOf(FileType.class),
            DocumentChunkMode.BOOK, EnumSet.of(FileType.PDF, FileType.DOC, FileType.DOCX,
                    FileType.TXT, FileType.MD),
            DocumentChunkMode.LAWS, EnumSet.of(FileType.PDF, FileType.DOC, FileType.DOCX,
                    FileType.TXT, FileType.MD),
            DocumentChunkMode.QA, EnumSet.of(FileType.DOC, FileType.DOCX, FileType.XLS, FileType.XLSX,
                    FileType.TXT, FileType.MD, FileType.CSV),
            DocumentChunkMode.TABLE, EnumSet.of(FileType.XLS, FileType.XLSX, FileType.TXT, FileType.CSV),
            DocumentChunkMode.PRESENTATION, EnumSet.of(FileType.PDF, FileType.PPT, FileType.PPTX),
            DocumentChunkMode.ONE, EnumSet.of(FileType.PDF, FileType.DOC, FileType.DOCX, FileType.XLS,
                    FileType.XLSX, FileType.PPT, FileType.PPTX, FileType.TXT, FileType.MD)
    );

    private ChunkStrategyFileTypeValidator() {
    }

    /**
     * 校验「生效分块策略」是否支持给定文档类型。
     *
     * @param fileType          已校验解析的文档类型，可为 null（无类型可校验，直接通过）
     * @param chunkStrategyJson 生效分块策略 JSON 原文（文档级优先，否则库级），可为空白（按 GENERAL 处理）
     * @throws DeepDataAgentException 策略不支持该文档类型，或策略 JSON 非法（400）
     */
    public static void validate(FileType fileType, String chunkStrategyJson) {
        if (ObjectUtils.isEmpty(fileType)) {
            return;
        }
        DocumentChunkMode mode = resolveMode(chunkStrategyJson);
        Set<FileType> allowed = ALLOWED_TYPES_BY_MODE.get(mode);
        if (allowed.contains(fileType)) {
            return;
        }
        String allowedNames = allowed.stream().map(Enum::name).collect(Collectors.joining("/"));
        throw new DeepDataAgentException("分块策略「" + mode.name() + "」不支持文档类型「" + fileType.name()
                + "」，支持的类型为：" + allowedNames);
    }

    /**
     * 解析生效分块模式：空白策略按 GENERAL 处理；模式名缺失 / 空白按 GENERAL；
     * 兼容字符串再包一层形态；非法 JSON 抛 400。
     *
     * @param chunkStrategyJson 分块策略 JSON 原文，可为空白
     * @return 生效分块模式（非空）
     * @throws DeepDataAgentException 策略 JSON 非法（400）
     */
    private static DocumentChunkMode resolveMode(String chunkStrategyJson) {
        if (StringUtils.isBlank(chunkStrategyJson)) {
            return DocumentChunkMode.GENERAL;
        }
        JsonNode root;
        try {
            root = OBJECT_MAPPER.readTree(chunkStrategyJson);
        } catch (JacksonException e) {
            log.error("分块策略配置不是合法JSON，原始值={}", chunkStrategyJson, e);
            throw new DeepDataAgentException("分块策略配置（chunkStrategy）不是合法的JSON");
        }
        JsonNode strategy = unwrap(root);
        if (ObjectUtils.isEmpty(strategy) || !strategy.isObject()) {
            return DocumentChunkMode.GENERAL;
        }
        String raw = strategy.path(FIELD_CHUNK_MODE).isTextual()
                ? strategy.path(FIELD_CHUNK_MODE).stringValue() : null;
        if (StringUtils.isBlank(raw)) {
            return DocumentChunkMode.GENERAL;
        }
        try {
            return DocumentChunkMode.valueOf(StringUtils.upperCase(StringUtils.trim(raw), Locale.ROOT));
        } catch (IllegalArgumentException e) {
            // 模式名非法由 ChunkStrategyConfigValidator 前置拒绝；此处为防御性兜底，按 GENERAL 处理
            return DocumentChunkMode.GENERAL;
        }
    }

    /**
     * 归一分块策略节点：字符串形态（策略 JSON 被再包一层引号）解包为内嵌 JSON。
     *
     * @param node 原始节点
     * @return 解包后的节点；无法解包时返回原节点
     */
    private static JsonNode unwrap(JsonNode node) {
        if (ObjectUtils.isEmpty(node) || !node.isString()) {
            return node;
        }
        String raw = node.stringValue();
        if (StringUtils.isBlank(raw)) {
            return node;
        }
        try {
            return OBJECT_MAPPER.readTree(raw);
        } catch (JacksonException e) {
            return node;
        }
    }
}