package com.linkroa.deepdataagent.rag.domain.service;

import com.linkroa.deepdataagent.rag.domain.model.ContentBlockVO;
import com.linkroa.deepdataagent.rag.domain.model.ParsedDocument;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 多模态块检索上下文注入器。
 * <p>在 Stage 1a 媒体引用绑定之后、Stage 1b 分块之前，为解析产物中的多模态块补写两枚注入键，
 * 供 Stage1 描述生成与 Stage2 模板装配消费（键名见 {@link MultimodalMetaKeys}）：</p>
 * <ul>
 *   <li>{@link MultimodalMetaKeys#META_KEY_SECTION_PATH} —— 章节路径：顺序扫描全部内容块，维护一个
 *       标题栈（标题块 = {@code TEXT} 且 meta 携带解析器注入的标题层级键 {@code text_level}），
 *       遇到多模态块时按栈内标题文本由浅到深拼接为 {@code 营收 > 季度趋势} 形态
 *       （层级间以 {@code > } 连接；层级仅用于栈维护，不进入路径文本）；栈空（无标题层级）时不写该键；</li>
 *   <li>{@link MultimodalMetaKeys#META_KEY_NEIGHBOR_TEXT} —— 邻近文本：以该块在内容块序列中的下标为中心，
 *       向前后各取 {@value #NEIGHBOR_WINDOW_SIZE} 个下标位置（不含该块自身），窗口内仅取 {@code TEXT} 块；
 *       每个文本块先做空白归一（去首尾空白，并把内容内部的换行与连续制表符压缩为单个空格），
 *       再以单个空格连接；窗口内无文本块时不写该键。</li>
 * </ul>
 * <p>降级与容错（注入永不抛）：</p>
 * <ul>
 *   <li>本地解析器产物无标题层级信息 → 两键自然缺省，模板既有 {@code N/A} 占位机制兜底，分块照常执行；</li>
 *   <li>单块注入过程抛任何异常 → WARN 留痕后原样返回该块，不阻断整体注入与管线。</li>
 * </ul>
 * <p>内容块与其 meta 可能为不可变结构，故对需要写入注入键的块重建 {@link ContentBlockVO} 副本
 * （沿用 {@code IngestionWorker.bindMediaRefs} 的块复制模式）；无块命中时原样返回入参解析产物，零副作用。</p>
 *
 * @author DeepDataAgent
 */
@Component
public class MultimodalContextInjector {

    private static final Logger log = LoggerFactory.getLogger(MultimodalContextInjector.class);

    /**
     * MinerU content_list 标题层级键（snake_case，随原始字段透传进块 meta）。
     * <p>语义：{@code text_level} 为正整数时该 text 项为标题，其值即层级（1=h1、2=h2…）；
     * 缺失 / 非正 / 无法解析按正文文本处理（不入标题栈）。键名与语义以解析联调实测为准，
     * 若实测键名或层级口径不同，仅需调整本常量与 {@link #parseHeadingLevel(ContentBlockVO)} 映射。</p>
     */
    private static final String META_KEY_TEXT_LEVEL = "text_level";

    /** 章节路径层级连接符（前后各一空格） */
    private static final String SECTION_PATH_SEPARATOR = " > ";
    /** 邻近文本位置窗口半径：以多模态块下标为中心，向前后各取该数量个下标位置 */
    private static final int NEIGHBOR_WINDOW_SIZE = 3;
    /** 邻近文本各文本块之间的连接符（单个空格） */
    private static final String NEIGHBOR_TEXT_SEPARATOR = " ";
    /** 文本块内容的空白断裂（换行与制表符及其两侧空白），归一为单个空格 */
    private static final Pattern NEIGHBOR_WHITESPACE_BREAK = Pattern.compile("\\s*[\\r\\n\\t]+\\s*");

    /**
     * 为解析产物中的多模态块注入检索上下文元数据。
     *
     * @param parsed 已完成媒体引用绑定的解析产物，可为 null（原样透传）
     * @return 注入后的解析产物；无解析产物或无多模态块命中时原样返回入参
     */
    public ParsedDocument inject(ParsedDocument parsed) {
        if (ObjectUtils.isEmpty(parsed) || CollectionUtils.isEmpty(parsed.blocks())) {
            return parsed;
        }
        List<ContentBlockVO> source = parsed.blocks();
        int size = source.size();
        List<ContentBlockVO> result = new ArrayList<>(size);
        // 标题栈：自浅到深的 (层级, 标题) 序列，层级即 text_level 值
        List<Heading> headingStack = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            ContentBlockVO block = source.get(i);
            if (isTextBlock(block)) {
                updateHeadingStack(block, headingStack);
                result.add(block);
                continue;
            }
            if (ObjectUtils.isEmpty(block) || !block.isMultimodal()) {
                result.add(block);
                continue;
            }
            result.add(injectBlock(block, headingStack, collectWindowTexts(source, i)));
        }
        return new ParsedDocument(parsed.parsedTextHash(), result, parsed.fileName(), parsed.images());
    }

    /**
     * 为单个多模态块重建副本并写入注入键；任何异常仅 WARN 后原样返回该块（注入永不抛）。
     *
     * @param block        多模态块
     * @param headingStack 当前标题栈（自浅到深）
     * @param windowTexts  位置窗口内各文本块归一后的内容（按序列序，可空）
     * @return 注入后的新块；无需注入或注入异常时原样返回入参块
     */
    private ContentBlockVO injectBlock(ContentBlockVO block, List<Heading> headingStack,
                                       List<String> windowTexts) {
        try {
            Map<String, Object> meta = new HashMap<>(ObjectUtils.isEmpty(block.meta()) ? Map.of() : block.meta());
            String sectionPath = buildSectionPath(headingStack);
            if (StringUtils.isNotBlank(sectionPath)) {
                meta.put(MultimodalMetaKeys.META_KEY_SECTION_PATH, sectionPath);
            }
            String neighborText = buildNeighborText(windowTexts);
            if (StringUtils.isNotBlank(neighborText)) {
                meta.put(MultimodalMetaKeys.META_KEY_NEIGHBOR_TEXT, neighborText);
            }
            return new ContentBlockVO(block.type(), block.text(), meta);
        } catch (RuntimeException e) {
            log.warn("多模态块上下文注入失败，原样保留该块：type=[{}] msg=[{}]", block.type(), e.getMessage());
            return block;
        }
    }

    /**
     * 收集位置窗口内各文本块的归一内容：以 {@code index} 为中心，向前后各取
     * {@link #NEIGHBOR_WINDOW_SIZE} 个下标位置（含两端、不含 {@code index} 自身），
     * 仅取 {@code TEXT} 块，逐块做空白归一后按序列序返回。
     *
     * @param blocks 内容块序列
     * @param index  多模态块下标
     * @return 归一后的文本块内容列表；窗口内无文本块时为空列表
     */
    private List<String> collectWindowTexts(List<ContentBlockVO> blocks, int index) {
        int from = Math.max(0, index - NEIGHBOR_WINDOW_SIZE);
        int to = Math.min(blocks.size() - 1, index + NEIGHBOR_WINDOW_SIZE);
        List<String> texts = new ArrayList<>();
        for (int i = from; i <= to; i++) {
            if (i == index) {
                continue;
            }
            ContentBlockVO candidate = blocks.get(i);
            if (!isTextBlock(candidate)) {
                continue;
            }
            String normalized = normalizeWhitespace(candidate.text());
            if (StringUtils.isNotBlank(normalized)) {
                texts.add(normalized);
            }
        }
        return texts;
    }

    /**
     * 文本块内容空白归一：去除首尾空白，并把内容内部的换行与连续制表符压缩为单个空格。
     *
     * @param text 文本块原文，可为 null
     * @return 归一后的单行文本；空白输入返回空串
     */
    private static String normalizeWhitespace(String text) {
        if (StringUtils.isBlank(text)) {
            return StringUtils.EMPTY;
        }
        return NEIGHBOR_WHITESPACE_BREAK.matcher(text).replaceAll(NEIGHBOR_TEXT_SEPARATOR).trim();
    }

    /**
     * 是否为可参与标题栈与邻近文本计算的 {@code TEXT} 块。
     *
     * @param block 内容块，可为 null
     * @return true 表示为文本块
     */
    private boolean isTextBlock(ContentBlockVO block) {
        return ObjectUtils.isNotEmpty(block) && ContentBlockVO.TYPE_TEXT.equals(block.type());
    }

    /**
     * 用文本块更新标题栈：命中标题层级时按「同层替换、更深压栈、更浅弹栈」维护。
     *
     * @param block        文本块（{@code TEXT}）
     * @param headingStack 标题栈（自浅到深）
     */
    private void updateHeadingStack(ContentBlockVO block, List<Heading> headingStack) {
        int level = parseHeadingLevel(block);
        if (level <= 0) {
            return;
        }
        String title = StringUtils.trimToNull(block.text());
        if (ObjectUtils.isEmpty(title)) {
            return;
        }
        while (!headingStack.isEmpty() && headingStack.get(headingStack.size() - 1).level() >= level) {
            headingStack.remove(headingStack.size() - 1);
        }
        headingStack.add(new Heading(level, title));
    }

    /**
     * 解析文本块的标题层级：读取 {@code text_level} 键并转为正整数，缺失 / 非法 / 非正返回 {@code 0}（正文）。
     * <p>兼容 JSON 反序列化后的 Number 与字符串两种形态（映射函数集中于此，联调实测仅调整此处的键名/口径）。</p>
     *
     * @param block 文本块
     * @return 标题层级（&gt;0）；非标题返回 0
     */
    private int parseHeadingLevel(ContentBlockVO block) {
        if (ObjectUtils.isEmpty(block) || MapUtils.isEmpty(block.meta())) {
            return 0;
        }
        Object raw = block.meta().get(META_KEY_TEXT_LEVEL);
        if (ObjectUtils.isEmpty(raw)) {
            return 0;
        }
        try {
            int level = raw instanceof Number number ? number.intValue()
                    : Integer.parseInt(StringUtils.trim(String.valueOf(raw)));
            return Math.max(level, 0);
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /**
     * 按标题栈拼接章节路径（各层标题文本间以 {@code > } 连接；层级仅用于栈维护、不进入路径文本）。
     *
     * @param headingStack 标题栈（自浅到深）
     * @return 章节路径；栈空返回 {@code null}
     */
    private String buildSectionPath(List<Heading> headingStack) {
        if (CollectionUtils.isEmpty(headingStack)) {
            return null;
        }
        StringBuilder path = new StringBuilder();
        for (Heading heading : headingStack) {
            if (path.length() > 0) {
                path.append(SECTION_PATH_SEPARATOR);
            }
            path.append(heading.title());
        }
        return path.toString();
    }

    /**
     * 组装邻近文本：位置窗口内各文本块归一内容以单个空格连接；无有效内容返回 {@code null}。
     *
     * @param windowTexts 位置窗口内各文本块归一后的内容（按序列序，可空）
     * @return 邻近文本（单空格连接的单行文本）；无有效内容返回 {@code null}
     */
    private String buildNeighborText(List<String> windowTexts) {
        if (CollectionUtils.isEmpty(windowTexts)) {
            return null;
        }
        return String.join(NEIGHBOR_TEXT_SEPARATOR, windowTexts);
    }

    /**
     * 标题栈元素（层级 + 标题文本）。
     *
     * @param level 标题层级（{@code text_level} 值，&gt;0）
     * @param title 标题文本（非空白）
     */
    private record Heading(int level, String title) {
    }
}
