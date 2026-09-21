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

/**
 * 多模态块检索上下文注入器。
 * <p>在 Stage 1a 媒体引用绑定之后、Stage 1b 分块之前，为解析产物中的多模态块补写两枚注入键，
 * 供 Stage1 描述生成与 Stage2 模板装配消费（键名见 {@link MultimodalMetaKeys}）：</p>
 * <ul>
 *   <li>{@link MultimodalMetaKeys#META_KEY_SECTION_PATH} —— 章节路径：顺序扫描全部内容块，维护一个
 *       标题栈（标题块 = {@code TEXT} 且 meta 携带解析器注入的标题层级键 {@code text_level}），
 *       遇到多模态块时按栈内层级由浅到深拼接为 {@code ## 营收 > ### 季度趋势} 形态
 *       （每级前缀 {@code #}×level、层级间以 {@code > } 连接）；栈空（无标题层级）时不写该键；</li>
 *   <li>{@link MultimodalMetaKeys#META_KEY_NEIGHBOR_TEXT} —— 邻近文本：取该块<b>前向</b>最近 {@code TEXT}
 *       块与<b>后向</b>最近 {@code TEXT} 块的可见文本，各截断至 200 字符后以省略号连接为「前…后」；
 *       前后皆无文本块时不写该键。</li>
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

    /** 章节路径层级前缀（Markdown 井号） */
    private static final String HEADING_PREFIX = "#";
    /** 章节路径层级连接符（前后各一空格） */
    private static final String SECTION_PATH_SEPARATOR = " > ";
    /** 邻近文本单侧截断长度上限（字符） */
    private static final int NEIGHBOR_TEXT_MAX_LENGTH = 200;
    /** 邻近文本前后段连接省略号 */
    private static final String NEIGHBOR_TEXT_CONNECTOR = "…";

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
        // 前向预计算：每个位置之前最近的 TEXT 块文本（含自身为 TEXT 时取自身）——用于邻近文本的「前」段
        String[] forwardText = new String[size];
        String lastText = null;
        for (int i = 0; i < size; i++) {
            if (isTextBlock(source.get(i))) {
                lastText = source.get(i).text();
            }
            forwardText[i] = lastText;
        }
        // 后向预计算：每个位置之后最近的 TEXT 块文本——用于邻近文本的「后」段
        String[] backwardText = new String[size];
        lastText = null;
        for (int i = size - 1; i >= 0; i--) {
            if (isTextBlock(source.get(i))) {
                lastText = source.get(i).text();
            }
            backwardText[i] = lastText;
        }

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
            result.add(injectBlock(block, headingStack, forwardText[i], backwardText[i]));
        }
        return new ParsedDocument(parsed.parsedTextHash(), result, parsed.fileName(), parsed.images());
    }

    /**
     * 为单个多模态块重建副本并写入注入键；任何异常仅 WARN 后原样返回该块（注入永不抛）。
     *
     * @param block        多模态块
     * @param headingStack 当前标题栈（自浅到深）
     * @param precedingText 前向最近文本块内容（可空）
     * @param followingText 后向最近文本块内容（可空）
     * @return 注入后的新块；无需注入或注入异常时原样返回入参块
     */
    private ContentBlockVO injectBlock(ContentBlockVO block, List<Heading> headingStack,
                                       String precedingText, String followingText) {
        try {
            Map<String, Object> meta = new HashMap<>(ObjectUtils.isEmpty(block.meta()) ? Map.of() : block.meta());
            String sectionPath = buildSectionPath(headingStack);
            if (StringUtils.isNotBlank(sectionPath)) {
                meta.put(MultimodalMetaKeys.META_KEY_SECTION_PATH, sectionPath);
            }
            String neighborText = buildNeighborText(precedingText, followingText);
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
     * 按标题栈拼接章节路径（每级 {@code #}×text_level 前缀 + 标题文本，层级间 {@code > } 连接）。
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
            path.append(HEADING_PREFIX.repeat(heading.level())).append(' ').append(heading.title());
        }
        return path.toString();
    }

    /**
     * 组装邻近文本：前向段与后向段各截断至上限后以省略号连接；两段皆空白返回 {@code null}。
     *
     * @param precedingText 前向最近文本块内容（可空）
     * @param followingText 后向最近文本块内容（可空）
     * @return 邻近文本（「前…后」）；无有效内容返回 {@code null}
     */
    private String buildNeighborText(String precedingText, String followingText) {
        String before = StringUtils.trimToNull(precedingText);
        String after = StringUtils.trimToNull(followingText);
        if (ObjectUtils.isEmpty(before) && ObjectUtils.isEmpty(after)) {
            return null;
        }
        StringBuilder neighbor = new StringBuilder();
        if (ObjectUtils.isNotEmpty(before)) {
            neighbor.append(StringUtils.abbreviate(before, NEIGHBOR_TEXT_MAX_LENGTH));
        }
        if (ObjectUtils.isNotEmpty(after)) {
            if (neighbor.length() > 0) {
                neighbor.append(NEIGHBOR_TEXT_CONNECTOR);
            }
            neighbor.append(StringUtils.abbreviate(after, NEIGHBOR_TEXT_MAX_LENGTH));
        }
        return neighbor.toString();
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
