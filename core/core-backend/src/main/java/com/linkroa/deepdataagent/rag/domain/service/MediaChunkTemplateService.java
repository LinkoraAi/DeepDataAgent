package com.linkroa.deepdataagent.rag.domain.service;

import com.linkroa.deepdataagent.rag.domain.model.ChunkVO;
import com.linkroa.deepdataagent.rag.domain.model.ContentBlockVO;
import com.linkroa.deepdataagent.rag.domain.model.MediaDescriptionVO;
import com.linkroa.deepdataagent.rag.infrastructure.prompts.catalog.PromptCatalog;
import com.linkroa.deepdataagent.rag.infrastructure.prompts.catalog.PromptTemplates;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 媒体 chunk 模板装配服务（多模态 7-Stage 管线 Stage2，模板清单 #14~#17）。
 * <p>将 Stage1 的 {@link MediaDescriptionVO} 与内容块 meta 注入四类 chunk 模板
 * （image_chunk / table_chunk / equation_chunk / generic_chunk），产出结构化 chunk 正文：
 * enhanced caption（详描优先、摘要退化）+ 章节路径（{@code _section_path}）+
 * 邻近文本（{@code _neighbor_text}）等检索上下文一并向量化。</p>
 * <p>仅做纯构造（含 {@link ChunkVO} 装配的便捷出口），不落库；meta 缺失字段以
 * {@code N/A} 占位，渲染永不误抛缺失占位符异常。键名读取统一复用
 * {@link MultimodalMetaKeys}，与 Stage1 及分流注入约定对齐。</p>
 *
 * @author DeepDataAgent
 */
@Component
public class MediaChunkTemplateService {

    /** chunk 模板：图片（集中定义见 PromptTemplates） */
    static final String TEMPLATE_IMAGE_CHUNK = PromptTemplates.IMAGE_CHUNK;
    /** chunk 模板：表格 */
    static final String TEMPLATE_TABLE_CHUNK = PromptTemplates.TABLE_CHUNK;
    /** chunk 模板：公式 */
    static final String TEMPLATE_EQUATION_CHUNK = PromptTemplates.EQUATION_CHUNK;
    /** chunk 模板：泛型 */
    static final String TEMPLATE_GENERIC_CHUNK = PromptTemplates.GENERIC_CHUNK;

    /** 占位符变量名：章节路径 */
    private static final String VAR_SECTION_PATH = "section_path";
    /** 占位符变量名：邻近文本 */
    private static final String VAR_NEIGHBOR_TEXT = "neighbor_text";
    /** 占位符变量名：图片路径 */
    private static final String VAR_IMAGE_PATH = "image_path";
    /** 占位符变量名：图片题注 */
    private static final String VAR_CAPTIONS = "captions";
    /** 占位符变量名：图片脚注 */
    private static final String VAR_FOOTNOTES = "footnotes";
    /** 占位符变量名：增强描述 */
    private static final String VAR_ENHANCED_CAPTION = "enhanced_caption";
    /** 占位符变量名：表格截图路径 */
    private static final String VAR_TABLE_IMG_PATH = "table_img_path";
    /** 占位符变量名：表格题注 */
    private static final String VAR_TABLE_CAPTION = "table_caption";
    /** 占位符变量名：表格结构体 */
    private static final String VAR_TABLE_BODY = "table_body";
    /** 占位符变量名：表格脚注 */
    private static final String VAR_TABLE_FOOTNOTE = "table_footnote";
    /** 占位符变量名：公式原文 */
    private static final String VAR_EQUATION_TEXT = "equation_text";
    /** 占位符变量名：公式格式 */
    private static final String VAR_EQUATION_FORMAT = "equation_format";
    /** 占位符变量名：内容类型 */
    private static final String VAR_CONTENT_TYPE = "content_type";
    /** 占位符变量名：块内容 */
    private static final String VAR_CONTENT = "content";

    /** 变量缺失时的模板占位值 */
    private static final String MISSING_VALUE_PLACEHOLDER = "N/A";
    /** 公式默认格式 */
    private static final String DEFAULT_EQUATION_FORMAT = "latex";

    /**
     * 构造模板装配服务（模板渲染经 {@link PromptCatalog} 静态门面，无注入依赖）。
     */
    public MediaChunkTemplateService() {
    }

    /**
     * 装配媒体块的模板化 chunk 正文（Stage2 出口，向量化唯一输入）。
     *
     * @param block       多模态内容块（IMAGE / TABLE / EQUATION / GENERIC）
     * @param description Stage1 媒体描述（非空）
     * @param language    模板语言码（zh 中文、其余英文）
     * @return 渲染完成的 chunk 正文
     * @throws IllegalArgumentException 入参非法或块类型非多模态（TEXT / 未知）
     */
    public String buildChunkContent(ContentBlockVO block, MediaDescriptionVO description, String language) {
        if (ObjectUtils.isEmpty(block)) {
            throw new IllegalArgumentException("内容块不能为空");
        }
        if (ObjectUtils.isEmpty(description)) {
            throw new IllegalArgumentException("媒体描述不能为空");
        }
        if (StringUtils.isBlank(language)) {
            throw new IllegalArgumentException("language 不能为空");
        }
        String type = block.type();
        if (ContentBlockVO.TYPE_TEXT.equals(type)) {
            throw new IllegalArgumentException("文本块无需媒体模板装配");
        }
        Map<String, String> vars = buildTemplateVars(type, block, description);
        return PromptCatalog.render(selectTemplate(type), language, vars);
    }

    /**
     * 装配模板化 chunk 并包装为 {@link ChunkVO}（token 走真实 encode 口径）。
     *
     * @param sequence     块序号（从 1 开始）
     * @param block        多模态内容块
     * @param description  Stage1 媒体描述
     * @param language     模板语言码
     * @param tokenCounter Token 计数器（真实 encode）
     * @return 分块中间态值对象（仅内存，不落库）
     * @throws IllegalArgumentException 入参非法（透传构造器与模板装配校验）
     */
    public ChunkVO buildChunk(int sequence, ContentBlockVO block, MediaDescriptionVO description,
                              String language, TokenCounter tokenCounter) {
        if (ObjectUtils.isEmpty(tokenCounter)) {
            throw new IllegalArgumentException("tokenCounter 不能为空");
        }
        String content = buildChunkContent(block, description, language);
        return new ChunkVO(sequence, content, tokenCounter.count(content), block);
    }

    /**
     * 按块类型选择 chunk 模板名。
     *
     * @param type 块类型
     * @return 模板名
     * @throws IllegalArgumentException 未知块类型
     */
    private String selectTemplate(String type) {
        return switch (type) {
            case ContentBlockVO.TYPE_IMAGE -> TEMPLATE_IMAGE_CHUNK;
            case ContentBlockVO.TYPE_TABLE -> TEMPLATE_TABLE_CHUNK;
            case ContentBlockVO.TYPE_EQUATION -> TEMPLATE_EQUATION_CHUNK;
            case ContentBlockVO.TYPE_GENERIC -> TEMPLATE_GENERIC_CHUNK;
            default -> throw new IllegalArgumentException("未知的多模态块类型: " + type);
        };
    }

    /**
     * 组装 chunk 模板变量（全部归一为非空白值）。
     *
     * @param type        块类型
     * @param block       内容块
     * @param description 媒体描述
     * @return 模板变量表
     */
    private Map<String, String> buildTemplateVars(String type, ContentBlockVO block,
                                                  MediaDescriptionVO description) {
        Map<String, String> vars = new HashMap<>();
        putVar(vars, VAR_ENHANCED_CAPTION, description.enhancedCaption());
        // 注入键对全部模板统一装配（image_chunk 实际引用；其余模板多余变量按渲染端口契约忽略）
        putVar(vars, VAR_SECTION_PATH,
                MultimodalMetaKeys.metaString(block, MultimodalMetaKeys.META_KEY_SECTION_PATH));
        putVar(vars, VAR_NEIGHBOR_TEXT,
                MultimodalMetaKeys.metaString(block, MultimodalMetaKeys.META_KEY_NEIGHBOR_TEXT));
        switch (type) {
            case ContentBlockVO.TYPE_IMAGE -> buildImageVars(vars, block);
            case ContentBlockVO.TYPE_TABLE -> buildTableVars(vars, block);
            case ContentBlockVO.TYPE_EQUATION -> buildEquationVars(vars, block);
            default -> buildGenericVars(vars, block, type);
        }
        return vars;
    }

    /**
     * 图片块变量：image_path / captions / footnotes。
     *
     * @param vars  变量表
     * @param block 内容块
     */
    private void buildImageVars(Map<String, String> vars, ContentBlockVO block) {
        putVar(vars, VAR_IMAGE_PATH, MultimodalMetaKeys.metaString(block,
                MultimodalMetaKeys.META_KEY_IMG_PATH, MultimodalMetaKeys.META_KEY_IMAGE_PATH));
        putVar(vars, VAR_CAPTIONS, MultimodalMetaKeys.metaString(block,
                MultimodalMetaKeys.META_KEY_IMAGE_CAPTION, MultimodalMetaKeys.META_KEY_IMAGE_CAPTIONS));
        putVar(vars, VAR_FOOTNOTES, MultimodalMetaKeys.metaString(block,
                MultimodalMetaKeys.META_KEY_IMAGE_FOOTNOTE));
    }

    /**
     * 表格块变量：table_img_path / table_caption / table_body / table_footnote。
     *
     * @param vars  变量表
     * @param block 内容块
     */
    private void buildTableVars(Map<String, String> vars, ContentBlockVO block) {
        putVar(vars, VAR_TABLE_IMG_PATH, MultimodalMetaKeys.metaString(block,
                MultimodalMetaKeys.META_KEY_IMG_PATH));
        putVar(vars, VAR_TABLE_CAPTION, MultimodalMetaKeys.metaString(block,
                MultimodalMetaKeys.META_KEY_TABLE_CAPTION));
        putVar(vars, VAR_TABLE_BODY, StringUtils.defaultIfBlank(
                MultimodalMetaKeys.metaString(block, MultimodalMetaKeys.META_KEY_TABLE_BODY), block.text()));
        putVar(vars, VAR_TABLE_FOOTNOTE, MultimodalMetaKeys.metaString(block,
                MultimodalMetaKeys.META_KEY_TABLE_FOOTNOTE));
    }

    /**
     * 公式块变量：equation_text / equation_format。
     *
     * @param vars  变量表
     * @param block 内容块
     */
    private void buildEquationVars(Map<String, String> vars, ContentBlockVO block) {
        putVar(vars, VAR_EQUATION_TEXT, StringUtils.defaultIfBlank(
                MultimodalMetaKeys.metaString(block, MultimodalMetaKeys.META_KEY_TEXT,
                        MultimodalMetaKeys.META_KEY_LATEX), block.text()));
        putVar(vars, VAR_EQUATION_FORMAT, StringUtils.defaultIfBlank(
                MultimodalMetaKeys.metaString(block, MultimodalMetaKeys.META_KEY_FORMAT),
                DEFAULT_EQUATION_FORMAT));
    }

    /**
     * 泛型块变量：content_type / content。
     *
     * @param vars  变量表
     * @param block 内容块
     * @param type  块类型
     */
    private void buildGenericVars(Map<String, String> vars, ContentBlockVO block, String type) {
        putVar(vars, VAR_CONTENT_TYPE, StringUtils.defaultIfBlank(
                MultimodalMetaKeys.metaString(block, MultimodalMetaKeys.META_KEY_CONTENT_TYPE),
                type.toLowerCase(Locale.ROOT)));
        putVar(vars, VAR_CONTENT, StringUtils.defaultIfBlank(
                MultimodalMetaKeys.metaString(block, MultimodalMetaKeys.META_KEY_TEXT), block.text()));
    }

    /**
     * 写入归一化变量：空白值统一替换为缺失占位值，保证渲染端永不因空值误抛异常。
     *
     * @param vars  变量表
     * @param key   变量名
     * @param value 变量原始值（可空）
     */
    private void putVar(Map<String, String> vars, String key, String value) {
        vars.put(key, StringUtils.defaultIfBlank(value, MISSING_VALUE_PLACEHOLDER));
    }
}
