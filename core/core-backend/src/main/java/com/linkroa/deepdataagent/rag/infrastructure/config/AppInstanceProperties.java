package com.linkroa.deepdataagent.rag.infrastructure.config;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 应用实例标识持有者（在飞任务注册表的归属维度）。
 * <p><strong>唯一来源是进程环境变量</strong> {@code APP_INSTANCE_ID}，经
 * {@link System#getenv(String)} 直接读取：<strong>MUST NOT 在任何配置文件中声明同名键</strong>，
 * 也 MUST NOT 经由 Spring 属性解析（故 {@code application.yaml}、profile 专属 yaml、
 * {@code -DAPP_INSTANCE_ID=} 系统属性、{@code @DynamicPropertySource} 均无法影响该值）。
 * 这样可从物理上杜绝「配置文件里悄悄给一个默认值或旧值」——那会让多个实例以同一标识启动而互相错杀。</p>
 *
 * <p>校验语义（fail-fast，构造期完成）：变量缺失、为空或仅含空白字符时<strong>启动即失败</strong>
 * （写入 ERROR 日志并抛出），MUST NOT 以空值或默认值继续启动——空标识会使读取范围退化为全部实例，
 * 把其他实例正在运行的任务一并置为失败；值经 {@link String#trim()} 规整后作为标识，
 * 值中残留未解析的占位文本（如 {@code ${APP_INSTANCE_ID}} 被原样传入）同样按缺失处理。应用 MUST NOT 自行生成标识，
 * 也 MUST NOT 校验其全局唯一性（重复值的风险由部署方承担）。</p>
 *
 * <p>类名沿用 {@code Properties} 历史命名，但已不再承载 {@code @ConfigurationProperties} 绑定：
 * 取值在构造期完成校验后即固定于 {@code final} 字段，天然是不可变、线程安全的单例。</p>
 *
 * @author DeepDataAgent
 */
@Component
public class AppInstanceProperties {

    /**
     * 实例标识的环境变量名。
     * <p>部署方按实例注入（示例 {@code APP_INSTANCE_ID=node-a}），是本标识的唯一来源。</p>
     */
    public static final String ENV_APP_INSTANCE_ID = "APP_INSTANCE_ID";

    private static final Logger log = LoggerFactory.getLogger(AppInstanceProperties.class);

    /** 实例唯一标识（部署者按实例注入、跨重启稳定不变；构造期校验通过后不可变）。 */
    private final String instanceId;

    /**
     * 生产构造：从进程环境变量 {@link #ENV_APP_INSTANCE_ID} 读取实例标识并立即校验。
     * <p>Spring 容器注入本类时使用此构造（存在多个构造且未标注 {@code @Autowired} 时，
     * Spring 选择无参构造）。校验失败即抛出，使应用启动失败。</p>
     *
     * @throws IllegalStateException 环境变量缺失、为空白，或值为未解析的占位文本
     */
    public AppInstanceProperties() {
        this(System.getenv(ENV_APP_INSTANCE_ID));
    }

    /**
     * 带值构造：包级可见，<strong>仅供单元测试</strong>注入可控标识。
     * <p>校验逻辑与生产路径完全一致（无参构造即委托到此），避免测试与生产出现两套判定口径；
     * 生产代码 MUST NOT 使用本构造，标识只允许来自进程环境变量。</p>
     *
     * @param rawInstanceId 待校验的原始标识值（允许含首尾空白）
     * @throws IllegalStateException 值为空白或为未解析的占位文本
     */
    AppInstanceProperties(String rawInstanceId) {
        this.instanceId = validateAndNormalize(rawInstanceId);
    }

    /**
     * 实例唯一标识（读取范围维度：决定在飞任务注册表「读哪些键、写哪些键」）。
     *
     * @return 规整后的实例标识，永不为空白
     */
    public String getInstanceId() {
        return instanceId;
    }

    /**
     * 校验并规整原始标识值（生产与单测共用同一判定）。
     *
     * @param rawInstanceId 待校验的原始标识值
     * @return 去除首尾空白后的标识
     * @throws IllegalStateException 值为空白或含未解析占位文本
     */
    private static String validateAndNormalize(String rawInstanceId) {
        String trimmed = StringUtils.isNotBlank(rawInstanceId) ? rawInstanceId.trim() : "";
        if (StringUtils.isEmpty(trimmed)) {
            log.error("未配置实例唯一标识：请先设置进程环境变量 [{}] 再启动（每个实例须取互不相同的值，"
                    + "如 node-a / node-b；缺失或空白时应用拒绝启动，不存在默认值）", ENV_APP_INSTANCE_ID);
            throw new IllegalStateException("环境变量 " + ENV_APP_INSTANCE_ID + " 未配置：实例唯一标识缺失或为空白，"
                    + "应用拒绝启动（该标识仅从进程环境变量读取，配置文件中不存在该键）");
        }
        if (trimmed.contains("${")) {
            log.error("实例唯一标识为未解析的占位文本：env=[{}]，value=[{}]；请传入已替换的真实标识",
                    ENV_APP_INSTANCE_ID, trimmed);
            throw new IllegalStateException("环境变量 " + ENV_APP_INSTANCE_ID + " 的值是未解析的占位文本："
                    + trimmed + "，请传入真实的实例唯一标识");
        }
        return trimmed;
    }
}