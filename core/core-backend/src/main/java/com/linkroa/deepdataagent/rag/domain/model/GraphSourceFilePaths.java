package com.linkroa.deepdataagent.rag.domain.model;

import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 图谱条目「来源文件路径列表」归一共用实现（graph-source-file-paths R1/R2/R3，design D8）。
 * <p>摄入合并路径（{@code GraphMergeService} 写回）与删除重建路径（组 4 重建服务）MUST 共用本处，
 * 保证两条路径产出的 {@code properties.filePaths} 恒为同一形态。统一口径：</p>
 * <ol>
 *     <li><b>去重保序</b>：剔除空白元素后按首次出现顺序去重（旧来源在前、新来源追加在后）；</li>
 *     <li><b>上限截断</b>：去重后条数超过上限时保留前 N 条（KEEP 策略，首现来源优先）。
 *         截断策略说明：与 source_ids 展示列的 KEEP 默认口径一致——文件路径仅作展示与溯源线索，
 *         保留首现来源可让条目长期展示最初的贡献文件，避免每次合并都整体翻新的列表形态；</li>
 *     <li><b>溢出占位元素</b>：发生截断时在列表末尾追加一个可辨识的占位字符串，格式为
 *         {@code 占位词(KEEP;保留<保留数>/共<总数>)}（例：{@code …等(KEEP;保留75/共120)}），
 *         使消费方可知晓「存在未展示的来源」及其数量；未超限 MUST NOT 追加。</li>
 * </ol>
 * <p><b>占位元素识别</b>：{@link #isOverflowPlaceholder(String)} 以上述格式的固定尾缀正则判定；
 * {@link #normalize} 在去重前先剔除既有占位元素再重算并追加新占位，保证截断口径幂等
 * （反复合并不累积占位元素）。总数（共 N）按「可辨识的真实路径条数」计——历史截断丢弃的路径
 * 已不可恢复，不并入本次总数。</p>
 * <p>来源文件路径 MUST NOT 参与向量内容构造与相关性计算（spec R1），本类仅服务展示列表本身。</p>
 *
 * @author DeepDataAgent
 */
public final class GraphSourceFilePaths {

    /** 来源文件路径保留上限默认值（与 LightRAG source_ids 类限额同族口径，经 {@code GraphMergeParams} 暴露为配置项） */
    public static final int DEFAULT_LIMIT = 75;

    /** 溢出占位元素默认占位词（占位元素 = 占位词 + 括号内策略与数量信息） */
    public static final String DEFAULT_PLACEHOLDER_WORD = "…等";

    /** 截断策略标识：保留前 N 条（首现来源优先），随占位元素一并呈现 */
    public static final String TRUNCATION_KEEP = "KEEP";

    /** 溢出占位元素格式：{@code 占位词(策略;保留<保留数>/共<总数>)} */
    private static final String PLACEHOLDER_ELEMENT_FORMAT = "%s(" + TRUNCATION_KEEP + ";保留%d/共%d)";

    /** 溢出占位元素识别正则：任意占位词 + 固定尾缀 {@code (KEEP;保留数字/共数字)} */
    private static final Pattern PLACEHOLDER_PATTERN =
            Pattern.compile(".*\\(" + TRUNCATION_KEEP + ";保留\\d+/共\\d+\\)$");

    /**
     * 工具类禁止实例化（开发规范【强制】）。
     */
    private GraphSourceFilePaths() {
        throw new AssertionError("图谱来源文件路径归一工具类不允许实例化");
    }

    /**
     * 单来源路径 → 列表形态：null 归一空列表，非 null（含空白）原样承载为单元素列表
     * （空白裁剪口径与落库层一致——模型层不裁剪、归一层统一剔除）。
     *
     * @param filePath 单条来源文件路径，可为 null
     * @return 不可变列表（null → 空列表；否则单元素列表）
     */
    public static List<String> singletonListOrEmpty(String filePath) {
        return ObjectUtils.isEmpty(filePath) ? List.of() : List.of(filePath);
    }

    /**
     * 两个来源路径列表的合并（去重保序，自身在前、对方追加在后）：
     * 剔除空白元素与既有溢出占位元素后按首次出现顺序去重。
     * <p>用于实体/关系属性 merge 与骨架合并——merge 阶段只做并集，
     * 上限截断与占位追加统一交写回前的 {@link #normalize}。</p>
     *
     * @param first  自身列表，可为空
     * @param second 对方列表，可为空
     * @return 不可变的去重保序并集
     */
    public static List<String> union(List<String> first, List<String> second) {
        Set<String> merged = new LinkedHashSet<>();
        appendRealPaths(merged, first);
        appendRealPaths(merged, second);
        return List.copyOf(merged);
    }

    /**
     * 落库前归一（合并路径与重建路径的唯一截断口径）：
     * 先剔除空白元素与既有占位元素并去重保序，条数超过 {@code limit} 时保留前 N 条
     * （KEEP 策略）并在末尾追加溢出占位元素；未超限保持原样、不追加占位。
     *
     * @param filePaths       待归一的来源路径列表，可为空
     * @param limit           保留上限（由 {@code GraphMergeParams#sourceFilePathsLimit} 供给；
     *                        非正值视为不截断，防御配置异常）
     * @param placeholderWord 溢出占位词（{@code GraphMergeParams#sourceFilePathsPlaceholder}；
     *                        空白回落 {@link #DEFAULT_PLACEHOLDER_WORD}）
     * @return 归一后的不可变列表（超限时末元素为占位元素，长度恒为 limit + 1）
     */
    public static List<String> normalize(List<String> filePaths, int limit, String placeholderWord) {
        List<String> deduped = new ArrayList<>(union(filePaths, List.of()));
        int total = deduped.size();
        if (limit <= 0 || total <= limit) {
            return List.copyOf(deduped);
        }
        List<String> kept = new ArrayList<>(deduped.subList(0, limit));
        kept.add(buildPlaceholder(limit, total, placeholderWord));
        return List.copyOf(kept);
    }

    /**
     * 构造溢出占位元素：{@code 占位词(KEEP;保留<保留数>/共<总数>)}。
     *
     * @param keptCount         保留条数（= 截断上限）
     * @param totalCount        去重后的真实来源总条数
     * @param placeholderWord   占位词（空白回落 {@link #DEFAULT_PLACEHOLDER_WORD}）
     * @return 占位元素文本
     */
    public static String buildPlaceholder(int keptCount, int totalCount, String placeholderWord) {
        String word = StringUtils.isBlank(placeholderWord) ? DEFAULT_PLACEHOLDER_WORD : placeholderWord;
        return String.format(PLACEHOLDER_ELEMENT_FORMAT, word, keptCount, totalCount);
    }

    /**
     * 判定一个元素是否为溢出占位元素（可辨识口径见类注释）。
     *
     * @param element 待判定元素，可为 null
     * @return 命中占位元素格式返回 true
     */
    public static boolean isOverflowPlaceholder(String element) {
        return StringUtils.isNotBlank(element) && PLACEHOLDER_PATTERN.matcher(element).matches();
    }

    /**
     * 将列表中「非空白且非占位元素」的路径按首次出现顺序追加进目标集合（LinkedHashSet 天然去重保序）。
     *
     * @param target 目标集合（就地追加）
     * @param paths  来源列表，可为空
     */
    private static void appendRealPaths(Set<String> target, List<String> paths) {
        if (ObjectUtils.isEmpty(paths)) {
            return;
        }
        for (String path : paths) {
            if (StringUtils.isNotBlank(path) && !isOverflowPlaceholder(path)) {
                target.add(path);
            }
        }
    }
}
