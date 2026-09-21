package com.linkroa.deepdataagent.rag.domain.service;

import com.linkroa.deepdataagent.rag.domain.model.ChunkVO;
import com.linkroa.deepdataagent.rag.domain.model.ContentBlockVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;

/**
 * {@link QaChunkStrategy} 六路级联（JSON→标记交替→表格→问题栈→无标记交替→整块兜底）单元测试。
 * <p>覆盖六路正反用例与输出归一：①标记混排中英文同路统一 {@code 问题：x⇥回答：y}；
 * ②英文散文行 "A good day" 不误触 A 标记；③单逗号散文不达表格阈值不产假对；④带表头三列
 * CSV 跳过表头且第三列仅入元数据；⑤JSON 数组扩展字段仅入元数据不进正文；⑥问题栈路
 * {@code ---} 不入答案、裸标题不产空答案块；⑦无标记交替成对产出与 <2 对整体放弃；
 * ⑧全形状不命中落整块兜底且非空；⑨既有两列 TSV / Markdown 栈语义回归。
 * TokenCounter 使用 Mockito mock，桩以「长度即 token 数」保证确定性。</p>
 */
@ExtendWith(MockitoExtension.class)
class QaChunkStrategyTest {

    /** 表格路第 3 列起的注入 meta 键（与 QaChunkStrategy 私有常量约定一致）。 */
    private static final String META_KEY_EXTRA_COLUMNS = "_qa_extra_columns";

    @Mock
    private TokenCounter counter;

    @InjectMocks
    private QaChunkStrategy strategy;

    /**
     * 统一桩：token 计数按字符串长度返回（确定性口径，覆盖所有路线的调用点）。
     */
    @BeforeEach
    void setUp() {
        lenient().when(counter.count(anyString()))
                .thenAnswer(invocation -> ((String) invocation.getArgument(0)).length());
    }

    /**
     * 场景：Q:/A: 与 问：/答： 标记混排的两个问答对。
     * 预期：标记路命中产 2 对，中文内容输出统一 {@code 问题：x⇥回答：y}。
     */
    @Test
    void should_outputTwoNormalizedCnPairs_when_split_given_mixedCnEnMarkerLines() {
        // given
        String text = "Q: 如何重置密码？\nA: 点击设置页面。\n问：会员权益有哪些？\n答：包含积分与折扣。";

        // when
        List<ChunkVO> chunks = splitText(text);

        // then
        assertEquals(2, chunks.size(), "标记路应解析出 2 个问答对");
        assertEquals("问题：如何重置密码？\t回答：点击设置页面。", chunks.get(0).text());
        assertEquals("问题：会员权益有哪些？\t回答：包含积分与折扣。", chunks.get(1).text());
        verify(counter, atLeastOnce()).count(anyString());
    }

    /**
     * 场景：英文问答标记文本中夹一行散文 "A good day to all!"（其后仅空格）。
     * 预期：该行不被识别为 A 标记，作为答案续行并入第一对；英文内容输出英文前缀归一。
     */
    @Test
    void should_notTreatProseLineAsAnswerMarker_when_split_given_englishLineStartingWithAWord() {
        // given
        String text = "Q: Where is the exit?\nA: Turn left.\nA good day to all!\nIt is sunny.";

        // when
        List<ChunkVO> chunks = splitText(text);

        // then
        assertEquals(1, chunks.size(), "散文行不得开启新答案段，全文应只有 1 对");
        assertEquals("Question: Where is the exit?\tAnswer: Turn left.\nA good day to all!\nIt is sunny.",
                chunks.get(0).text(), "英文内容应使用 Question/Answer 英文前缀归一");
    }

    /**
     * 场景：500 行纯叙述散文中仅 10 行恰含 1 个半角逗号（其余行 1 列），无任何疑问形态。
     * 预期：逗号多列行占比 2% 不达表格路 80% 阈值，不产出逗号劈开的假问答对，落整块兜底返回原整块。
     */
    @Test
    void should_fallbackWholeBlock_when_split_given_proseWithFewSingleCommaLines() {
        // given
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 500; i++) {
            if (i > 0) {
                sb.append('\n');
            }
            if (i % 50 == 0) {
                sb.append("例行巡检项目").append(i).append("完成, 状态正常。");
            } else {
                sb.append("例行巡检项目").append(i).append("已完成数据核对。");
            }
        }
        String text = sb.toString();

        // when
        List<ChunkVO> chunks = splitText(text);

        // then
        assertEquals(1, chunks.size(), "表格阈值不成立且无其他形状，应整块兜底为单 chunk");
        assertEquals(text, chunks.get(0).text(), "兜底块应保留整块原文，不得出现逗号劈开的假对");
        assertFalse(chunks.get(0).text().startsWith("问题："), "兜底块不得携带问答归一前缀");
    }

    /**
     * 场景：三列 CSV，首行表头 {@code question,answer,分类}，两条数据行三列。
     * 预期：表头行被识别跳过（不产出「问题：question」对）；前两列成对；第三列仅进该 chunk 元数据。
     */
    @Test
    void should_skipHeaderAndPutThirdColumnInMeta_when_split_given_threeColumnCsvWithHeader() {
        // given
        String text = "question,answer,分类\n"
                + "如何重置密码？,点击安全中心重置,账号\n"
                + "会员权益有哪些？,包含积分与折扣,权益";

        // when
        List<ChunkVO> chunks = splitText(text);

        // then
        assertEquals(2, chunks.size(), "表头跳过后两条数据行应各产 1 对");
        assertEquals("问题：如何重置密码？\t回答：点击安全中心重置", chunks.get(0).text());
        assertFalse(chunks.get(0).text().contains("question"), "表头词不得进入块正文");
        Map<String, Object> meta = chunks.get(0).block().meta();
        assertInstanceOf(Map.class, meta, "第三列应写入 chunk 元数据");
        assertEquals(List.of("账号"), meta.get(META_KEY_EXTRA_COLUMNS));
        assertEquals(List.of("权益"), chunks.get(1).block().meta().get(META_KEY_EXTRA_COLUMNS));
        assertFalse(chunks.get(0).text().contains("账号"), "附加列不得拼进块正文");
    }

    /**
     * 场景：含 question/similar_questions/answer/tags 键的 JSON 数组（单个问答对象）。
     * 预期：JSON 路命中产 1 chunk，正文为归一问答；similar_questions 与 tags 仅在元数据、不在块正文。
     */
    @Test
    void should_putJsonExtrasOnlyInMeta_when_split_given_jsonArrayWithSimilarQuestions() {
        // given
        String text = "[{\"question\":\"如何重置密码？\",\"similar_questions\":[\"忘记密码怎么办\"],"
                + "\"answer\":\"进入安全中心重置\",\"tags\":[\"账号\",\"安全\"]}]";

        // when
        List<ChunkVO> chunks = splitText(text);

        // then
        assertEquals(1, chunks.size());
        assertEquals("问题：如何重置密码？\t回答：进入安全中心重置", chunks.get(0).text());
        assertFalse(chunks.get(0).text().contains("忘记密码"), "相似问法不得拼进块正文");
        Map<String, Object> meta = chunks.get(0).block().meta();
        assertEquals(List.of("忘记密码怎么办"), meta.get("similar_questions"));
        assertEquals(List.of("账号", "安全"), meta.get("tags"));
    }

    /**
     * 场景：多级 Markdown 标题手册，标题间夹 {@code ---} 水平分隔线，末尾为无正文裸标题。
     * 预期：问题为整条标题路径、正文成答案；{@code ---} 行不进入答案；裸标题不产出空答案块。
     */
    @Test
    void should_excludeRuleAndDropBareHeading_when_split_given_markdownManualWithHeadingStack() {
        // given
        String text = "# 产品手册\n## 如何安装软件\n准备安装包。\n---\n双击运行即可。\n## 卸载指南";

        // when
        List<ChunkVO> chunks = splitText(text);

        // then
        assertEquals(1, chunks.size(), "仅一个有正文的标题产出问答对，裸标题应丢弃");
        assertEquals("问题：产品手册\n如何安装软件\t回答：准备安装包。\n双击运行即可。", chunks.get(0).text(),
                "水平分隔线行不得并入答案");
    }

    /**
     * 场景：无任何标记/表格/标题形状的四行短文本，问答以「疑问行+答案行」交替出现两次。
     * 预期：无标记交替路命中产 2 对并归一输出。
     */
    @Test
    void should_outputTwoPairs_when_split_given_unmarkedAlternatingShortLines() {
        // given
        String text = "如何重置密码？\n进入安全中心点击重置。\n会员权益有哪些？\n包含积分兑换与折扣。";

        // when
        List<ChunkVO> chunks = splitText(text);

        // then
        assertEquals(2, chunks.size());
        assertEquals("问题：如何重置密码？\t回答：进入安全中心点击重置。", chunks.get(0).text());
        assertEquals("问题：会员权益有哪些？\t回答：包含积分兑换与折扣。", chunks.get(1).text());
    }

    /**
     * 场景：无标记交替形态但全文仅可识别 1 对。
     * 预期：成对数 <2 整体放弃启发式，落整块兜底返回原文单 chunk。
     */
    @Test
    void should_giveUpHeuristicAndFallback_when_split_given_singleUnmarkedPairOnly() {
        // given
        String text = "如何重置密码？\n进入安全中心点击重置。";

        // when
        List<ChunkVO> chunks = splitText(text);

        // then
        assertEquals(1, chunks.size(), "仅 1 对应整体放弃启发式落兜底");
        assertEquals(text, chunks.get(0).text(), "兜底块应为整块原文，不带问答归一前缀");
    }

    /**
     * 场景：单句陈述文本，六种形状均不命中。
     * 预期：落整块兜底且结果非空（策略不变量：不得返回空列表）。
     */
    @Test
    void should_returnSingleNonEmptyChunk_when_split_given_noShapeMatchedAtAll() {
        // given
        String text = "本系统用于订单管理，提供报表导出与数据看板。";

        // when
        List<ChunkVO> chunks = splitText(text);

        // then
        assertEquals(1, chunks.size(), "兜底必须返回非空单 chunk");
        assertEquals(text, chunks.get(0).text());
        assertEquals(1, chunks.get(0).sequence().intValue());
    }

    /**
     * 场景：既有两列 TSV（无前缀）回归——两行 tab 分隔的问答。
     * 预期：表格路命中，语义与旧实现一致，输出归一 {@code 问题：q⇥回答：a}。
     */
    @Test
    void should_keepTsvPairSemantics_when_split_given_twoColumnTsvRegression() {
        // given
        String text = "如何退款？\t联系客服处理。\n多久到账？\t三个工作日。";

        // when
        List<ChunkVO> chunks = splitText(text);

        // then
        assertEquals(2, chunks.size());
        assertEquals("问题：如何退款？\t回答：联系客服处理。", chunks.get(0).text());
        assertEquals("问题：多久到账？\t回答：三个工作日。", chunks.get(1).text());
    }

    /**
     * 场景：既有单行「问题：x⇥答案：y」TSV 回归——每行同时含问题与答案标记段。
     * 预期：标记路单行内拆分成立，产出与旧实现一致的归一输出（标签统一为 回答：）。
     */
    @Test
    void should_splitEmbeddedQaInSingleLine_when_split_given_prefixedTsvPairsRegression() {
        // given
        String text = "问题：如何退款？\t答案：联系客服处理。\n问题：多久到账？\t答案：三个工作日。";

        // when
        List<ChunkVO> chunks = splitText(text);

        // then
        assertEquals(2, chunks.size());
        assertEquals("问题：如何退款？\t回答：联系客服处理。", chunks.get(0).text());
        assertEquals("问题：多久到账？\t回答：三个工作日。", chunks.get(1).text());
    }

    /**
     * 场景：文本以 {@code {} 开头但并非合法 JSON，其后跟标准 Q/A 标记行。
     * 预期：JSON 路解析失败静默降级，标记路正常命中产 1 对。
     */
    @Test
    void should_degradeToNextRoute_when_split_given_malformedJsonLeadingText() {
        // given
        String text = "{不是合法JSON\nQ: 如何重置密码？\nA: 点击重置按钮。";

        // when
        List<ChunkVO> chunks = splitText(text);

        // then
        assertEquals(1, chunks.size(), "JSON 解析失败应落下一级标记路");
        assertEquals("问题：如何重置密码？\t回答：点击重置按钮。", chunks.get(0).text());
    }

    /**
     * 场景：仅问题标记、答案标记后无内容的单行文本（问答不齐备）。
     * 预期：标记路丢弃裸问对后为空，级联各级均不产出，最终落整块兜底且非空。
     */
    @Test
    void should_discardQuestionOnlyPairAndFallback_when_split_given_questionWithoutAnyAnswer() {
        // given
        String text = "问题：如何退订服务？\t答案：";

        // when
        List<ChunkVO> chunks = splitText(text);

        // then
        assertEquals(1, chunks.size(), "丢弃裸问对后应落整块兜底，兜底不得返回空列表");
        assertEquals(text, chunks.get(0).text());
    }

    /**
     * 场景：split 入参 block 为 null。
     * 预期：参数校验抛出 IllegalArgumentException。
     */
    @Test
    void should_throwIllegalArgumentException_when_split_given_nullBlock() {
        // given & when & then
        assertThrows(IllegalArgumentException.class,
                () -> strategy.split(null, ChunkParams.defaults(), counter), "空内容块应快速失败");
    }

    /**
     * 场景：策略注册与块类型支持判定。
     * 预期：method 返回 {@code qa}，支持 TEXT 块、不支持 IMAGE 块。
     */
    @Test
    void should_reportQaMethodAndSupportTextOnly_when_method_given_variousBlocks() {
        // given
        ContentBlockVO image = new ContentBlockVO(ContentBlockVO.TYPE_IMAGE, "图片占位", null);

        // when & then
        assertEquals("qa", strategy.method());
        assertTrue(strategy.supports(new ContentBlockVO(ContentBlockVO.TYPE_TEXT, "文本", null)));
        assertFalse(strategy.supports(image));
    }

    /**
     * 构造 TEXT 内容块并以默认参数执行切分（QA 不消费 params，统一走 {@link ChunkParams#defaults()}）。
     *
     * @param text 块文本
     * @return 切分结果
     */
    private List<ChunkVO> splitText(String text) {
        ContentBlockVO block = new ContentBlockVO(ContentBlockVO.TYPE_TEXT, text, null);
        return strategy.split(block, ChunkParams.defaults(), counter);
    }
}
