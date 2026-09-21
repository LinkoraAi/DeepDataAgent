package com.linkroa.deepdataagent.rag.domain.model;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link GraphSourceFilePaths} 单元测试（来源文件路径列表归一共用实现，spec graph-source-file-paths R1/R2/R3）。
 * <p>覆盖：单值入口的 null 归一与空白原样承载；union 的去重保序、空白与既有占位元素剔除、
 * 自身在前对侧追加；normalize 的未超限保持原样（含恰等于上限的边界）、超限保留前 N 条并追加
 * 溢出占位元素（格式 {@code 占位词(KEEP;保留<保留数>/共<总数>)}）、占位词空白回落默认、
 * 非正上限不截断、幂等（已归一列表重归一不累积占位元素）；占位元素识别与构造口径。</p>
 *
 * @author DeepDataAgent
 */
class GraphSourceFilePathsTest {

    /** 上限测试值（小于默认 75 便于构造超限场景） */
    private static final int LIMIT = 3;

    /** 默认占位词下保留 2 条、共 3 条的占位元素（格式契约锁定字面量） */
    private static final String PLACEHOLDER_2_OF_3 = "…等(KEEP;保留2/共3)";

    /** 文档 A 路径 */
    private static final String PATH_A = "kb/7/A.docx";

    /** 文档 B 路径 */
    private static final String PATH_B = "kb/7/B.pdf";

    /** 文档 C 路径 */
    private static final String PATH_C = "kb/7/C.xlsx";

    /** 文档 D 路径 */
    private static final String PATH_D = "kb/7/D.md";

    // ===== singletonListOrEmpty =====

    @Test
    void should_returnEmptyList_when_singletonListOrEmpty_given_nullPath() {
        // given：单值入口传入 null

        // when
        List<String> result = GraphSourceFilePaths.singletonListOrEmpty(null);

        // then
        assertEquals(List.of(), result, "null 路径应归一为空列表");
    }

    @Test
    void should_returnSingletonList_when_singletonListOrEmpty_given_nonNullPath() {
        // given：正常路径与空白路径（模型层不裁剪，归一层统一剔除）

        // when & then
        assertEquals(List.of(PATH_A), GraphSourceFilePaths.singletonListOrEmpty(PATH_A));
        assertEquals(List.of("   "), GraphSourceFilePaths.singletonListOrEmpty("   "),
                "空白路径应原样承载为单元素列表，裁剪口径在归一层");
    }

    // ===== union =====

    @Test
    void should_keepOwnFirstAndAppendOther_when_union_given_pathsFromTwoDocuments() {
        // given：两侧各一篇来源
        List<String> self = List.of(PATH_A);
        List<String> other = List.of(PATH_B);

        // when
        List<String> merged = GraphSourceFilePaths.union(self, other);

        // then：跨文档共享含两篇且自身在前（去重保序）
        assertEquals(List.of(PATH_A, PATH_B), merged, "并集应含全部来源且自身在前、对侧追加在后");
    }

    @Test
    void should_recordOnce_when_union_given_samePathOnBothSides() {
        // given：同一文件重复贡献（两侧同路径）
        List<String> self = List.of(PATH_A);
        List<String> other = List.of(PATH_A, PATH_B);

        // when
        List<String> merged = GraphSourceFilePaths.union(self, other);

        // then：同路径只记一次
        assertEquals(List.of(PATH_A, PATH_B), merged, "同一文件重复贡献只记一次");
    }

    @Test
    void should_dropBlankAndLegacyPlaceholder_when_union_given_dirtyElements() {
        // given：混入空白元素、null 元素与上一轮截断遗留的占位元素
        List<String> self = new ArrayList<>(List.of(PATH_A, "  ", PLACEHOLDER_2_OF_3));
        List<String> other = Arrays.asList(null, PATH_B);

        // when
        List<String> merged = GraphSourceFilePaths.union(self, other);

        // then：空白与占位元素被剔除，真实路径按首现序保留
        assertEquals(List.of(PATH_A, PATH_B), merged, "并集应剔除空白元素与既有占位元素");
    }

    @Test
    void should_returnOther_when_union_given_nullOrEmptySelf() {
        // given：一侧为空（null 与空列表等价）

        // when & then
        assertEquals(List.of(PATH_A), GraphSourceFilePaths.union(null, List.of(PATH_A)));
        assertEquals(List.of(PATH_A), GraphSourceFilePaths.union(List.of(), List.of(PATH_A)));
        assertEquals(List.of(), GraphSourceFilePaths.union(null, null), "两侧皆空应得空列表");
    }

    // ===== normalize =====

    @Test
    void should_keepAsIsWithoutPlaceholder_when_normalize_given_pathsBelowLimit() {
        // given：去重后 2 条，未超过上限 3

        // when
        List<String> result = GraphSourceFilePaths.normalize(List.of(PATH_A, PATH_B), LIMIT,
                GraphSourceFilePaths.DEFAULT_PLACEHOLDER_WORD);

        // then：原样保持、不追加占位元素（spec「未超限时保持原样」）
        assertEquals(List.of(PATH_A, PATH_B), result, "未超限列表 MUST 保持原样");
        assertFalse(result.contains(PLACEHOLDER_2_OF_3), "未超限 MUST NOT 追加占位元素");
    }

    @Test
    void should_notAppendPlaceholder_when_normalize_given_pathsExactlyAtLimit() {
        // given：去重后条数恰等于上限（边界值）
        List<String> paths = List.of(PATH_A, PATH_B, PATH_C);

        // when
        List<String> result = GraphSourceFilePaths.normalize(paths, LIMIT,
                GraphSourceFilePaths.DEFAULT_PLACEHOLDER_WORD);

        // then：恰等于上限不触发截断
        assertEquals(paths, result, "恰等于上限属未超限，列表应保持原样");
    }

    @Test
    void should_truncateToFirstNAndAppendPlaceholder_when_normalize_given_pathsExceedLimit() {
        // given：去重后 4 条超过上限 3
        List<String> paths = List.of(PATH_A, PATH_B, PATH_C, PATH_D);

        // when
        List<String> result = GraphSourceFilePaths.normalize(paths, LIMIT,
                GraphSourceFilePaths.DEFAULT_PLACEHOLDER_WORD);

        // then：裁剪至上限 + 末位占位元素标明「KEEP 策略与保留数/总数」（spec「超出上限时截断并标记」）
        assertEquals(List.of(PATH_A, PATH_B, PATH_C, "…等(KEEP;保留3/共4)"), result,
                "超限应保留前 N 条并追加含策略与数量的占位元素");
        assertEquals(LIMIT + 1, result.size(), "超限后列表长度恒为上限加一（末位占位）");
    }

    @Test
    void should_useDefaultWord_when_normalize_given_blankPlaceholderWord() {
        // given：占位词为空白，超限场景

        // when
        List<String> result = GraphSourceFilePaths.normalize(List.of(PATH_A, PATH_B, PATH_C, PATH_D),
                LIMIT, "   ");

        // then：回落默认占位词「…等」
        assertEquals("…等(KEEP;保留3/共4)", result.get(result.size() - 1), "空白占位词应回落默认");
    }

    @Test
    void should_useCustomWord_when_normalize_given_customPlaceholderWord() {
        // given：库级自定义占位词

        // when
        List<String> result = GraphSourceFilePaths.normalize(List.of(PATH_A, PATH_B, PATH_C, PATH_D),
                LIMIT, "其余文件");

        // then：占位元素采用自定义词
        assertEquals("其余文件(KEEP;保留3/共4)", result.get(result.size() - 1), "自定义占位词应生效");
    }

    @Test
    void should_beIdempotent_when_normalize_given_alreadyNormalizedListWithPlaceholder() {
        // given：上一轮已截断的列表（含占位元素）再次进入合并与归一
        List<String> previouslyTruncated = List.of(PATH_A, PATH_B, PATH_C, "…等(KEEP;保留3/共4)");

        // when：反复合并不累积占位元素、总数按真实路径计
        List<String> result = GraphSourceFilePaths.normalize(previouslyTruncated, LIMIT,
                GraphSourceFilePaths.DEFAULT_PLACEHOLDER_WORD);

        // then：占位元素被剔除后仅 3 条真实路径，未超限 → 无占位（幂等稳定形态）
        assertEquals(List.of(PATH_A, PATH_B, PATH_C), result,
                "历史截断丢弃的路径不可恢复，总数按可辨识真实路径计，重归一不累积占位");
    }

    @Test
    void should_skipTruncation_when_normalize_given_nonPositiveLimit() {
        // given：上限为非正值（防御配置异常 → 视为不截断）
        List<String> paths = List.of(PATH_A, PATH_B, PATH_C, PATH_D);

        // when
        List<String> result = GraphSourceFilePaths.normalize(paths, 0,
                GraphSourceFilePaths.DEFAULT_PLACEHOLDER_WORD);

        // then：仅去重保序，不截断、不追加占位
        assertEquals(paths, result, "非正上限应视为不截断（防御配置异常）");
    }

    // ===== buildPlaceholder / isOverflowPlaceholder =====

    @Test
    void should_renderContractFormat_when_buildPlaceholder_given_counts() {
        // given：默认占位词、保留 75 共 120（spec 默认上限口径）

        // when
        String placeholder = GraphSourceFilePaths.buildPlaceholder(75, 120,
                GraphSourceFilePaths.DEFAULT_PLACEHOLDER_WORD);

        // then：格式为「占位词(KEEP;保留<保留数>/共<总数>)」
        assertEquals("…等(KEEP;保留75/共120)", placeholder, "占位元素格式契约");
    }

    @Test
    void should_identifyByFixedSuffix_when_isOverflowPlaceholder_given_variousElements() {
        // given：标准占位元素、自定义词占位元素、普通路径、null/空白
        String standard = GraphSourceFilePaths.buildPlaceholder(2, 3, GraphSourceFilePaths.DEFAULT_PLACEHOLDER_WORD);
        String customWord = GraphSourceFilePaths.buildPlaceholder(2, 3, "其余文件");

        // when & then
        assertTrue(GraphSourceFilePaths.isOverflowPlaceholder(standard), "标准占位元素应可辨识");
        assertTrue(GraphSourceFilePaths.isOverflowPlaceholder(customWord), "识别依赖固定尾缀而非占位词");
        assertFalse(GraphSourceFilePaths.isOverflowPlaceholder(PATH_A), "普通路径不是占位元素");
        assertFalse(GraphSourceFilePaths.isOverflowPlaceholder(null), "null 应返回 false 不抛错");
        assertFalse(GraphSourceFilePaths.isOverflowPlaceholder(" "), "空白应返回 false");
    }

    @Test
    void should_useDefaults_when_constants_given_specDefaults() {
        // given & when & then：spec R2 默认口径（上限 75、占位词「…等」、策略 KEEP）
        assertEquals(75, GraphSourceFilePaths.DEFAULT_LIMIT, "默认上限应为 75");
        assertEquals("…等", GraphSourceFilePaths.DEFAULT_PLACEHOLDER_WORD, "默认占位词应为「…等」");
        assertEquals("KEEP", GraphSourceFilePaths.TRUNCATION_KEEP, "截断策略标识应为 KEEP");
    }
}
