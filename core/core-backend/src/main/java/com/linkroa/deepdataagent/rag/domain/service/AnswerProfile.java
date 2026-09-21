package com.linkroa.deepdataagent.rag.domain.service;

import com.linkroa.deepdataagent.rag.infrastructure.prompts.catalog.PromptTemplates;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 「模板形态」单点判据值对象（Stage 4 上下文构建与 Stage 5 答案生成共用）。
 *
 * <p><b>存在理由</b>：MIX/NAIVE 两种形态各自对应一对模板——上下文骨架模板
 * （{@code kg_query_context} / {@code naive_query_context}）与回答系统模板
 * （{@code rag_response} / {@code naive_rag_response}），且回答模板的内容槽变量名
 * 亦随形态变化（{@code context_data} / {@code content_data}）。此前 Stage 4 以
 * {@code kgMode}、Stage 5 以 {@code cfg.strategyType()} 各自判模板，属同一业务判据的
 * 两处副本：任一侧改形态而另一侧漏改，即出现「上下文按图谱骨架渲染、回答按朴素模板作答」
 * 的静默错配。本值对象把「形态 → 模板名 + 默认变量」收为唯一供给点，两侧各取所需。</p>
 *
 * <p><b>Stage 4 的用途</b>：除取上下文模板名外，还需以 {@link #responseVars(String)}
 * （内容槽置空串）渲染回答模板，得到「回答系统提示词空算 token 数」并入 chunk 预算扣减
 * ——该扣减必须与 Stage 5 实际送入的模板形态与默认变量一致，否则预算仍会系统性超支。</p>
 *
 * <p>形态判定入参统一为布尔 {@code kgMode}（等价于 Stage 5 的「是否 MIX 策略」）：
 * {@code true} → MIX 形态，{@code false} → NAIVE 形态。</p>
 *
 * @param contextTemplateName  上下文骨架模板名（Stage 4 渲染上下文用）
 * @param responseTemplateName 回答系统模板名（Stage 5 系统提示词与 Stage 4 预算空算共用）
 * @param contentVarName       回答模板内容槽变量名（MIX 为 {@code context_data}、
 *                             NAIVE 为 {@code content_data}）
 * @param defaultResponseVars  回答模板默认变量表（不含内容槽；本期即 {@code response_type}
 *                             与 {@code user_prompt} 两项，Stage 4/5 必须同源以保证渲染结果一致）
 * @author DeepDataAgent
 */
public record AnswerProfile(
        String contextTemplateName,
        String responseTemplateName,
        String contentVarName,
        Map<String, String> defaultResponseVars
) {

    /** 答案形态默认值（{@code {response_type}} 本期无外部来源，恒为该值） */
    public static final String DEFAULT_RESPONSE_TYPE = "Multiple Paragraphs";

    /** 回答模板 {@code {user_prompt}}（附加指令）缺省占位（无来源时填 n/a） */
    public static final String DEFAULT_USER_PROMPT_ABSENT = "n/a";

    /** 占位符变量名：答案形态（{@code {response_type}}） */
    public static final String VAR_RESPONSE_TYPE = "response_type";

    /** 占位符变量名：附加指令（{@code {user_prompt}}） */
    public static final String VAR_USER_PROMPT = "user_prompt";

    /** MIX 形态：图谱上下文骨架 + 知识图谱回答模板（内容槽 {@code context_data}） */
    public static final AnswerProfile KG = new AnswerProfile(
            PromptTemplates.KG_QUERY_CONTEXT,
            PromptTemplates.RAG_RESPONSE,
            "context_data",
            Map.of(VAR_RESPONSE_TYPE, DEFAULT_RESPONSE_TYPE, VAR_USER_PROMPT, DEFAULT_USER_PROMPT_ABSENT));

    /** NAIVE 形态：朴素上下文骨架 + 朴素回答模板（内容槽 {@code content_data}） */
    public static final AnswerProfile NAIVE = new AnswerProfile(
            PromptTemplates.NAIVE_QUERY_CONTEXT,
            PromptTemplates.NAIVE_RAG_RESPONSE,
            "content_data",
            Map.of(VAR_RESPONSE_TYPE, DEFAULT_RESPONSE_TYPE, VAR_USER_PROMPT, DEFAULT_USER_PROMPT_ABSENT));

    /**
     * 紧凑构造器：模板名与内容槽变量名非空快速失败，默认变量表做不可变防御拷贝。
     */
    public AnswerProfile {
        if (StringUtils.isBlank(contextTemplateName) || StringUtils.isBlank(responseTemplateName)
                || StringUtils.isBlank(contentVarName)) {
            throw new IllegalArgumentException("AnswerProfile 模板名与内容槽变量名不能为空");
        }
        defaultResponseVars = Map.copyOf(ObjectUtils.isEmpty(defaultResponseVars) ? Map.of() : defaultResponseVars);
    }

    /**
     * 按形态布尔取对应模板形态（唯一取用入口）。
     *
     * @param kgMode {@code true} 表示走图谱形态（MIX），{@code false} 表示朴素形态（NAIVE）
     * @return 对应的模板形态值对象，永不为空
     */
    public static AnswerProfile forKgMode(boolean kgMode) {
        return kgMode ? KG : NAIVE;
    }

    /**
     * 组装回答模板完整变量表：默认变量 + 本次内容槽取值。
     * <p>Stage 5 传入真实上下文正文；Stage 4 预算空算传空串（只求模板骨架的 token 占用）。</p>
     *
     * @param contextData 内容槽取值（{@code null} 归一为空串）
     * @return 可直接交给 {@code PromptCatalog.render} 的变量表
     */
    public Map<String, String> responseVars(String contextData) {
        Map<String, String> vars = new LinkedHashMap<>(defaultResponseVars);
        vars.put(contentVarName, StringUtils.defaultString(contextData));
        return vars;
    }
}
