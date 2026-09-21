package com.linkroa.deepdataagent.rag.domain.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link RelationEdge} 单元测试（贡献记录合并权威口径：逐来源全额累加，与 LightRAG 合并端同构）。
 * <p>覆盖：账本过滤、幂等重放零增量、null 来源无条件累加、批内不去重 quirk（+2.0）、
 * 双参权威入口（计权基准 = 传入向量账本，图行展示列不作基准；账本为空 fail-open 全额累加）、
 * 骨架字段（描述/关键词/filePaths 去重保序并集）语义与合并守卫。</p>
 */
class RelationEdgeTest {

    /** 双精度比较容差 */
    private static final double DELTA = 1e-9;

    /** 知识库ID */
    private static final Long KB_ID = 7L;

    /**
     * 构造带账本的边：指定已存权重、来源账本、描述与文件路径。
     */
    private static RelationEdge edgeWithLedger(double weight, List<Long> sourceIds, String description,
                                               String filePath) {
        return new RelationEdge(KB_ID, "部门", "公司",
                new RelationProperties(weight, description, List.of("归属"), sourceIds, filePath));
    }

    /**
     * 场景：贡献记录列表为空（含 null）。
     * 预期：返回自身同一实例，不做任何计算。
     */
    @Test
    void should_returnSelf_when_mergeContributions_given_emptyRecords() {
        // given
        RelationEdge base = edgeWithLedger(10.0, List.of(1L), "部门归属于公司", "kb/7/a.pdf");

        // when & then
        assertSame(base, base.mergeContributions(List.of()));
        assertSame(base, base.mergeContributions(null));
    }

    /**
     * 场景：图边账本 {1}/10.0（belongs_to 混合权重边），入批 3 个普通 chunk 来源（2/3/4 各 1.0）。
     * 预期：新来源逐条全额累加 → weight=13.0（旧均摊口径会失真为 19.75）；账本旧值优先并入为 [1,2,3,4]。
     */
    @Test
    void should_sumFullWeightOfNewSources_when_mergeContributions_given_mixedLedger() {
        // given
        RelationEdge base = edgeWithLedger(10.0, List.of(1L), "部门归属于公司", "kb/7/a.pdf");
        List<RelationContribution> records = List.of(
                new RelationContribution(2L, 1.0),
                new RelationContribution(3L, 1.0),
                new RelationContribution(4L, 1.0));

        // when
        RelationEdge merged = base.mergeContributions(records);

        // then
        assertEquals(13.0, merged.properties().weight(), DELTA, "新来源应按记录全额累加而非均摊");
        assertEquals(List.of(1L, 2L, 3L, 4L), merged.properties().sourceIds(), "账本应旧值优先并入");
    }

    /**
     * 场景：账本 [1,2,3,4]/13.0 的边，重放同一批记录（同集合重放）。
     * 预期：所有来源均已在账本 → 新增恒为 0，weight 与 sourceIds 不变（幂等）。
     */
    @Test
    void should_addNothing_when_mergeContributions_given_allSourcesAlreadyInLedger() {
        // given
        RelationEdge base = edgeWithLedger(13.0, List.of(1L, 2L, 3L, 4L), "部门归属于公司", "kb/7/a.pdf");
        List<RelationContribution> records = List.of(
                new RelationContribution(1L, 10.0),
                new RelationContribution(2L, 1.0),
                new RelationContribution(3L, 1.0),
                new RelationContribution(4L, 1.0));

        // when
        RelationEdge merged = base.mergeContributions(records);

        // then
        assertEquals(13.0, merged.properties().weight(), DELTA, "幂等重放新增必须为 0");
        assertEquals(List.of(1L, 2L, 3L, 4L), merged.properties().sourceIds());
    }

    /**
     * 场景：双参权威入口——入批来源 2/3 均已被传入向量账本 [1,2,3] 覆盖，
     * 而图行展示列 sourceIds 仅剩 [1]（截断形态）。
     * 预期：计权以传入账本为过滤基准 → 新增恒为 0（weight 不变）；展示列仍保序并入新来源。
     */
    @Test
    void should_addNothing_when_mergeContributionsWithLedger_given_ledgerCoversAllSources() {
        // given：图行展示列被截断为 [1]，权威向量账本为 [1,2,3]
        RelationEdge base = edgeWithLedger(13.0, List.of(1L), "部门归属于公司", "kb/7/a.pdf");
        List<RelationContribution> records = List.of(
                new RelationContribution(2L, 1.0),
                new RelationContribution(3L, 1.0));

        // when
        RelationEdge merged = base.mergeContributions(records, List.of(1L, 2L, 3L));

        // then
        assertEquals(13.0, merged.properties().weight(), DELTA, "传入账本已覆盖的来源不得重复计权");
        assertEquals(List.of(1L, 2L, 3L), merged.properties().sourceIds(), "sourceIds 仍作展示列并入新来源");
    }

    /**
     * 场景：双参权威入口——传入账本为空列表与 null（无既记账基准，fail-open），
     * 入批来源 1 虽已在图行展示列中。
     * 预期：空/null 账本判「未覆盖」→ 记录全额累加（图行展示列 MUST NOT 作计权基准）；
     * 空列表与 null 两种入参行为等价。
     */
    @Test
    void should_addFullWeight_when_mergeContributionsWithLedger_given_emptyOrNullLedger() {
        // given
        RelationEdge base = edgeWithLedger(10.0, List.of(1L), "部门归属于公司", "kb/7/a.pdf");
        List<RelationContribution> records = List.of(new RelationContribution(1L, 1.0));

        // when
        RelationEdge withEmptyLedger = base.mergeContributions(records, List.of());
        RelationEdge withNullLedger = base.mergeContributions(records, null);

        // then
        assertEquals(11.0, withEmptyLedger.properties().weight(), DELTA, "空账本 fail-open 应全额累加");
        assertEquals(11.0, withNullLedger.properties().weight(), DELTA, "null 账本与空账本 fail-open 等价");
    }

    /**
     * 场景：均匀权重域行为不变（spec Scenario）——合并双方所有贡献记录权重均为 1.0（普通文本链路）。
     * 预期：逐来源全额累加与原均摊公式
     * （{@code 已存 weight + 入批权重 × 新来源数 / 入批来源总数}）在该域数学等价，
     * 存量普通边不因公式变更产生行为漂移。
     */
    @Test
    void should_matchApportionedFormula_when_mergeContributions_given_uniformUnitWeights() {
        // given：已存 2 个来源共 2.0（各 1.0），入批 3 条 1.0 记录（其中来源 2 已在账本内）
        double unitWeight = 1.0;
        List<Long> ledger = List.of(1L, 2L);
        RelationEdge base = edgeWithLedger(ledger.size() * unitWeight, ledger, "部门归属于公司", "kb/7/a.pdf");
        List<RelationContribution> records = List.of(
                new RelationContribution(2L, unitWeight),
                new RelationContribution(3L, unitWeight),
                new RelationContribution(4L, unitWeight));
        long newSourceCount = records.stream().map(RelationContribution::sourceId)
                .filter(source -> !ledger.contains(source)).count();

        // when
        RelationEdge merged = base.mergeContributions(records);

        // then：旧均摊口径 = 2.0 + 3.0 × 2/3，与本实现的逐来源全额累加 = 2.0 + 2×1.0 同值
        double apportionedWeight = ledger.size() * unitWeight
                + records.size() * unitWeight * newSourceCount / records.size();
        assertEquals(apportionedWeight, merged.properties().weight(), DELTA,
                "均匀权重域下全额累加与原均摊公式应数学等价");
        assertEquals(4.0, merged.properties().weight(), DELTA,
                "存量普通边 SHALL NOT 因本变更产生行为漂移");
        assertEquals(List.of(1L, 2L, 3L, 4L), merged.properties().sourceIds());
    }

    /**
     * 场景：记录来源为 null（无来源信息的全额贡献）。
     * 预期：无条件全额累加权重；账本不并入 null，保持原样。
     */
    @Test
    void should_addFullWeightUnconditionally_when_mergeContributions_given_nullSourceRecord() {
        // given
        RelationEdge base = edgeWithLedger(10.0, List.of(1L), "部门归属于公司", "kb/7/a.pdf");

        // when
        RelationEdge merged = base.mergeContributions(List.of(new RelationContribution(null, 10.0)));

        // then
        assertEquals(20.0, merged.properties().weight(), DELTA);
        assertEquals(List.of(1L), merged.properties().sourceIds(), "null 来源不应写入账本");
    }

    /**
     * 场景：空账本基底（新建边），同一新来源出现两条记录（同 chunk 双抽）。
     * 预期：过滤基准为合并前快照，两条各自全额计入 → weight=2.0、sourceIds=[1]（批内不去重 quirk 锁定）。
     */
    @Test
    void should_keepBatchDuplicateQuirk_when_mergeContributions_given_sameNewSourceTwice() {
        // given
        RelationEdge base = edgeWithLedger(0.0, List.of(), "部门归属于公司", null);
        List<RelationContribution> records = List.of(
                new RelationContribution(1L, 1.0),
                new RelationContribution(1L, 1.0));

        // when
        RelationEdge merged = base.mergeContributions(records);

        // then
        assertEquals(2.0, merged.properties().weight(), DELTA, "批内双抽应 +2.0（照抄 LightRAG 不修）");
        assertEquals(List.of(1L), merged.properties().sourceIds(), "账本仍去重为单值");
    }

    /**
     * 场景：贡献记录合并。
     * 预期：只改 weight 与 sourceIds，描述/关键词/filePaths 原样保留。
     */
    @Test
    void should_keepSkeletonFields_when_mergeContributions_given_validRecords() {
        // given
        RelationEdge base = edgeWithLedger(10.0, List.of(1L), "部门归属于公司", "kb/7/a.pdf");

        // when
        RelationEdge merged = base.mergeContributions(List.of(new RelationContribution(2L, 1.0)));

        // then
        assertEquals("部门归属于公司", merged.properties().description());
        assertEquals(List.of("归属"), merged.properties().keywords());
        assertEquals(List.of("kb/7/a.pdf"), merged.properties().filePaths(), "贡献记录合并不改动来源路径列表");
    }

    /**
     * 场景：骨架合并（this 描述齐备、双方各有来源路径，other 关键词不同）。
     * 预期：keywords 并集去重排序；description 以 this 为主；filePaths 去重保序并集（this 在前）；
     * weight/sourceIds 完全沿用 this 不受影响。
     */
    @Test
    void should_unionKeywordsAndKeepThisFields_when_mergeSkeleton_given_thisFieldsPresent() {
        // given
        RelationEdge base = edgeWithLedger(10.0, List.of(1L), "部门归属于公司", "kb/7/a.pdf");
        RelationEdge other = new RelationEdge(KB_ID, "部门", "公司",
                new RelationProperties(1.0, "另一描述", List.of("belong_to"), List.of(2L), "kb/7/b.pdf"));

        // when
        RelationEdge merged = base.mergeSkeleton(other);

        // then
        assertEquals(List.of("belong_to", "归属"), merged.properties().keywords(), "关键词应并集后自然排序");
        assertEquals("部门归属于公司", merged.properties().description());
        assertEquals(List.of("kb/7/a.pdf", "kb/7/b.pdf"), merged.properties().filePaths(),
                "来源路径应为去重保序并集（自身在前、对侧追加在后）");
        assertEquals(10.0, merged.properties().weight(), DELTA, "骨架合并不允许改权重");
        assertEquals(List.of(1L), merged.properties().sourceIds(), "骨架合并不允许改账本");
    }

    /**
     * 场景：骨架合并（this 描述空白、来源路径列表为空）。
     * 预期：description 取 other 兜底；filePaths 并集只余 other 侧路径。
     */
    @Test
    void should_takeOtherFields_when_mergeSkeleton_given_thisFieldsBlank() {
        // given
        RelationEdge base = edgeWithLedger(10.0, List.of(1L), "  ", null);
        RelationEdge other = new RelationEdge(KB_ID, "部门", "公司",
                new RelationProperties(1.0, "部门隶属于公司", List.of(), List.of(2L), "kb/7/b.pdf"));

        // when
        RelationEdge merged = base.mergeSkeleton(other);

        // then
        assertEquals("部门隶属于公司", merged.properties().description());
        assertEquals(List.of("kb/7/b.pdf"), merged.properties().filePaths());
    }

    /**
     * 场景：骨架合并（两侧描述均空白）。
     * 预期：抛 IllegalArgumentException 且不兜底（关系描述为必填）。
     */
    @Test
    void should_throw_when_mergeSkeleton_given_bothDescriptionsBlank() {
        // given
        RelationEdge base = edgeWithLedger(10.0, List.of(1L), null, null);
        RelationEdge other = edgeWithLedger(1.0, List.of(2L), "  ", null);

        // when & then
        assertThrows(IllegalArgumentException.class, () -> base.mergeSkeleton(other));
    }

    /**
     * 场景：骨架合并（跨知识库 / 不同无向端点对）。
     * 预期：分别抛错，拒绝合并。
     */
    @Test
    void should_throw_when_mergeSkeleton_given_incompatibleGuardViolated() {
        // given
        RelationEdge base = edgeWithLedger(10.0, List.of(1L), "部门归属于公司", null);
        RelationEdge otherKb = new RelationEdge(8L, "部门", "公司",
                new RelationProperties(1.0, "x", List.of(), List.of(2L), List.of()));
        RelationEdge otherPair = new RelationEdge(KB_ID, "员工", "公司",
                new RelationProperties(1.0, "x", List.of(), List.of(2L), List.of()));

        // when & then
        assertThrows(IllegalArgumentException.class, () -> base.mergeSkeleton(otherKb), "非同库应拒绝合并");
        assertThrows(IllegalArgumentException.class, () -> base.mergeSkeleton(otherPair), "不同端点对应拒绝合并");
    }

    /**
     * 场景：便捷组合 merge（单来源入批边 {2}/1.0 并入账本 [1]/10.0 的边）。
     * 预期：骨架与账本两口径同时生效 → weight=11.0、sourceIds=[1,2]。
     */
    @Test
    void should_mergeSkeletonAndContributions_when_merge_given_singleSourceIncoming() {
        // given
        RelationEdge base = edgeWithLedger(10.0, List.of(1L), "部门归属于公司", "kb/7/a.pdf");
        RelationEdge other = new RelationEdge(KB_ID, "公司", "部门",
                new RelationProperties(1.0, "公司下辖部门", List.of("下辖"), List.of(2L), "kb/7/b.pdf"));

        // when
        RelationEdge merged = base.merge(other);

        // then
        assertEquals(11.0, merged.properties().weight(), DELTA);
        assertEquals(List.of(1L, 2L), merged.properties().sourceIds());
        assertEquals("部门", merged.sourceName(), "方向应沿用本边原始方向");
    }

    /**
     * 场景：合并链路 filePaths 语义（incoming 路径为空白）。
     * 预期：空白元素不并入，来源路径列表保留既有行路径（graph-source-file-paths R1）。
     */
    @Test
    void should_keepExistingFilePath_when_merge_given_incomingFilePathBlank() {
        // given
        RelationEdge base = edgeWithLedger(10.0, List.of(1L), "部门归属于公司", "kb/7/exist.pdf");
        RelationEdge other = new RelationEdge(KB_ID, "部门", "公司",
                new RelationProperties(1.0, "部门归属于公司", List.of(), List.of(2L), "  "));

        // when
        RelationEdge merged = base.merge(other);

        // then
        assertEquals(List.of("kb/7/exist.pdf"), merged.properties().filePaths());
    }

    /**
     * 场景：合并链路 filePaths 语义（this 来源路径为空、incoming 有值）。
     * 预期：并集取 incoming 路径（graph-source-file-paths R1）。
     */
    @Test
    void should_takeIncomingFilePath_when_merge_given_thisFilePathBlank() {
        // given
        RelationEdge base = edgeWithLedger(10.0, List.of(1L), "部门归属于公司", null);
        RelationEdge other = new RelationEdge(KB_ID, "部门", "公司",
                new RelationProperties(1.0, "部门归属于公司", List.of(), List.of(2L), "kb/7/incoming.pdf"));

        // when
        RelationEdge merged = base.merge(other);

        // then
        assertEquals(List.of("kb/7/incoming.pdf"), merged.properties().filePaths());
    }

    /**
     * 场景：展开多来源边的贡献记录（含空账本边）。
     * 预期：每个来源一条全额记录；账本为空时展开为单条 null 来源记录。
     */
    @Test
    void should_expandPerSourceFullWeight_when_expandContributions_given_variousEdges() {
        // given
        RelationEdge multi = edgeWithLedger(10.0, List.of(1L, 2L), "部门归属于公司", null);
        RelationEdge noSource = edgeWithLedger(3.0, List.of(), "部门归属于公司", null);

        // when
        List<RelationContribution> expanded = RelationEdge.expandContributions(multi);
        List<RelationContribution> expandedEmpty = RelationEdge.expandContributions(noSource);

        // then
        assertEquals(2, expanded.size());
        assertEquals(10.0, expanded.get(0).weight(), DELTA, "每来源应携带边权重全额");
        assertEquals(List.of(new RelationContribution(null, 3.0)), expandedEmpty);
        assertEquals(List.of(), RelationEdge.expandContributions(null));
    }
}
