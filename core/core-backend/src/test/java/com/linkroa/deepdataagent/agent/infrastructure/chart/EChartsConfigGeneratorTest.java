package com.linkroa.deepdataagent.agent.infrastructure.chart;

import com.linkroa.deepdataagent.agent.domain.model.ChartConfig;
import com.linkroa.deepdataagent.agent.domain.model.ChartType;
import com.linkroa.deepdataagent.agent.infrastructure.client.LLMClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

/**
 * {@link EChartsConfigGenerator} 单元测试
 * <p>覆盖 generate 主流程、空结果、null 参数、不同图表类型分支、失败兜底及标题截断。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EChartsConfigGeneratorTest {

    @Mock
    private LLMClient llmClient;

    private EChartsConfigGenerator generator;

    @BeforeEach
    void setUp() {
        generator = new EChartsConfigGenerator(llmClient);
    }

    /**
     * 构造单行查询结果
     */
    private List<Map<String, Object>> singleRowResult() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", "A");
        row.put("value", 10L);
        return new ArrayList<>(List.of(row));
    }

    @Test
    void should_returnEmptyChart_whenGenerate_givenEmptyQueryResult() {
        // given
        List<Map<String, Object>> queryResult = new ArrayList<>();

        // when
        ChartConfig config = generator.generate(1L, queryResult, "问题");

        // then
        assertEquals(ChartType.TABLE, config.chartType());
        assertEquals("{}", config.echartsOption());
        assertEquals("数据表格", config.title());
        assertEquals("暂无数据", config.description());
    }

    @Test
    void should_returnEmptyChart_whenGenerate_givenNullQueryResult() {
        // when
        ChartConfig config = generator.generate(1L, null, "问题");

        // then
        assertEquals(ChartType.TABLE, config.chartType());
        assertEquals("{}", config.echartsOption());
    }

    @Test
    void should_returnEmptyChart_whenGenerate_givenLLMClientThrows() {
        // given
        List<Map<String, Object>> queryResult = singleRowResult();
        doThrow(new RuntimeException("LLM failed")).when(llmClient)
                .generateChartConfig(anyLong(), anyString(), anyString());

        // when
        ChartConfig config = generator.generate(1L, queryResult, "问题");

        // then
        assertEquals(ChartType.TABLE, config.chartType());
        assertEquals("{}", config.echartsOption());
        assertEquals("暂无数据", config.description());
    }

    @Test
    void should_returnLineChart_whenGenerate_givenLineChartJson() {
        // given
        List<Map<String, Object>> queryResult = singleRowResult();
        doReturn("{\"series\":[{\"type\":\"line\"}]}").when(llmClient)
                .generateChartConfig(anyLong(), anyString(), anyString());

        // when
        ChartConfig config = generator.generate(1L, queryResult, "销量趋势");

        // then
        assertEquals(ChartType.LINE, config.chartType());
        assertEquals("{\"series\":[{\"type\":\"line\"}]}", config.echartsOption());
        assertEquals("销量趋势", config.title());
        assertEquals("自动生成的 折线图", config.description());
    }

    @Test
    void should_returnBarChart_whenGenerate_givenBarChartJson() {
        // given
        List<Map<String, Object>> queryResult = singleRowResult();
        doReturn("{\"series\":[{\"type\":\"bar\"}]}").when(llmClient)
                .generateChartConfig(anyLong(), anyString(), anyString());

        // when
        ChartConfig config = generator.generate(1L, queryResult, "分类对比");

        // then
        assertEquals(ChartType.BAR, config.chartType());
        assertEquals("自动生成的 柱状图", config.description());
    }

    @Test
    void should_returnPieChart_whenGenerate_givenPieChartJson() {
        // given
        List<Map<String, Object>> queryResult = singleRowResult();
        doReturn("{\"series\":[{\"type\":\"pie\"}]}").when(llmClient)
                .generateChartConfig(anyLong(), anyString(), anyString());

        // when
        ChartConfig config = generator.generate(1L, queryResult, "占比分析");

        // then
        assertEquals(ChartType.PIE, config.chartType());
        assertEquals("自动生成的 饼图", config.description());
    }

    @Test
    void should_returnScatterChart_whenGenerate_givenScatterChartJson() {
        // given
        List<Map<String, Object>> queryResult = singleRowResult();
        doReturn("{\"series\":[{\"type\":\"scatter\"}]}").when(llmClient)
                .generateChartConfig(anyLong(), anyString(), anyString());

        // when
        ChartConfig config = generator.generate(1L, queryResult, "相关性分析");

        // then
        assertEquals(ChartType.SCATTER, config.chartType());
        assertEquals("自动生成的 散点图", config.description());
    }

    @Test
    void should_returnTableChart_whenGenerate_givenNoChartTypeInJson() {
        // given
        List<Map<String, Object>> queryResult = singleRowResult();
        doReturn("{\"option\":\"custom\"}").when(llmClient)
                .generateChartConfig(anyLong(), anyString(), anyString());

        // when
        ChartConfig config = generator.generate(1L, queryResult, "原始数据");

        // then
        assertEquals(ChartType.TABLE, config.chartType());
        assertEquals("自动生成的 数据表格", config.description());
    }

    @Test
    void should_truncateTitle_whenGenerate_givenLongQuestion() {
        // given
        List<Map<String, Object>> queryResult = singleRowResult();
        String longQuestion = "这是一个非常长的用户问题，用来验证标题超过三十个字符时会被截断并追加省略号";
        doReturn("{\"type\":\"line\"}").when(llmClient)
                .generateChartConfig(anyLong(), anyString(), anyString());

        // when
        ChartConfig config = generator.generate(1L, queryResult, longQuestion);

        // then
        assertEquals(longQuestion.substring(0, 27) + "...", config.title());
    }

    @Test
    void should_keepFullTitle_whenGenerate_givenShortQuestion() {
        // given
        List<Map<String, Object>> queryResult = singleRowResult();
        String shortQuestion = "趋势";
        doReturn("{\"type\":\"line\"}").when(llmClient)
                .generateChartConfig(anyLong(), anyString(), anyString());

        // when
        ChartConfig config = generator.generate(1L, queryResult, shortQuestion);

        // then
        assertEquals(shortQuestion, config.title());
    }

    @Test
    void should_useUnknownType_whenGenerate_givenNullValueInFirstRow() {
        // given
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", null);
        row.put("value", 10L);
        List<Map<String, Object>> queryResult = new ArrayList<>(List.of(row));
        doReturn("{\"type\":\"bar\"}").when(llmClient)
                .generateChartConfig(anyLong(), anyString(), anyString());

        // when
        ChartConfig config = generator.generate(1L, queryResult, "含空值数据");

        // then
        assertEquals(ChartType.BAR, config.chartType());
    }

    @Test
    void should_limitSampleRows_whenGenerate_givenLargeQueryResult() {
        // given
        List<Map<String, Object>> queryResult = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("idx", i);
            queryResult.add(row);
        }
        doReturn("{\"type\":\"line\"}").when(llmClient)
                .generateChartConfig(anyLong(), anyString(), anyString());

        // when
        ChartConfig config = generator.generate(1L, queryResult, "大数据量");

        // then
        assertEquals(ChartType.LINE, config.chartType());
        verify(llmClient).generateChartConfig(anyLong(), anyString(), anyString());
    }
}