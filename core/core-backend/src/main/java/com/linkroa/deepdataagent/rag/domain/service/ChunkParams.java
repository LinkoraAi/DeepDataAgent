package com.linkroa.deepdataagent.rag.domain.service;

import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * 分块参数值对象（由 knowledge_base.rag_engine_config.chunkStrategy.modeConfig.params 反序列化）。
 * <p><b>消费契约</b>：用户可配置项只有本记录的三项，且仅 GENERAL 消费——
 * {@code chunkTokenNum}（软目标 token，合法域 (0, 2048] 整数）、
 * {@code delimiter}（分隔符字段，非空白）、{@code overlappedPercent}（重叠比例，合法域 [0, 30] 整数）。
 * 其余六法（QA/BOOK/LAWS/TABLE/PRESENTATION/ONE）的预算与分隔符为代码常量，不消费本值对象。</p>
 * <p><b>不改写合法输入</b>：合法值原样生效；构造期的兜底（回默认值 / clamp 到边界）只是
 * 「保存侧入口拒绝后理论不可达」的防御路径，命中即输出 WARN，绝不静默改写。</p>
 * <p><b>分隔符两层默认</b>：下发层默认 {@link #DEFAULT_DISPATCH_DELIMITER}（配置面展示口径），
 * 代码兜底 {@link #FALLBACK_DELIMITER}（仅当字段空白时生效）。</p>
 *
 * @param chunkTokenNum     分块软目标 token 数（合法域 (0, 2048]，非法回 {@link #DEFAULT_CHUNK_TOKEN_NUM}）
 * @param delimiter         分隔符字段（反引号包裹为自定义多字符分隔符；空白落 {@link #FALLBACK_DELIMITER}）
 * @param overlappedPercent 重叠比例百分比（合法域 [0, 30]，非法 clamp 到边界）
 */
public record ChunkParams(
        int chunkTokenNum,
        String delimiter,
        int overlappedPercent
) {

    private static final Logger log = LoggerFactory.getLogger(ChunkParams.class);

    /** 分块默认 token 预算（软目标，非法值兜底口径）。 */
    public static final int DEFAULT_CHUNK_TOKEN_NUM = 512;

    /** token 预算合法域下界（不含，0 视为非法）。 */
    public static final int CHUNK_TOKEN_NUM_EXCLUSIVE_MIN = 0;

    /** token 预算合法域上界（含）。 */
    public static final int CHUNK_TOKEN_NUM_MAX = 2048;

    /** 重叠比例合法区间下限。 */
    public static final int OVERLAP_MIN = 0;

    /** 重叠比例合法区间上限。 */
    public static final int OVERLAP_MAX = 30;

    /**
     * 分隔符下发层默认值（配置面与前端展示口径，对齐外部实现的后端默认）。
     * <p>段落是天然语义单位，故下发口径默认按换行切段。</p>
     */
    public static final String DEFAULT_DISPATCH_DELIMITER = "\n";

    /**
     * 分隔符代码兜底值（仅当 {@code delimiter} 字段空白时生效）。
     * <p>句界集合逐字保留外部实现兜底串，刻意不含英文句号加空格，避免切 {@code v1.2 / Fig. 3}。</p>
     */
    public static final String FALLBACK_DELIMITER = "\n!?。；！？";

    /**
     * BOOK/LAWS 文档级层级合并投票失败时，降级 naive 合并使用的固定 token 预算（软目标）。
     * <p>不进配置面：降级出口刻意不吃 {@code chunkTokenNum}，保证降级结果与用户参数解耦
     * （BOOK/LAWS 文档级收敛与降级出口）。</p>
     */
    public static final int HIERARCHY_FALLBACK_TOKEN_NUM = 256;

    /**
     * BOOK/LAWS 文档级降级合并使用的固定分隔符字段（中文句界集合，不进配置面）。
     * <p>与 {@link #HIERARCHY_FALLBACK_TOKEN_NUM} 配套，保证降级路径输出确定性。</p>
     */
    public static final String HIERARCHY_FALLBACK_DELIMITER = "\n。；！？";

    /** JSONB 参数键：分块软目标 token 数。 */
    private static final String KEY_CHUNK_TOKEN_NUM = "chunk_token_num";

    /** JSONB 参数键：分隔符字段。 */
    private static final String KEY_DELIMITER = "delimiter";

    /** JSONB 参数键：重叠比例（标准写法）。 */
    private static final String KEY_OVERLAPPED_PERCENT = "overlapped_percent";

    /** JSONB 参数键：重叠比例（简写兼容）。 */
    private static final String KEY_OVERLAP = "overlap";

    /**
     * 紧凑构造器：<b>不改写合法输入</b>，仅对非法值做防御兜底并输出 WARN。
     * <p>token 非 (0, 2048] → 回 {@link #DEFAULT_CHUNK_TOKEN_NUM}；重叠比例非 [0, 30] → clamp 到边界；
     * 分隔符为 null / 空串 → 落 {@link #FALLBACK_DELIMITER}。三条兜底均为「入口拒绝后理论不可达」路径。</p>
     * <p>注意：空白字符（换行、空格、制表符）都是<b>合法分隔符字符</b>——下发层默认
     * {@link #DEFAULT_DISPATCH_DELIMITER}（即 {@code "\n"}）必须原样生效，故此处只判「空」不判「空白」。</p>
     */
    public ChunkParams {
        if (chunkTokenNum <= CHUNK_TOKEN_NUM_EXCLUSIVE_MIN || chunkTokenNum > CHUNK_TOKEN_NUM_MAX) {
            log.warn("分块 token 预算 {} 越界 (0, {}]，回默认值 {}（该值应由保存侧入口拒绝）",
                    chunkTokenNum, CHUNK_TOKEN_NUM_MAX, DEFAULT_CHUNK_TOKEN_NUM);
            chunkTokenNum = DEFAULT_CHUNK_TOKEN_NUM;
        }
        if (overlappedPercent < OVERLAP_MIN || overlappedPercent > OVERLAP_MAX) {
            int clamped = Math.max(OVERLAP_MIN, Math.min(OVERLAP_MAX, overlappedPercent));
            log.warn("重叠比例 {} 越界 [{}, {}]，clamp 到 {}", overlappedPercent,
                    OVERLAP_MIN, OVERLAP_MAX, clamped);
            overlappedPercent = clamped;
        }
        if (StringUtils.isEmpty(delimiter)) {
            log.warn("分隔符字段为空，落代码兜底分隔符 {}", FALLBACK_DELIMITER);
            delimiter = FALLBACK_DELIMITER;
        }
    }

    /**
     * 默认分块参数（软目标 512、无重叠、下发层默认分隔符 {@code "\n"}）。
     * <p>delimiter 取下发层默认 {@link #DEFAULT_DISPATCH_DELIMITER}：参数未配置时代码默认即
     * 按换行切段、再按预算贪心合并（配置继承语义③「未填项取代码默认值」的落点）；
     * {@link #FALLBACK_DELIMITER} 仅对「显式配置了空串」的脏数据兜底生效。</p>
     *
     * @return 默认参数实例
     */
    public static ChunkParams defaults() {
        return new ChunkParams(DEFAULT_CHUNK_TOKEN_NUM, DEFAULT_DISPATCH_DELIMITER, OVERLAP_MIN);
    }

    /**
     * 从 JSONB 参数映射构建分块参数（键名对齐三项字段清单）。
     * <p>只读 {@code chunk_token_num} / {@code delimiter} /
     * {@code overlapped_percent}（兼容 {@code overlap}）三个键，未知键（含存量废弃键
     * {@code enable_children} 等）一律忽略；缺失键取默认值。</p>
     *
     * @param params 模式参数映射，可为空
     * @return 分块参数实例
     */
    public static ChunkParams fromMap(Map<String, Object> params) {
        if (MapUtils.isEmpty(params)) {
            return defaults();
        }
        int tokenNum = toInt(params.get(KEY_CHUNK_TOKEN_NUM), DEFAULT_CHUNK_TOKEN_NUM);
        // 键缺失 → 下发层默认；键存在但为空串 → 构造器落代码兜底并 WARN（两层默认各有触发点）
        String delimiter = toStringValue(params.get(KEY_DELIMITER), DEFAULT_DISPATCH_DELIMITER);
        int overlap = toInt(params.get(KEY_OVERLAPPED_PERCENT), params.get(KEY_OVERLAP), OVERLAP_MIN);
        return new ChunkParams(tokenNum, delimiter, overlap);
    }

    /**
     * 取两个候选键中第一个生效的整数值（兼容 {@code overlapped_percent} / {@code overlap} 两种写法）。
     *
     * @param primary      主键值，可为 null
     * @param fallback     备键值，可为 null
     * @param defaultValue 两键均缺失时的默认值
     * @return 整数值
     */
    private static int toInt(Object primary, Object fallback, int defaultValue) {
        if (ObjectUtils.isNotEmpty(primary)) {
            return toInt(primary, defaultValue);
        }
        return toInt(fallback, defaultValue);
    }

    /**
     * 数值参数解析：数字转 int，非法值回退默认。
     *
     * @param value        原始值，可为 null
     * @param defaultValue 默认值
     * @return 解析后的整数
     */
    private static int toInt(Object value, int defaultValue) {
        return toNumber(value, defaultValue);
    }

    /**
     * 数值统一解析：JsonNode(IntNode/DecimalNode)/Integer/Long/Double/Float 均兼容。
     * <p>参数契约要求整数：非整数值（如 {@code 512.5}）视为非法，WARN 后回退默认，不做四舍五入改写。</p>
     *
     * @param value        原始值，可为 null
     * @param defaultValue 默认值
     * @return 解析后的整数；值为 null、非数字或非整数时返回默认值
     */
    private static int toNumber(Object value, int defaultValue) {
        if (ObjectUtils.isEmpty(value)) {
            return defaultValue;
        }
        if (value instanceof Integer || value instanceof Long || value instanceof Short
                || value instanceof Byte) {
            return ((Number) value).intValue();
        }
        if (value instanceof Double || value instanceof Float) {
            double numeric = ((Number) value).doubleValue();
            if (Double.compare(numeric, Math.floor(numeric)) != 0) {
                log.warn("分块参数 {} 非整数，按配置契约回默认值 {}", value, defaultValue);
                return defaultValue;
            }
            return (int) numeric;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        String text = String.valueOf(value).trim();
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            log.warn("分块参数 {} 无法解析为整数，回默认值 {}", value, defaultValue);
            return defaultValue;
        }
    }

    /**
     * 字符串参数解析：原样透传（不 trim、不判空白——换行 / 空格 / 制表符均为合法分隔符字符），
     * 仅值为 null 时回退默认。
     *
     * @param value        原始值，可为 null
     * @param defaultValue 默认值
     * @return 解析后的字符串
     */
    private static String toStringValue(Object value, String defaultValue) {
        if (ObjectUtils.isEmpty(value)) {
            return defaultValue;
        }
        return String.valueOf(value);
    }
}
