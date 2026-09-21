package com.linkroa.deepdataagent.rag.domain.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RelationProperties} 单元测试（域模型 record：filePaths 列表承载与工厂方法）。
 * <p>模型层不做任何非空校验，filePath 为空（null/空白）不阻断对象构造
 * （graph-source-file-paths R1：null 归一空列表、非 null 原样承载为单元素列表，
 * 空白裁剪与上限截断统一由 {@link GraphSourceFilePaths} 归一层承担）。</p>
 */
class RelationPropertiesTest {

    /** 双精度比较容差 */
    private static final double DELTA = 1e-9;

    /**
     * 场景：以全字段（含 filePaths 列表）直接构造 record。
     * 预期：五个组件逐一可读出，filePaths 原样承载。
     */
    @Test
    void should_returnAllComponentsIncludingFilePaths_when_new_given_fullComponents() {
        // given
        List<String> keywords = List.of("归属", "依赖");
        List<Long> sourceIds = List.of(101L, 102L);

        // when
        RelationProperties properties = new RelationProperties(13.0, "部门归属于公司", keywords, sourceIds,
                List.of("kb/2024/组织架构.pdf", "kb/2024/人员名册.xlsx"));

        // then
        assertEquals(13.0, properties.weight(), DELTA);
        assertEquals("部门归属于公司", properties.description());
        assertEquals(keywords, properties.keywords());
        assertEquals(sourceIds, properties.sourceIds());
        assertEquals(List.of("kb/2024/组织架构.pdf", "kb/2024/人员名册.xlsx"), properties.filePaths(),
                "filePaths 应作为 record 组件原样承载");
    }

    /**
     * 场景：通过 {@code of} 工厂以单来源与文件路径新建属性。
     * 预期：filePath 包装为单元素列表承载，权重/描述/来源同时按入参落位。
     */
    @Test
    void should_wrapFilePathAsList_when_of_given_singleSourceAndFilePath() {
        // given
        String filePath = "kb/7/产品手册.docx";

        // when
        RelationProperties properties = RelationProperties.of(10.0, "属于", List.of("belongs_to"), 305L, filePath);

        // then
        assertEquals(List.of(filePath), properties.filePaths(), "单来源路径应包装为单元素列表");
        assertEquals(10.0, properties.weight(), DELTA);
        assertEquals("属于", properties.description());
        assertEquals(List.of(305L), properties.sourceIds());
    }

    /**
     * 场景：{@code of} 工厂传入 null filePath（抽取端未带来源路径）。
     * 预期：不抛异常且其余字段不受影响，filePaths 归一为空列表（不虚构来源路径）。
     */
    @Test
    void should_keepOtherFieldsIntact_when_of_given_nullFilePath() {
        // given
        List<String> keywords = List.of("负责", "负责", "  ");

        // when
        RelationProperties properties = RelationProperties.of(1.0, "张三负责项目", keywords, 401L, null);

        // then
        assertTrue(properties.filePaths().isEmpty(), "null filePath 应归一为空列表而非抛错");
        assertEquals(1.0, properties.weight(), DELTA);
        assertEquals("张三负责项目", properties.description());
        assertEquals(List.of(401L), properties.sourceIds());
        assertEquals(List.of("负责"), properties.keywords(), "关键词仍按去重去空白规则处理");
    }

    /**
     * 场景：单路径便捷构造传入空白串。
     * 预期：模型层不裁剪空白，空白原值以单元素列表承载（裁剪口径在归一层 {@link GraphSourceFilePaths}）。
     */
    @Test
    void should_keepBlankFilePathAsIs_when_new_given_blankFilePath() {
        // given
        String blankFilePath = "   ";

        // when
        RelationProperties properties = new RelationProperties(2.0, "关联", List.of("k"), List.of(501L),
                blankFilePath);

        // then
        assertEquals(List.of(blankFilePath), properties.filePaths(), "模型层不裁剪空白，保留原值由归一层处理");
        assertEquals(2.0, properties.weight(), DELTA);
        assertEquals(List.of(501L), properties.sourceIds());
    }

    /**
     * 场景：新建边的默认空属性。
     * 预期：零权重、无描述、空关键词、空来源账本与空来源路径列表。
     */
    @Test
    void should_returnZeroWeightAndEmptyFilePaths_when_empty_given_noInput() {
        // when
        RelationProperties properties = RelationProperties.empty();

        // then
        assertEquals(0.0, properties.weight(), DELTA);
        assertNull(properties.description());
        assertTrue(properties.keywords().isEmpty());
        assertTrue(properties.sourceIds().isEmpty());
        assertTrue(properties.filePaths().isEmpty(), "空属性不应虚构来源路径");
    }

    /**
     * 场景：两份属性仅 filePaths 不同。
     * 预期：record 值相等判定区分 filePaths（JSONB 往返与幂等比较依赖该语义）。
     */
    @Test
    void should_notBeEqual_when_equals_given_differentFilePathsOnly() {
        // given
        RelationProperties withPath = new RelationProperties(1.0, "关联", List.of("k"), List.of(601L),
                List.of("a.md"));
        RelationProperties otherPath = new RelationProperties(1.0, "关联", List.of("k"), List.of(601L),
                List.of("b.md"));
        RelationProperties samePath = new RelationProperties(1.0, "关联", List.of("k"), List.of(601L),
                List.of("a.md"));

        // when / then
        assertNotEquals(withPath, otherPath, "filePaths 参与值相等判定");
        assertEquals(withPath, samePath);
        assertEquals(withPath.hashCode(), samePath.hashCode());
    }
}
