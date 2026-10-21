package com.linkroa.deepdataagent.rag.domain.service;

import com.linkroa.deepdataagent.rag.infrastructure.prompts.catalog.EnPromptCatalog;
import com.linkroa.deepdataagent.rag.infrastructure.prompts.catalog.PromptTemplates;
import org.apache.commons.lang3.StringUtils;

import java.util.Map;

/**
 * 媒体 chunk 正文模板（双语内容模板，非 LLM 指令）。
 * <p>图片 / 表格 / 公式 / 泛型四类 chunk 的正文会作为切片内容持久化并参与向量化，
 * 其语言必须跟随知识库语言；因此这里保留中英两套<b>内容模板</b>。
 * 这与提示词指令目录不同——指令正文已收敛为单套英文，输出语言由 {@code {language}} 控制；
 * chunk 正文无法通过变量切换固定标签（如「图片内容分析：」），故按语言选择内容模板。</p>
 * <p>模板名与 {@link PromptTemplates} 的 IMAGE_CHUNK / TABLE_CHUNK / EQUATION_CHUNK /
 * GENERIC_CHUNK 一一对应；英文正文直接复用 {@link EnPromptCatalog} 的目录条目，
 * 中文正文由本类持有。</p>
 *
 * @author DeepDataAgent
 */
public final class MediaChunkTemplates {

    /** 中文知识库语言全名（大小写不敏感判定） */
    private static final String LANGUAGE_CHINESE = "Chinese";

    /** 图片 chunk 中文正文（不含媒体路径：引用由切片持有） */
    private static final String IMAGE_CHUNK_ZH = """
    图片内容分析：
    - 章节路径：{section_path}
    - 邻近文本：{neighbor_text}
    标注：{captions}
    脚注：{footnotes}

    视觉分析：{enhanced_caption}
    """;

    /** 表格 chunk 中文正文（不含媒体路径：引用由切片持有） */
    private static final String TABLE_CHUNK_ZH = """
    表格分析：
    标题：{table_caption}
    结构：{table_body}
    脚注：{table_footnote}

    分析：{enhanced_caption}
    """;

    /** 公式 chunk 中文正文 */
    private static final String EQUATION_CHUNK_ZH = """
    数学公式分析：
    公式：{equation_text}
    格式：{equation_format}

    数学分析：{enhanced_caption}
    """;

    /** 泛型 chunk 中文正文 */
    private static final String GENERIC_CHUNK_ZH = """
    {content_type}内容分析：
    内容：{content}

    分析：{enhanced_caption}
    """;

    /** 中文内容模板注册表（键与 {@link PromptTemplates} 常量一致） */
    private static final Map<String, String> ZH_TEMPLATES = Map.of(
            PromptTemplates.IMAGE_CHUNK, IMAGE_CHUNK_ZH,
            PromptTemplates.TABLE_CHUNK, TABLE_CHUNK_ZH,
            PromptTemplates.EQUATION_CHUNK, EQUATION_CHUNK_ZH,
            PromptTemplates.GENERIC_CHUNK, GENERIC_CHUNK_ZH);

    private MediaChunkTemplates() {
        // 工具类禁止实例化
    }

    /**
     * 按知识库语言选择 chunk 正文模板。
     *
     * @param templateName 模板名（{@link PromptTemplates} 的四个 chunk 模板之一）
     * @param language     知识库语言全名（{@code Chinese} 取中文正文，其余取英文正文）
     * @return 模板正文
     * @throws IllegalArgumentException 模板名未知
     */
    public static String resolve(String templateName, String language) {
        if (StringUtils.equalsIgnoreCase(StringUtils.trim(language), LANGUAGE_CHINESE)) {
            String zhBody = ZH_TEMPLATES.get(templateName);
            if (zhBody != null) {
                return zhBody;
            }
        }
        String enBody = EnPromptCatalog.TEMPLATES.get(templateName);
        if (enBody == null) {
            throw new IllegalArgumentException("未知的媒体 chunk 模板: " + templateName);
        }
        return enBody;
    }
}