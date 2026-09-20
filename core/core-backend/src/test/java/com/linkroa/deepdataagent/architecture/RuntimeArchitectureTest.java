package com.linkroa.deepdataagent.architecture;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
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

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DDD 分层架构门禁（fix-runtime-layering R6，信息模式）。
 *
 * <p>四类依赖规则（design D5）：
 * <ol>
 *     <li>{@code noControllerDependsOnInfraOrDomain}：接口层不得依赖基础设施 / 领域层
 *     （controller.convert 引用领域模型为 AGENTS 模板既有形态，现状计入基线）；</li>
 *     <li>{@code applicationNoInfraOrController}：应用层不得触碰基础设施与协议层；
 *     {@code runtime.application.contract}（SseEventEnvelope 信封）按设计显式白名单豁免出口检查，
 *     例外已在 AGENTS.md 登记；其中 runtime BC 作用域已由 straighten-event-factory-and-inbound
 *     清零反向 import 并转强制（见 {@code runtimeApplicationNoInfraOrControllerEnforced}），
 *     其余 BC 现状仍计入本条信息模式基线；</li>
 *     <li>{@code domainNoApplicationOrInfra}：领域层保持零技术依赖方向；</li>
 *     <li>{@code runtimeInfraClientNoOtherBcApplicationPackages}：runtime 基础设施 client 包
 *     不得直接 import 他 BC 的 application.port / application.dto——他 BC 机密材料端口与
 *     重载荷 DTO 只允许在本 BC infrastructure.assembly 适配单点消费（R4 收敛）。</li>
 * </ol>
 *
 * <p><b>信息模式</b>：平台级四条规则全量执行并打印违规清单与基线计数，但失败不红灯（不抛给构建），
 * 与「{@code @Disabled} 占位」裁定相反（整类不执行＝零信号、易长期遗忘）。
 *
 * <p><b>强制模式</b>（straighten-event-factory-and-inbound 收尾，design D5）：
 * runtime BC 作用域的「application ↛ infrastructure / controller」已转真断言红灯——
 * 该作用域反向依赖（AgentRuntimeCommandConvert 引用 controller.request）已随入站装配上移清零，
 * 满足 fix-runtime-layering 预设的转强制前置条件；其余 BC 与其余三类规则仍留在信息模式基线。
 *
 * <p><b>迭代裁决例外登记（2026-09-13，fix-runtime-layering design D3 偏差③追加）</b>：
 * {@code @ConfigurationProperties} 配置载体（{@code ..infrastructure.config..}）与 Spring 原生
 * 事件设施属<b>工具性</b>依赖，经用户裁决允许应用层直注，不再为其增设端口包装（原配置端口与
 * 领域事件端口已删除）。故规则二与 runtime 强制规则的<b>目标类谓词统一豁免 {@code ..infrastructure.config..}</b>
 * （见 {@link #applicationForbiddenTargets()}）；出站端口仅当存在真实技术机制缝或跨 BC 消费单点时引入。
 *
 * <p><b>凭据出网门禁（align-qoder-vault-credential-capabilities tasks 2.4）</b>：ArchUnit 读字节码、
 * 看不见注释，无法表达「源码代码面不得再出现保管库凭证环境变量标识」；故另立源码扫描强制断言
 * （{@link #should_failBuildOnForbiddenIdentifier_when_scanRuntimeInfraClientSources_given_currentCodebase}），
 * 剥离注释后检索 {@code VAULT_MCP_CREDENTIALS} 零命中——注释中的历史沿革说明不计入违规，
 * 而任何把凭据重新接回环境变量通道的代码都会红灯。
 */
class RuntimeArchitectureTest {

    /** 平台根包前缀（不含尾点）。 */
    private static final String ROOT = "com.linkroa.deepdataagent";

    /** 除 runtime 外的全部业务限界上下文（shared 为公共能力，不参与跨 BC 禁令目标）。 */
    private static final String[] OTHER_BC_APPLICATION_PACKAGES = Stream.of(
                    ".agent", ".auth", ".datasource", ".file", ".memory", ".skill", ".vault")
            .flatMap(bc -> Stream.of(
                    ROOT + bc + ".application.port..",
                    ROOT + bc + ".application.dto.."))
            .toArray(String[]::new);

    /** 直接消费 shared 对象存储技术能力的业务 BC：仅允许其基础设施装配点 import。 */
    private static final String[] STORAGE_CONSUMING_BC_UPPER_LAYERS = Stream.of(".file", ".skill")
            .flatMap(bc -> Stream.of(ROOT + bc + ".domain..", ROOT + bc + ".application.."))
            .toArray(String[]::new);

    /** ArchUnit 违规消息中的计数头（"was violated (N times)"），用于提取基线违规数。 */
    private static final Pattern VIOLATION_COUNT = Pattern.compile("was violated \\((\\d+) times?\\)");

    /** 已废除的保管库凭证环境变量标识（凭据唯一出口为 MCP 请求头，不得再经环境变量通道）。 */
    private static final String FORBIDDEN_VAULT_ENV_IDENTIFIER = "VAULT_MCP_CREDENTIALS";

    /** runtime 基础设施 client 包源码目录（surefire 工作目录为模块 basedir）。 */
    private static final Path RUNTIME_INFRA_CLIENT_SOURCES =
            Path.of("src/main/java/com/linkroa/deepdataagent/runtime/infrastructure/client");

    /** 生产类导入：排除测试镜像与 test-classes 位置（双保险），保证门禁只看主干代码。 */
    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .withImportOption(location -> !location.toString().replace('\\', '/').contains("/test-classes/"))
            .importPackages(ROOT);

    @Test
    @DisplayName("四类分层依赖规则信息模式执行：记录违规基线清单，不阻断构建")
    void should_recordViolationBaselineWithoutFailing_when_evaluateLayeringRules_given_currentCodebase() {
        // given：导入结果为空说明门禁本身失效，属于必须红灯的构建环境问题（与分层违规红灯语义不同）
        if (CLASSES.isEmpty()) {
            throw new IllegalStateException("ArchUnit 未导入任何生产类，分层门禁处于失效状态");
        }

        // when：四条规则逐条以信息模式执行
        int total = 0;
        total += evaluateInformationally("noControllerDependsOnInfraOrDomain", noControllerDependsOnInfraOrDomain());
        total += evaluateInformationally("applicationNoInfraOrController", applicationNoInfraOrController());
        total += evaluateInformationally("domainNoApplicationOrInfra", domainNoApplicationOrInfra());
        total += evaluateInformationally("runtimeInfraClientNoOtherBcApplicationPackages",
                runtimeInfraClientNoOtherBcApplicationPackages());

        // then：基线汇总（供 PR 描述引用；runtime 作用域 application↛controller/infrastructure 已另立强制断言）
        System.out.println("[ARCH-BASELINE] SUMMARY 规则 4 条，基线违规依赖合计 " + total + " 处（信息模式，不红灯）");
    }

    @Test
    @DisplayName("强制红灯（straighten 收尾）：runtime 应用层对基础设施 / 协议层零依赖")
    void should_failBuildOnAnyViolation_when_enforceRuntimeApplicationLayering_given_currentCodebase() {
        // given：导入结果为空说明门禁本身失效，属于必须红灯的构建环境问题
        if (CLASSES.isEmpty()) {
            throw new IllegalStateException("ArchUnit 未导入任何生产类，分层门禁处于失效状态");
        }

        // when & then：真断言，任何 runtime.application → infrastructure/controller 依赖直接红灯
        runtimeApplicationNoInfraOrControllerEnforced().check(CLASSES);
    }

    @Test
    @DisplayName("强制红灯：file / skill 的领域层与应用层不得直接依赖 shared.storage 技术类型")
    void should_failBuildOnAnyViolation_when_enforceStorageIsolation_given_currentCodebase() {
        // given：导入结果为空说明门禁本身失效，属于必须红灯的构建环境问题
        if (CLASSES.isEmpty()) {
            throw new IllegalStateException("ArchUnit 未导入任何生产类，分层门禁处于失效状态");
        }

        // when & then：对象存储技术类型只能出现在本 BC infrastructure 装配点 + shared 自身，
        // domain / application（含自有业务端口接口）零依赖，违规即红灯
        businessUpperLayersDoNotDependOnSharedStorage().check(CLASSES);
    }

    @Test
    @DisplayName("强制红灯：runtime 基础设施 client 代码面零保管库凭证环境变量标识（凭据唯一出口为 MCP 请求头）")
    void should_failBuildOnForbiddenIdentifier_when_scanRuntimeInfraClientSources_given_currentCodebase()
            throws IOException {
        // given：源码目录缺失视为门禁失效（与 ArchUnit 导入为空同判），而不是静默通过
        if (!Files.isDirectory(RUNTIME_INFRA_CLIENT_SOURCES)) {
            throw new IllegalStateException("未找到 runtime 基础设施 client 源码目录，凭据出网门禁处于失效状态: "
                    + RUNTIME_INFRA_CLIENT_SOURCES.toAbsolutePath());
        }

        // when：逐文件剥离注释后检索历史标识（注释里的「原形态已废除」沿革说明不计入违规）
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.list(RUNTIME_INFRA_CLIENT_SOURCES)) {
            for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".java")).toList()) {
                String code = stripComments(Files.readString(file, StandardCharsets.UTF_8));
                if (code.contains(FORBIDDEN_VAULT_ENV_IDENTIFIER)) {
                    offenders.add(file.getFileName().toString());
                }
            }
        }

        // then：零命中——命中即说明保管库凭证明文又经环境变量通道进入 MCP 客户端（唯一出口应为其请求头）
        assertTrue(offenders.isEmpty(),
                "runtime.infrastructure.client 代码面出现保管库凭证环境变量标识 "
                        + FORBIDDEN_VAULT_ENV_IDENTIFIER + ": " + offenders
                        + "（凭据唯一出口为 MCP 请求头，见 design D2）");
    }

    @Test
    @DisplayName("门禁有效性：源码剥离注释后仍保留 URL 字面量（防止 `://` 被当行注释吞掉而漏检）")
    void should_keepUrlLiteral_when_stripComments_given_lineWithProtocolSeparator() {
        // given（`https://` 与行尾注释共存：协议分隔符不得被当作注释起点）
        String source = "String url = \"https://example.com/mcp\"; // 端点\nString x = 1;";

        // when
        String stripped = stripComments(source);

        // then（URL 字面量完好、行注释已剥离）
        assertTrue(stripped.contains("https://example.com/mcp"));
        assertTrue(stripped.contains("String x = 1;"));
        assertTrue(!stripped.contains("// 端点"));
    }

    /**
     * 剥离块注释与行注释（门禁只看代码面；注释中的历史沿革说明不计入违规）。
     * <p>行注释仅剥离「行首或空白起始」的 {@code //}，避免把字符串字面量里的协议分隔符
     * （{@code "https://..."}）误当注释起点而截断本可被检出的违规定义。</p>
     */
    private static String stripComments(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", "")
                .replaceAll("(?m)(^|\\s)//.*$", "$1");
    }

    /**
     * 强制规则（demote-storage-to-shared-object-store）：file / skill 的领域层与应用层
     * MUST NOT 直接 import {@code shared.storage} 技术类型。业务 BC 只能在各自
     * infrastructure 装配点经业务语义端口消费对象存储，技术 key 规划 / 异常翻译不得渗入上层。
     */
    private static ArchRule businessUpperLayersDoNotDependOnSharedStorage() {
        return noClasses().that().resideInAnyPackage(STORAGE_CONSUMING_BC_UPPER_LAYERS)
                .should().dependOnClassesThat().resideInAnyPackage(ROOT + ".shared.storage..")
                .because("对象存储属 shared 技术设施，file/skill 的 domain/application 只能经本 BC application.port "
                        + "业务语义端口间接使用，技术类型（ObjectStorage / key / 异常）一律由 infrastructure 装配点翻译");
    }

    /**
     * 规则一：接口层不得依赖基础设施 / 领域层。
     */
    private static ArchRule noControllerDependsOnInfraOrDomain() {
        return noClasses().that().resideInAnyPackage(ROOT + "..controller..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(ROOT + "..infrastructure..", ROOT + "..domain..")
                .because("接口层一律经应用服务 + Convert 访问数据，不直接触碰基础设施（controller.convert 引用领域模型现状计入基线）");
    }

    /**
     * 规则二：应用层不得依赖基础设施 / 协议层；application.contract（SseEventEnvelope 信封）显式白名单豁免。
     */
    private static ArchRule applicationNoInfraOrController() {
        return noClasses().that().resideInAnyPackage(ROOT + "..application..")
                // 白名单：信封被 application.convert 与 infrastructure.sse 共同消费，保留在 application.contract
                // 为 AGENTS.md 已登记例外；契约类自身当前零跨层 import，此处为设计 D5/风险表明文要求的防御性豁免，
                // 防止未来规则误判该包的合法例外形态。
                .and().resideOutsideOfPackage(ROOT + ".runtime.application.contract..")
                .should().dependOnClassesThat(applicationForbiddenTargets())
                .because("应用层不得触碰基础设施与协议层（infrastructure.config 配置载体除外），保证用例编排可脱离技术细节单测与未来 Feign 化");
    }

    /**
     * 强制规则（straighten-event-factory-and-inbound 收尾）：runtime 应用层不得依赖基础设施 / 协议层。
     * <p>与平台级规则二同源（含 {@code runtime.application.contract} 信封白名单与
     * {@code infrastructure.config} 配置载体豁免，见 {@link #applicationForbiddenTargets()}），
     * 但作用域收敛到 runtime BC——本变更已将该作用域的反向依赖清零，满足 fix-runtime-layering
     * 预设的转强制前置条件，故以真断言红灯固化，防止 application → controller.request /
     * infrastructure（config 除外）依赖回潮。</p>
     */
    private static ArchRule runtimeApplicationNoInfraOrControllerEnforced() {
        return noClasses().that().resideInAnyPackage(ROOT + ".runtime.application..")
                .and().resideOutsideOfPackage(ROOT + ".runtime.application.contract..")
                .should().dependOnClassesThat(applicationForbiddenTargets())
                .because("runtime 应用层对基础设施 / 协议层依赖已清零（straighten-event-factory-and-inbound），违规即红灯");
    }

    /**
     * 应用层禁止依赖的目标类谓词：基础设施与协议层，<b>豁免 {@code ..infrastructure.config..}</b>。
     * <p>迭代裁决（2026-09-13）：{@code @ConfigurationProperties} 配置载体属工具性 Spring 设施，
     * 允许应用层直注（同 {@code org.apache.commons.lang3} 一类工具依赖），MUST NOT 为其增设端口包装；
     * 例外口径已登记于 AGENTS.md「依赖规则」与本类 javadoc。</p>
     */
    private static DescribedPredicate<JavaClass> applicationForbiddenTargets() {
        return resideInAnyPackage(ROOT + "..infrastructure..", ROOT + "..controller..")
                .and(not(resideInAnyPackage(ROOT + "..infrastructure.config..")));
    }

    /**
     * 规则三：领域层不得依赖应用 / 基础设施层。
     */
    private static ArchRule domainNoApplicationOrInfra() {
        return noClasses().that().resideInAnyPackage(ROOT + "..domain..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(ROOT + "..application..", ROOT + "..infrastructure..")
                .because("领域层只承载业务规则与不变量，依赖方向严格向内");
    }

    /**
     * 规则四：runtime 基础设施 client 包不得直接消费他 BC 的 application.port / application.dto（R4 收敛单点）。
     */
    private static ArchRule runtimeInfraClientNoOtherBcApplicationPackages() {
        return noClasses().that().resideInAnyPackage(ROOT + ".runtime.infrastructure.client..")
                .should().dependOnClassesThat().resideInAnyPackage(OTHER_BC_APPLICATION_PACKAGES)
                .because("他 BC 机密材料端口 / 重载荷 DTO 只允许在本 BC infrastructure.assembly 适配单点消费");
    }

    /**
     * 信息模式执行单条规则：违规只打印清单与计数，不向构建抛出失败。
     *
     * @return 该规则的违规数（计数头解析失败时按清单行数兜底为未知并打印全文）
     */
    private static int evaluateInformationally(String ruleName, ArchRule rule) {
        try {
            rule.check(CLASSES);
            System.out.println("[ARCH-BASELINE] " + ruleName + " -> 违规 0 处");
            return 0;
        } catch (AssertionError failure) {
            String message = failure.getMessage() == null ? "" : failure.getMessage();
            Matcher matcher = VIOLATION_COUNT.matcher(message);
            int count = matcher.find() ? Integer.parseInt(matcher.group(1)) : -1;
            System.out.println("[ARCH-BASELINE] " + ruleName + " -> 违规 "
                    + (count >= 0 ? count + " 处" : "计数解析失败，清单如下") + "（信息模式，不红灯）");
            message.lines()
                    .filter(line -> !line.isBlank())
                    .forEach(line -> System.out.println("[ARCH-BASELINE]   " + line.strip()));
            return Math.max(count, 0);
        }
    }
}
