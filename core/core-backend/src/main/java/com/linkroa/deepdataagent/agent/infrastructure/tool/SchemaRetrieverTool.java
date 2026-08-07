package com.linkroa.deepdataagent.agent.infrastructure.tool;

import com.linkroa.deepdataagent.agent.acl.datasource.DatasourceGateway;
import com.linkroa.deepdataagent.agent.infrastructure.util.AgentToolResponse;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Schema 检索工具
 * <p>根据数据源 ID 提取数据库 Schema 信息，供 Agent 调用。</p>
 */
@Component
public class SchemaRetrieverTool {

    private static final Logger log = LoggerFactory.getLogger(SchemaRetrieverTool.class);

    /** 表头行前缀，用于将 Schema 文本切分为表块 */
    private static final String TABLE_HEADER_PREFIX = "表: ";

    /** 关键词无匹配时返回的提示文本 */
    private static final String NO_MATCH_MESSAGE = "未找到与关键词相关的表";

    private final DatasourceGateway datasourceGateway;

    public SchemaRetrieverTool(DatasourceGateway datasourceGateway) {
        this.datasourceGateway = datasourceGateway;
    }

    @Tool(name = "retrieve_schema",
          description = "Retrieve database schema information for a given datasource. " +
                        "This includes table names, column names, data types, and comments. " +
                        "Call this FIRST before generating any SQL to understand the database structure.")
    public String retrieveSchema(
            @ToolParam(name = "datasourceId", required = true,
                       description = "The ID of the datasource connection") Long datasourceId,
            @ToolParam(name = "keyword", required = false,
                       description = "Optional keyword to filter relevant tables") String keyword
    ) {
        log.info("SchemaRetrieverTool: retrieving schema for datasource={}, keyword={}", datasourceId, keyword);
        try {
            String schema = datasourceGateway.extractSchema(datasourceId);
            if (keyword != null && !keyword.isBlank()) {
                schema = filterByKeyword(schema, keyword);
            }
            log.info("SchemaRetrieverTool: schema retrieved, length={}", schema.length());
            return schema;
        } catch (Exception e) {
            log.error("SchemaRetrieverTool: failed to retrieve schema", e);
            return AgentToolResponse.error("Failed to retrieve schema: " + e.getMessage());
        }
    }

    /**
     * 根据关键词过滤 Schema 文本
     * <p>按表块（表头行 + 其后的字段行）为最小过滤单元：
     * 表头行命中关键词，或任一字段行命中关键词，则保留整张表。
     * 避免逐行过滤导致"字段匹配但所属表头被丢弃"的结构破坏。</p>
     *
     * @param schema 完整的 Schema 文本
     * @param keyword 过滤关键词
     * @return 过滤后的 Schema 文本；若无匹配返回提示文本
     */
    private String filterByKeyword(String schema, String keyword) {
        String lowerKeyword = keyword.toLowerCase(Locale.ROOT);
        List<String> blocks = splitIntoBlocks(schema);
        StringBuilder filtered = new StringBuilder();
        for (String block : blocks) {
            if (blockMatched(block, lowerKeyword)) {
                filtered.append(block).append("\n\n");
            }
        }
        String result = filtered.toString().strip();
        return result.isEmpty() ? NO_MATCH_MESSAGE : result;
    }

    /**
     * 将 Schema 文本切分为表块列表
     * <p>以"表: "开头的行作为表头，切分出一个新表块；
     * 其后以"-"开头的字段行归属当前表块。</p>
     *
     * @param schema 完整的 Schema 文本
     * @return 表块列表，每个表块由表头行和字段行组成
     */
    private List<String> splitIntoBlocks(String schema) {
        List<String> blocks = new ArrayList<>();
        String currentBlock = null;
        for (String line : schema.split("\n")) {
            if (line.startsWith(TABLE_HEADER_PREFIX)) {
                if (currentBlock != null) {
                    blocks.add(currentBlock);
                }
                currentBlock = line;
            } else if (currentBlock != null && line.strip().startsWith("-")) {
                currentBlock = currentBlock + "\n" + line;
            }
        }
        if (currentBlock != null) {
            blocks.add(currentBlock);
        }
        return blocks;
    }

    /**
     * 判断表块是否命中关键词
     * <p>表头行或任一字段行包含关键词即视为命中。</p>
     *
     * @param block 单个表块文本
     * @param lowerKeyword 小写化的关键词
     * @return 命中返回 true，否则 false
     */
    private boolean blockMatched(String block, String lowerKeyword) {
        for (String line : block.split("\n")) {
            if (line.toLowerCase(Locale.ROOT).contains(lowerKeyword)) {
                return true;
            }
        }
        return false;
    }
}