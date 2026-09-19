package com.linkroa.deepdataagent.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MyBatis-Plus 列引用形态门禁（强制红灯）。
 *
 * <p>规约：{@code LambdaQueryWrapper} / {@code LambdaUpdateWrapper} 的列引用一律<b>方法引用</b>
 * （{@code Entity::getUserId}），MUST NOT 传 lambda 表达式（{@code e -> e.getUserId()}）——
 * 查询条件（{@code eq / in / gt ...}）与保存更新 SET（{@code set}）两条路径同罪。</p>
 *
 * <p>成因：MyBatis-Plus 不调用 {@code SFunction} 本身，只解析序列化 lambda 的方法名——
 * 方法引用给出 {@code getUserId}（经 {@code PropertyNamer} 剥离 get 得属性名，再转列名），
 * 而 lambda 表达式编译为合成方法 {@code lambda$N}，运行期必抛
 * {@code Error parsing property name 'lambda$N'. Didn't start with 'is', 'get' or 'set'.}
 * （列名解析失败发生在 SQL 执行期，单测不带真实库时极易漏网）。</p>
 *
 * <p><b>为什么需要本门禁</b>：AGENTS.md 的散文约定 + 各 Mapper javadoc 不产生红灯；mock 仓储的
 * 单测不走列解析，只有强制渲染 {@code getSqlSegment()} 的用例才暴露该问题——全仓 25 个实体中
 * 仅少数有此类用例。故此处以源码扫描补一条全量强制断言。</p>
 *
 * <p><b>豁免（不得误伤）</b>：{@code and / or / nested / not} 的入参是 {@code Consumer<Param>}
 * （嵌套条件块，如 {@code wrapper.and(w -> w.eq(Entity::getX, v))}），其 lambda 必须保留，
 * 故不在扫描方法清单内；stream 的 {@code map / filter / forEach} 等非本清单方法同样不在范围内。</p>
 */
class MybatisPlusColumnReferenceTest {

    /** 后端主源码根（surefire 工作目录为模块 basedir）。 */
    private static final Path SOURCE_ROOT = Path.of("src/main/java/com/linkroa/deepdataagent");

    /**
     * 吃 {@code SFunction} 列引用的包装器方法名（查询条件 / SET / 投影 / 排序 / 分组）。
     * <p>{@code and / or / nested / not} 与 {@code func / allEq} 不在此列——前者入参为
     * {@code Consumer<Param>}，后者入参为 {@code BiPredicate}（被真实调用，不解析方法名）。</p>
     */
    private static final String SFUNCTION_METHODS =
            "eq|ne|gt|ge|lt|le|like|notLike|likeLeft|likeRight|in|notIn|isNull|isNotNull"
                    + "|between|notBetween|select|set|orderBy|orderByAsc|orderByDesc|groupBy|having";

    /**
     * lambda 表达式落在列引用位的形态。
     * <p>三段：方法名 → 可选条件重载前缀（{@code condition, 列引用, 值}，条件允许含括号调用）→
     * lambda 形参（裸名 / {@code (Entity e)} / 带类型转换）。尾随 {@code ->} 为必需，
     * 故方法引用（{@code ::}）与普通实参不会命中。</p>
     */
    private static final Pattern LAMBDA_COLUMN_ARGUMENT = Pattern.compile(
            "\\.(?:" + SFUNCTION_METHODS + ")\\s*\\(\\s*"
                    + "(?:[^,;]{1,120}\\s*,\\s*)?"
                    + "(?:[A-Za-z_$][\\w$]*|\\([^()]{0,60}\\))"
                    + "\\s*->");

    /** 块注释（剥离时保留其中的换行，保证行号不漂移）。 */
    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);

    @Test
    @DisplayName("强制红灯：Mapper / 仓储层的 MyBatis-Plus 列引用零 lambda 表达式入参")
    void should_failBuildOnAnyViolation_when_scanWrapperColumnArguments_given_currentCodebase()
            throws IOException {
        // given：源码目录缺失视为门禁失效（与 ArchUnit 导入为空同判），而不是静默通过
        if (!Files.isDirectory(SOURCE_ROOT)) {
            throw new IllegalStateException("未找到后端主源码目录，MyBatis-Plus 列引用门禁处于失效状态: "
                    + SOURCE_ROOT.toAbsolutePath());
        }

        // when：逐文件剥离注释后检索「列引用位传 lambda 表达式」（注释中的反例说明不计入违规）
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(SOURCE_ROOT)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                collectOffenders(file, offenders);
            }
        }

        // then：零命中——命中即说明该处列名会被解析为合成方法 lambda$N，运行期必抛
        assertTrue(offenders.isEmpty(),
                "MyBatis-Plus 列引用必须使用方法引用（Entity::getX），MUST NOT 传 lambda 表达式"
                        + "（lambda$N 无法解析为列名，运行期必抛 Error parsing property name）: " + offenders);
    }

    @Test
    @DisplayName("门禁有效性：列引用位的 lambda 各形态（条件重载 / 跨行 / 显式形参 / 排序投影）均被检出")
    void should_detectEachViolationShape_when_matchesColumnLambda_given_lambdaFormsInColumnPosition() {
        // given：错误写法及其等价变体（查询 / 保存 SET / 条件重载 / 跨行 / 显式形参 / 排序投影）
        List<String> wrongForms = List.of(
                "wrapper.eq(it -> it.getUsername(), username);",
                "wrapper.set(true, e -> e.getStatus(), \"idle\");",
                "wrapper.eq(entities.isEmpty(),\n        e -> e.getId());",
                "wrapper.eq((FileEntity e) -> e.getId(), fileId);",
                "wrapper.select(e -> e.getSeq()).orderByDesc(e -> e.getCreatedAt());",
                "wrapper.in(item -> item.getType(), types);");

        // when & then：逐形态命中（门禁漏检等同放行违规，故此处必须全部命中）
        wrongForms.forEach(form -> assertTrue(matchesColumnLambda(form), form));
    }

    @Test
    @DisplayName("门禁无误报：方法引用、嵌套条件块 Consumer lambda、stream lambda 均不判定违规")
    void should_detectNothing_when_matchesColumnLambda_given_methodReferenceAndConsumerLambda() {
        // given：合规写法（方法引用 / 嵌套条件块 / stream 管道 / 原生 SQL 片段）
        List<String> allowedForms = List.of(
                "wrapper.eq(FileEntity::getCreatedAt, cursorCreatedAt);",
                "wrapper.and(w -> w.gt(FileEntity::getCreatedAt, cursorCreatedAt)\n"
                        + "        .or(o -> o.eq(FileEntity::getId, cursorId)));",
                "wrapper.orderByAsc(AgentSessionEntity::getCreatedAt).orderByAsc(AgentSessionEntity::getId);",
                "repository.findPage(query).stream().map(ChatEvent::seq)"
                        + ".filter(item -> item.id().equals(id)).toList();",
                "wrapper.apply(\"scope->>'id' = {0}\", scopeId);");

        // when & then：零误报（误报会逼迫把嵌套 Consumer 改坏，破坏编译）
        allowedForms.forEach(form -> assertFalse(matchesColumnLambda(form), form));
    }

    /**
     * 单文件违规收集：命中处输出相对路径 + 行号 + 行内容，便于直接定位。
     */
    private static void collectOffenders(Path file, List<String> offenders) throws IOException {
        String code = stripComments(Files.readString(file, StandardCharsets.UTF_8));
        Matcher matcher = LAMBDA_COLUMN_ARGUMENT.matcher(code);
        while (matcher.find()) {
            offenders.add(SOURCE_ROOT.relativize(file) + ":" + lineNumberAt(code, matcher.start())
                    + " → " + lineAt(code, matcher.start()).strip());
        }
    }

    private static boolean matchesColumnLambda(String snippet) {
        return LAMBDA_COLUMN_ARGUMENT.matcher(snippet).find();
    }

    private static int lineNumberAt(String code, int offset) {
        return (int) code.substring(0, offset).chars().filter(ch -> ch == '\n').count() + 1;
    }

    private static String lineAt(String code, int offset) {
        int start = code.lastIndexOf('\n', offset) + 1;
        int end = code.indexOf('\n', offset);
        return code.substring(start, end < 0 ? code.length() : end);
    }

    /**
     * 剥离块注释与行注释（门禁只看代码面；注释中的反例说明不计入违规）。
     * <p>块注释内的换行数原样保留——剥离后行号须与源文件一致，违规定位才有意义。
     * 行注释仅剥离「行首或空白起始」的 {@code //}，避免把字符串字面量里的协议分隔符
     * （{@code "https://..."}）误当注释起点。</p>
     */
    private static String stripComments(String source) {
        StringBuilder stripped = new StringBuilder(source.length());
        Matcher blockComment = BLOCK_COMMENT.matcher(source);
        while (blockComment.find()) {
            int newlines = (int) blockComment.group().chars().filter(ch -> ch == '\n').count();
            blockComment.appendReplacement(stripped, Matcher.quoteReplacement("\n".repeat(newlines)));
        }
        blockComment.appendTail(stripped);
        return stripped.toString().replaceAll("(?m)(^|\\s)//.*$", "$1");
    }
}