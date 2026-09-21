package com.linkroa.deepdataagent.rag.domain.service;

import com.linkroa.deepdataagent.rag.domain.model.ChunkVO;
import com.linkroa.deepdataagent.rag.domain.model.ContentBlockVO;
import com.linkroa.deepdataagent.rag.domain.service.DelimiterParser.Unit;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 表格分块策略（method={@code table}，/ 参考）。
 * <p>正文格式为 {@code - 列名: 值} 逐行，首行必须为表头。输出「每行 1 chunk」：
 * 表头行解析出列名（重名追加 {@code _1/_2} 去重），其后每个数据行独立成 chunk；列类型
 * 按整列正则投票推断（int/float/bool/datetime/text，平票按信息损失最小序
 * {@code text < datetime < float < int < bool}），结果随 {@code block.meta} 的
 * {@code columns}/{@code column_types} 键下发给检索期（参考列角色 metadata 用）。
 * 未识别出表头或全部行无法解析时兜底整块单 chunk（策略不变量：不得返回空列表）。
 * 说明：本实现未做列名拼音化与 typed 字段落库（对应参考 stable 字段语义，属落库侧职责）。</p>
 */
@Component
public class TableChunkStrategy implements ChunkStrategy {

    /** 策略方法名（注册表键，与 {@code ChunkStrategyRegistry} 映射一致）。 */
    public static final String METHOD = "table";

    /** 表头/数据行单元格正则：{@code - 列名: 值}（兼容全角冒号）。 */
    private static final Pattern ROW_CELL = Pattern.compile("^-\\s*([^:：]+)\\s*[:：]\\s*(.*)$");

    /** 整数（容忍 {@code %} 后缀，参考类型推断）。 */
    private static final Pattern TYPE_INT = Pattern.compile("^[+-]?[0-9]+%?$");

    /** 浮点。 */
    private static final Pattern TYPE_FLOAT = Pattern.compile("^[+-]?[0-9]+\\.[0-9]*$");

    /** 布尔字面量（中英文，参考布尔枚举）。 */
    private static final Pattern TYPE_BOOL = Pattern.compile("(?i)^(true|yes|是|false|no|否|✓|✔|√|×)$");

    /** 日期（简化 ISO / 斜杠格式）。 */
    private static final Pattern TYPE_DATE = Pattern.compile("^\\d{4}[-/]\\d{1,2}[-/]\\d{1,2}(\\s+\\d{1,2}:\\d{2})?");

    /** meta 键：表头列名列表（已去重）。 */
    private static final String META_KEY_COLUMNS = "columns";

    /** meta 键：列名 → 推断类型。 */
    private static final String META_KEY_COLUMN_TYPES = "column_types";

    /** 类型标签：文本。 */
    private static final String TYPE_TEXT = "text";

    /** 类型标签：整数。 */
    private static final String TYPE_LONG = "int";

    /** 类型标签：浮点。 */
    private static final String TYPE_FLOAT_STR = "float";

    /** 类型标签：布尔（落 typed 字段时同 keyword）。 */
    private static final String TYPE_BOOL_STR = "bool";

    /** 类型标签：日期时间。 */
    private static final String TYPE_DATE_STR = "datetime";

    @Override
    public String method() {
        return METHOD;
    }

    @Override
    public List<ChunkVO> split(ContentBlockVO block, ChunkParams params, TokenCounter counter) {
        String normalized = block.text().replace("\r\n", "\n").replace("\r", "\n");
        List<String> lines = new ArrayList<>();
        for (String line : normalized.split("\n", -1)) {
            if (StringUtils.isNotBlank(DelimiterParser.stripTags(line).trim())) {
                lines.add(line);
            }
        }
        if (lines.isEmpty()) {
            return DelimiterParser.toChunkVOs(List.of(DelimiterParser.passthroughUnit(block, counter)), counter);
        }
        // 首行必须为表头（匹配表格单元格文法）
        List<String> headerCells = parseRowCells(lines.get(0));
        if (headerCells.isEmpty() || lines.size() < 2) {
            return DelimiterParser.toChunkVOs(List.of(DelimiterParser.passthroughUnit(block, counter)), counter);
        }
        List<String> columns = uniqueColumns(headerCells);
        List<List<String>> dataRows = new ArrayList<>();
        List<Unit> units = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            List<String> cells = parseRowCells(lines.get(i));
            dataRows.add(cells);
            String raw = DelimiterParser.stripTags(lines.get(i));
            units.add(new Unit(raw, counter.count(raw), false, block));
        }
        Map<String, String> columnTypes = inferColumnTypes(columns, dataRows);
        return enrichMeta(DelimiterParser.toChunkVOs(units, counter), columns, columnTypes);
    }

    @Override
    public boolean supports(ContentBlockVO block) {
        return ContentBlockVO.TYPE_TEXT.equals(block.type());
    }

    /**
     * 解析一行为单元格列表：匹配 {@code - 列名: 值}，取列名与值。
     *
     * @param line 原始行文本（可能含坐标标签）
     * @return 单元格列表（结构不匹配返回空列表）
     */
    private static List<String> parseRowCells(String line) {
        String visible = DelimiterParser.stripTags(line).trim();
        var matcher = ROW_CELL.matcher(visible);
        List<String> cells = new ArrayList<>();
        while (matcher.find()) {
            cells.add(matcher.group(1).trim());
            cells.add(matcher.group(2).trim());
        }
        return cells;
    }

    /**
     * 列名去重（参考）：重名追加 {@code _1/_2}（首次出现不带后缀）。
     *
     * @param names 原始列名列表（顺序 = 表头排列顺序）
     * @return 去重后的列名列表
     */
    private static List<String> uniqueColumns(List<String> names) {
        List<String> out = new ArrayList<>();
        Map<String, Integer> seen = new HashMap<>();
        for (String name : names) {
            int count = seen.getOrDefault(name, 0);
            out.add(count == 0 ? name : name + "_" + count);
            seen.put(name, count + 1);
        }
        return out;
    }

    /**
     * 列类型推断（参考）：按列收集非空值正则判定类型并多数投票；
     * 平票按信息损失最小序 {@code text < datetime < float < int < bool} 取前者。
     *
     * @param columns  去重后的列名列表
     * @param dataRows 数据行的单元格列表（行内顺序与列对齐，缺列忽略）
     * @return 列名 → 推断类型映射
     */
    private static Map<String, String> inferColumnTypes(List<String> columns, List<List<String>> dataRows) {
        Map<String, Map<String, Integer>> counts = new HashMap<>();
        for (String column : columns) {
            counts.put(column, new LinkedHashMap<>());
            counts.get(column).put(TYPE_TEXT, 0);
            counts.get(column).put(TYPE_DATE_STR, 0);
            counts.get(column).put(TYPE_FLOAT_STR, 0);
            counts.get(column).put(TYPE_LONG, 0);
            counts.get(column).put(TYPE_BOOL_STR, 0);
        }
        for (List<String> row : dataRows) {
            for (int i = 0; i < Math.min(columns.size(), row.size()); i++) {
                if (row.get(i).isEmpty()) {
                    continue;
                }
                String type = inferCellType(row.get(i));
                counts.get(columns.get(i)).merge(type, 1, Integer::sum);
            }
        }
        Map<String, String> types = new LinkedHashMap<>();
        for (String column : columns) {
            Map<String, Integer> columnCounts = counts.get(column);
            String best = TYPE_TEXT;
            int bestCount = -1;
            for (Map.Entry<String, Integer> entry : columnCounts.entrySet()) {
                if (entry.getValue() > bestCount) {
                    best = entry.getKey();
                    bestCount = entry.getValue();
                }
            }
            types.put(column, best);
        }
        return types;
    }

    /**
     * 单格类型判定（正则回退，参考类型推断字符串分支）：int → float → bool → datetime → text。
     *
     * @param value 单元格文本（已 trim 非空）
     * @return 推断类型
     */
    private static String inferCellType(String value) {
        if (TYPE_INT.matcher(value).matches()) {
            return TYPE_LONG;
        }
        if (TYPE_FLOAT.matcher(value).matches()) {
            return TYPE_FLOAT_STR;
        }
        if (TYPE_BOOL.matcher(value).matches()) {
            return TYPE_BOOL_STR;
        }
        if (TYPE_DATE.matcher(value).matches()) {
            return TYPE_DATE_STR;
        }
        return TYPE_TEXT;
    }

    /**
     * 为每个 chunk 附加表头列名与列类型元数据（合并到来源块 meta，保留原 positions）。
     *
     * @param chunks      基础分块结果
     * @param columns     去重后的列名列表
     * @param columnTypes 列名 → 类型映射
     * @return 增强 meta 后的分块结果（顺序与序号保持不变）
     */
    private static List<ChunkVO> enrichMeta(List<ChunkVO> chunks, List<String> columns,
                                            Map<String, String> columnTypes) {
        List<ChunkVO> out = new ArrayList<>();
        int sequence = 1;
        for (ChunkVO chunk : chunks) {
            ContentBlockVO source = chunk.block();
            Map<String, Object> meta = new HashMap<>();
            if (source != null && source.meta() != null) {
                meta.putAll(source.meta());
            }
            meta.put(META_KEY_COLUMNS, columns);
            meta.put(META_KEY_COLUMN_TYPES, columnTypes);
            var enrichedBlock = new ContentBlockVO(
                    source != null ? source.type() : ContentBlockVO.TYPE_TEXT,
                    source != null ? source.text() : chunk.text(), meta);
            out.add(new ChunkVO(sequence++, chunk.text(), chunk.tokens(), enrichedBlock));
        }
        return out;
    }
}