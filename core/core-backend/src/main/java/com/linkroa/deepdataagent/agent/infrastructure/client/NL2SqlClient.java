package com.linkroa.deepdataagent.agent.infrastructure.client;

import com.linkroa.deepdataagent.agent.domain.service.port.NL2SqlPort;
import org.springframework.stereotype.Component;

/**
 * NL2SQL 生成客户端
 * <p>实现领域层 {@link NL2SqlPort} 端口接口，将用户问题与数据库 schema 转换为 SQL 查询。
 * 提示词约束输出单条 SELECT、方言语法与 NULL 处理，具体调用由 {@link LLMInvoker} 执行。</p>
 */
@Component
public class NL2SqlClient implements NL2SqlPort {

    private final LLMInvoker llmInvoker;

    /**
     * 构造方法
     *
     * @param llmInvoker LLM 通用调用器
     */
    public NL2SqlClient(LLMInvoker llmInvoker) {
        this.llmInvoker = llmInvoker;
    }

    /**
     * 将自然语言转换为 SQL 查询语句
     *
     * @param modelConfigId 模型配置 ID
     * @param userQuestion  用户问题
     * @param schemaInfo    数据库 schema 信息
     * @param sqlDialect    SQL 方言（MySQL / ClickHouse）
     * @param sessionId     会话 ID（用于流式回调，可为 null）
     * @return 生成的 SQL 语句
     */
    @Override
    public String generateSql(Long modelConfigId, String userQuestion, String schemaInfo, String sqlDialect,
                              String sessionId) {
        String systemPrompt = """
                你是一个精通 %s 的 SQL 专家。根据用户问题和数据库 schema，生成对应的 SQL 查询。

                ## 规则
                1. 只能输出一条 SELECT 语句，禁止 INSERT/UPDATE/DELETE/DROP/ALTER 等操作，禁止输出多条语句
                2. 直接输出纯 SQL 语句，不要添加任何解释、注释或 markdown 代码块标记
                3. 分号（;）最多一个且只能出现在语句末尾，字符串字面量内不要出现分号
                4. 使用 %s 语法
                5. 表名和字段名使用反引号包裹（MySQL）或双引号（ClickHouse）
                6. 对于聚合查询，使用有意义的别名（AS）
                7. 考虑 NULL 值处理，必要时使用 COALESCE 或 IFNULL
                8. 如果涉及时间范围，使用 BETWEEN 或 >= <= 比较符

                ## 示例
                用户问题：查询每个部门的员工数量
                输出：SELECT department, COUNT(*) AS employee_count FROM employees GROUP BY department ORDER BY employee_count DESC

                用户问题：最近一个月的销售额趋势
                输出：SELECT DATE(order_date) AS date, SUM(amount) AS total_sales FROM orders WHERE order_date >= DATE_SUB(CURDATE(), INTERVAL 1 MONTH) GROUP BY DATE(order_date) ORDER BY date
                """.formatted(sqlDialect, sqlDialect);

        String userPrompt = """
                数据库 schema：
                %s

                用户问题：%s
                请生成对应的 SQL 查询：
                """.formatted(schemaInfo, userQuestion);

        return llmInvoker.invoke(modelConfigId, systemPrompt, userPrompt, sessionId);
    }
}