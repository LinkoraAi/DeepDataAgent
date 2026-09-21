package com.linkroa.deepdataagent.rag.domain.service;

import com.linkroa.deepdataagent.rag.domain.model.ContentBlockVO;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;

import java.util.List;
import java.util.Map;

/**
 * 多模态内容块 meta 键约定与读取工具（分流注入约定）。
 * <p>键名分三类：</p>
 * <ol>
 *   <li>摄入分流注入键（下划线前缀，参考「多模态内容分流」约定）：
 *       {@link #META_KEY_SECTION_PATH}（章节路径 h1→h2→h3）、
 *       {@link #META_KEY_NEIGHBOR_TEXT}（前后邻近文本）；</li>
 *   <li>MinerU content_list 原始字段透传键（snake_case，与
 *       {@code MineruDocumentParser} 保留原键的行为对齐）：如
 *       {@link #META_KEY_IMG_PATH}、{@link #META_KEY_IMAGE_CAPTION} 等；</li>
 *   <li>媒体图片对象存储引用键（驼峰，Stage 1a 图片持久化后由摄入 Worker 补写，
 *       随 {@code chunk.original_item} 落库）：{@link #META_KEY_MEDIA_OBJECT_KEY}
 *       （仅对象键，桶概念已退役）。</li>
 * </ol>
 * <p>Stage1 描述生成与 Stage2 chunk 模板装配统一经 {@link #metaString} 读取，
 * 支持多别名回退（如 {@code img_path} 与 {@code image_path}），避免两阶段键名漂移。</p>
 *
 * @author DeepDataAgent
 */
public final class MultimodalMetaKeys {

    /** 注入键：章节路径（h1→h2→h3） */
    public static final String META_KEY_SECTION_PATH = "_section_path";
    /** 注入键：邻近文本（前后文拼接） */
    public static final String META_KEY_NEIGHBOR_TEXT = "_neighbor_text";

    /** MinerU 原始键：图片相对路径 */
    public static final String META_KEY_IMG_PATH = "img_path";
    /** 别名键：图片相对路径（模板占位符同名） */
    public static final String META_KEY_IMAGE_PATH = "image_path";
    /** MinerU 原始键：图片题注（List 或 String） */
    public static final String META_KEY_IMAGE_CAPTION = "image_caption";
    /** 别名键：图片题注复数（模板占位符同名） */
    public static final String META_KEY_IMAGE_CAPTIONS = "captions";
    /** MinerU 原始键：图片脚注 */
    public static final String META_KEY_IMAGE_FOOTNOTE = "image_footnote";
    /** MinerU 原始键：表格截图路径 */
    public static final String META_KEY_TABLE_IMG_PATH = "img_path";
    /** MinerU 原始键：表格题注 */
    public static final String META_KEY_TABLE_CAPTION = "table_caption";
    /** MinerU 原始键：表格结构体（HTML/latex） */
    public static final String META_KEY_TABLE_BODY = "table_body";
    /** MinerU 原始键：表格脚注 */
    public static final String META_KEY_TABLE_FOOTNOTE = "table_footnote";
    /** MinerU 原始键：块文本（公式 latex 等） */
    public static final String META_KEY_TEXT = "text";
    /** 扩展键：公式 latex 原文 */
    public static final String META_KEY_LATEX = "latex";
    /** 扩展键：公式格式 */
    public static final String META_KEY_FORMAT = "format";
    /** 扩展键：分流阶段预计算的主实体名（优先于派生规则） */
    public static final String META_KEY_ENTITY_NAME = "entity_name";
    /** 扩展键：泛型块内容类型标签 */
    public static final String META_KEY_CONTENT_TYPE = "content_type";
    /** MinerU 原始键：页码 */
    public static final String META_KEY_PAGE = "page";
    /** 别名键：页码（部分版本输出 page_idx） */
    public static final String META_KEY_PAGE_IDX = "page_idx";
    /** 引用键：媒体图片对象键（可解析的对象存储引用，随 original_item 落库；桶概念已退役） */
    public static final String META_KEY_MEDIA_OBJECT_KEY = "mediaObjectKey";

    /** 集合值 join 分隔符 */
    private static final String COLLECTION_JOIN_SEPARATOR = " ";

    /**
     * 工具类禁止实例化。
     */
    private MultimodalMetaKeys() {
    }

    /**
     * 按别名顺序读取内容块 meta 中的字符串值。
     * <p>List/数组等集合值以空格拼接为单行文本；其余类型经 {@link String#valueOf(Object)} 转换；
     * 首个非空白命中即返回。</p>
     *
     * @param block 内容块（可为 null）
     * @param keys  候选键名（按优先级顺序）
     * @return 命中的字符串值；无命中返回 {@code null}
     */
    public static String metaString(ContentBlockVO block, String... keys) {
        if (ObjectUtils.isEmpty(block) || ObjectUtils.isEmpty(block.meta())) {
            return null;
        }
        Map<String, Object> meta = block.meta();
        for (String key : keys) {
            String value = stringify(meta.get(key));
            if (StringUtils.isNotBlank(value)) {
                return value;
            }
        }
        return null;
    }

    /**
     * meta 原始值转字符串：集合逐元素转换后空格拼接，标量走 valueOf。
     *
     * @param raw meta 原始值（可为 null）
     * @return 字符串值；null 返回 null
     */
    private static String stringify(Object raw) {
        if (ObjectUtils.isEmpty(raw)) {
            return null;
        }
        if (raw instanceof List<?> list) {
            if (CollectionUtils.isEmpty(list)) {
                return null;
            }
            return StringUtils.join(list, COLLECTION_JOIN_SEPARATOR);
        }
        return String.valueOf(raw);
    }
}
