package com.linkroa.deepdataagent.rag.domain.model;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link EntityProperties} 单元测试（实体侧合并语义）。
 * <p>覆盖：单来源工厂的去 null 来源与空白描述口径、类型频次取首规则（频次降序、同频新数据优先）、
 * merge 主类型纯按合并后票表频次降序取首（UNKNOWN 占位自愈语义已退役，不再按类型形态特判）、
 * merge 的 description 自身优先与对侧回落（5.1 合并链路保留语义）、
 * filePaths 去重保序并集（graph-source-file-paths R1：跨文档含多篇、同文件只记一次、空白剔除）、
 * 描述原文跨新旧精确去重与来源并集保序。</p>
 */
class EntityPropertiesTest {

    /** 类型：人物 */
    private static final String TYPE_PERSON = "PERSON";

    /** 类型：组织 */
    private static final String TYPE_ORG = "ORGANIZATION";

    /** 自身侧描述 */
    private static final String DESC_SELF = "Alice 是项目经理";

    /** 对侧描述 */
    private static final String DESC_OTHER = "Alice 负责项目交付";

    /** 自身侧文件路径 */
    private static final String FILE_PATH_SELF = "kb/7/产品手册.docx";

    /** 对侧文件路径 */
    private static final String FILE_PATH_OTHER = "kb/7/组织架构.pdf";

    /** 来源分块ID 101 */
    private static final Long SOURCE_101 = 101L;

    /** 来源分块ID 102 */
    private static final Long SOURCE_102 = 102L;

    /** 来源分块ID 103 */
    private static final Long SOURCE_103 = 103L;

    /**
     * 构造仅携带类型与频次票表的属性（用于精准控制票数场景）。
     *
     * @param entityType 主类型
     * @param votes      类型频次票表
     * @return 属性对象
     */
    private static EntityProperties withVotes(String entityType, Map<String, Integer> votes) {
        return new EntityProperties(entityType, null, List.of(), List.of(), votes, List.of());
    }

    /**
     * 场景：单来源工厂传入完整的类型 / 描述 / 来源 / 路径。
     * 预期：sourceIds 为单元素列表，类型票表计 1 票，非空白描述进入累积列表，各组件原值承载。
     */
    @Test
    void should_recordSingleSourceAndVote_when_of_given_fullArguments() {
        // when
        EntityProperties properties = EntityProperties.of(TYPE_PERSON, DESC_SELF, SOURCE_101, FILE_PATH_SELF);

        // then
        assertEquals(List.of(SOURCE_101), properties.sourceIds(), "单来源应归一为单元素列表");
        assertEquals(Map.of(TYPE_PERSON, 1), properties.entityTypeVotes());
        assertEquals(List.of(DESC_SELF), properties.descriptions());
        assertEquals(TYPE_PERSON, properties.entityType());
        assertEquals(DESC_SELF, properties.description());
        assertEquals(List.of(FILE_PATH_SELF), properties.filePaths(), "单来源路径应包装为单元素列表");
    }

    /**
     * 场景：单来源工厂的类型与描述均为空白。
     * 预期：不产生类型票、不累积描述（空白原值仍由 record 组件透传承载）。
     */
    @Test
    void should_produceEmptyTrackers_when_of_given_blankTypeAndDescription() {
        // when
        EntityProperties properties = EntityProperties.of("   ", "   ", SOURCE_101, null);

        // then
        assertTrue(properties.entityTypeVotes().isEmpty(), "空白类型不应计入频次票表");
        assertTrue(properties.descriptions().isEmpty(), "空白描述不应进入累积列表");
        assertEquals(List.of(SOURCE_101), properties.sourceIds());
    }

    /**
     * 场景：单来源工厂传入 null 来源。
     * 预期：sourceIds 归一为空列表（无来源可溯）。
     */
    @Test
    void should_useEmptySources_when_of_given_nullSourceId() {
        // when
        EntityProperties properties = EntityProperties.of(TYPE_PERSON, DESC_SELF, null, FILE_PATH_SELF);

        // then
        assertTrue(properties.sourceIds().isEmpty(), "null 来源应归一为空列表");
    }

    /**
     * 场景：主类型取首入参为空票表（null 与空 Map）。
     * 预期：返回 null（无票可取）。
     */
    @Test
    void should_returnNull_when_primaryEntityType_given_emptyVotes() {
        // when & then
        assertNull(EntityProperties.primaryEntityType(null), "null 票表应返回 null");
        assertNull(EntityProperties.primaryEntityType(Map.of()), "空票表应返回 null");
    }

    /**
     * 场景：票表频次互不相同（与插入顺序无关）。
     * 预期：频次最高者被取首为主类型。
     */
    @Test
    void should_pickHighestFrequencyFirst_when_primaryEntityType_given_distinctFrequencies() {
        // given：ORG 高票分别置于插入顺序首/尾两种排列
        Map<String, Integer> orgFirst = new LinkedHashMap<>();
        orgFirst.put(TYPE_ORG, 7);
        orgFirst.put(TYPE_PERSON, 2);
        Map<String, Integer> personFirst = new LinkedHashMap<>();
        personFirst.put(TYPE_PERSON, 2);
        personFirst.put(TYPE_ORG, 7);

        // when & then
        assertEquals(TYPE_ORG, EntityProperties.primaryEntityType(orgFirst), "频次最高者应被取首");
        assertEquals(TYPE_ORG, EntityProperties.primaryEntityType(personFirst), "同解与插入顺序无关");
    }

    /**
     * 场景：两类型票数相同。
     * 预期：后出现者（新数据）优先胜出。
     */
    @Test
    void should_preferLaterEntry_when_primaryEntityType_given_tiedFrequencies() {
        // given
        Map<String, Integer> tied = new LinkedHashMap<>();
        tied.put(TYPE_PERSON, 3);
        tied.put(TYPE_ORG, 3);

        // when
        String primary = EntityProperties.primaryEntityType(tied);

        // then
        assertEquals(TYPE_ORG, primary, "同频时应取后出现（较新）的类型");
    }

    /**
     * 场景（主类型纯频次取首）：一侧票表 ORG 9 票，另一侧新抽取 PERSON 1 票。
     * 预期：主类型即合并后票表频次降序之首（ORG），不按类型形态做 UNKNOWN 自愈特判；
     * 合并后票表保留全部历史票数。
     */
    @Test
    void should_pickHigherFrequencyType_when_merge_given_asymmetricVotes() {
        // given
        EntityProperties highVotes = withVotes(TYPE_ORG, Map.of(TYPE_ORG, 9));
        EntityProperties extracted = EntityProperties.of(TYPE_PERSON, DESC_SELF, SOURCE_101, FILE_PATH_SELF);

        // when
        EntityProperties merged = highVotes.merge(extracted);

        // then
        assertEquals(TYPE_ORG, merged.entityType(), "主类型应取合并后票表频次降序之首");
        assertEquals(Map.of(TYPE_ORG, 9, TYPE_PERSON, 1), merged.entityTypeVotes(), "双方历史票数应全部保留");
    }

    /**
     * 场景（合并同频裁决）：双方票数相同（PERSON 3 对 ORG 3），并入方后出现。
     * 预期：主类型取后出现者（新数据优先），与 {@code primaryEntityType} 同频规则一致。
     */
    @Test
    void should_preferLaterType_when_merge_given_tiedVotes() {
        // given
        EntityProperties self = withVotes(TYPE_PERSON, Map.of(TYPE_PERSON, 3));
        EntityProperties other = withVotes(TYPE_ORG, Map.of(TYPE_ORG, 3));

        // when
        EntityProperties merged = self.merge(other);

        // then
        assertEquals(TYPE_ORG, merged.entityType(), "合并后同频时应取后出现（较新）的类型");
        assertEquals(Map.of(TYPE_PERSON, 3, TYPE_ORG, 3), merged.entityTypeVotes());
    }

    /**
     * 场景（graph-source-file-paths R1 跨文档共享）：双方 filePaths 各有一篇来源路径。
     * 预期：合并结果的 filePaths 同时包含两篇路径（自身在前、对侧追加在后，去重保序）；
     * description 仍自身优先保留，不被对侧覆盖。
     */
    @Test
    void should_containBothFilePathsOwnFirst_when_merge_given_pathsFromTwoDocuments() {
        // given
        EntityProperties self = EntityProperties.of(TYPE_PERSON, DESC_SELF, SOURCE_101, FILE_PATH_SELF);
        EntityProperties other = EntityProperties.of(TYPE_PERSON, DESC_OTHER, SOURCE_102, FILE_PATH_OTHER);

        // when
        EntityProperties merged = self.merge(other);

        // then
        assertEquals(List.of(FILE_PATH_SELF, FILE_PATH_OTHER), merged.filePaths(),
                "跨文档共享实体的来源路径应含全部来源且自身在前");
        assertEquals(DESC_SELF, merged.description(), "description 应自身优先保留");
    }

    /**
     * 场景（graph-source-file-paths R1 同文件重复贡献）：双方来源路径相同（同一文档多个分块）。
     * 预期：该文件路径只记一次。
     */
    @Test
    void should_recordPathOnce_when_merge_given_sameFilePathFromBothSides() {
        // given
        EntityProperties self = EntityProperties.of(TYPE_PERSON, DESC_SELF, SOURCE_101, FILE_PATH_SELF);
        EntityProperties other = EntityProperties.of(TYPE_PERSON, DESC_OTHER, SOURCE_102, FILE_PATH_SELF);

        // when
        EntityProperties merged = self.merge(other);

        // then
        assertEquals(List.of(FILE_PATH_SELF), merged.filePaths(), "同一文件重复贡献只记一次");
    }

    /**
     * 场景（回落口径）：自身 filePaths 仅有空白元素、description 为 null。
     * 预期：归并时空白路径被剔除，仅保留对侧真实路径；description 回落对侧值。
     */
    @Test
    void should_fallbackToOtherFilePath_when_merge_given_blankOwnFilePath() {
        // given
        EntityProperties self = EntityProperties.of(TYPE_PERSON, null, SOURCE_101, "   ");
        EntityProperties other = EntityProperties.of(TYPE_PERSON, DESC_OTHER, SOURCE_102, FILE_PATH_OTHER);

        // when
        EntityProperties merged = self.merge(other);

        // then
        assertEquals(List.of(FILE_PATH_OTHER), merged.filePaths(), "自身空白路径应被剔除、只余对侧真实路径");
        assertEquals(DESC_OTHER, merged.description(), "自身 description 为空应回落对侧值");
    }

    /**
     * 场景：双方来源与描述存在交叠（含对侧空白描述）。
     * 预期：来源取保序并集，描述跨新旧精确去重且空白描述不累积。
     */
    @Test
    void should_dedupeDescriptionsAndUnionSources_when_merge_given_overlappingTrail() {
        // given：多来源形态以全参构造直接装配（多来源工厂重载已退役）
        EntityProperties self = new EntityProperties(TYPE_PERSON, DESC_SELF,
                List.of(SOURCE_101, SOURCE_102), List.of(FILE_PATH_SELF), Map.of(TYPE_PERSON, 1),
                List.of(DESC_SELF));
        EntityProperties other = new EntityProperties(TYPE_PERSON, DESC_OTHER,
                List.of(SOURCE_102, SOURCE_103), List.of(FILE_PATH_OTHER), Map.of(TYPE_PERSON, 1),
                List.of(DESC_SELF, DESC_OTHER, "   "));

        // when
        EntityProperties merged = self.merge(other);

        // then
        assertEquals(List.of(SOURCE_101, SOURCE_102, SOURCE_103), merged.sourceIds(), "来源应为保序并集");
        assertEquals(List.of(DESC_SELF, DESC_OTHER), merged.descriptions(), "重复与空白描述不应累积");
        assertEquals(List.of(FILE_PATH_SELF, FILE_PATH_OTHER), merged.filePaths(),
                "来源路径应为去重保序并集");
    }

    /**
     * 场景：合并对象为 null。
     * 预期：直接返回自身实例（null 视为空属性，不产生新对象）。
     */
    @Test
    void should_returnSelf_when_merge_given_nullOther() {
        // given
        EntityProperties self = EntityProperties.of(TYPE_PERSON, DESC_SELF, SOURCE_101, FILE_PATH_SELF);

        // when
        EntityProperties merged = self.merge(null);

        // then
        assertSame(self, merged, "对侧为 null 时应原样返回自身");
    }

    /**
     * 场景：空属性工厂。
     * 预期：类型/描述为 null，来源路径与三个追踪容器均为空列表。
     */
    @Test
    void should_returnEmptyTrackers_when_empty_given_noArguments() {
        // when
        EntityProperties properties = EntityProperties.empty();

        // then
        assertNull(properties.entityType());
        assertNull(properties.description());
        assertTrue(properties.filePaths().isEmpty(), "空属性的来源路径列表应为空");
        assertTrue(properties.sourceIds().isEmpty());
        assertTrue(properties.entityTypeVotes().isEmpty());
        assertTrue(properties.descriptions().isEmpty());
    }
}
