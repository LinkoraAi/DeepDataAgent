package com.linkroa.deepdataagent.rag.infrastructure.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link AppInstanceProperties} 应用实例标识持有者单测。
 * <p>覆盖构造期 fail-fast 校验：标识缺失（null）、为空串、仅含空白字符、占位文本未解析四种情形均
 * 启动即失败；正常标识与含首尾空白的标识均经规整后可读——MUST NOT 以空值或默认值继续启动
 * （空标识会使读取范围退化为全部实例，把其他实例正在运行的任务一并置为失败）。</p>
 *
 * <p>标识的唯一来源是进程环境变量，而 Java 无法在用例内可靠改写进程环境，故统一经包级可见的
 * 「带值构造」注入待校验值——其校验逻辑与生产无参构造完全一致（生产构造即委托到同一实现），
 * 不存在测试与生产两套判定口径。</p>
 *
 * <p>本组件为不可变、无依赖的载体，按规范无需 Mock，逐用例独立实例保证可重复性。</p>
 *
 * @author DeepDataAgent
 */
class AppInstancePropertiesTest {

    @Test
    void should_throw_when_construct_given_missingInstanceId() {
        // given：环境变量缺失（生产无参构造读到的即为 null）
        // when // then：构造期即 fail-fast，MUST NOT 以空值或默认值继续启动
        assertThrows(IllegalStateException.class, () -> new AppInstanceProperties(null));
    }

    @Test
    void should_throw_when_construct_given_emptyInstanceId() {
        // given：标识为空串
        // when // then
        assertThrows(IllegalStateException.class, () -> new AppInstanceProperties(""));
    }

    @Test
    void should_throw_when_construct_given_blankInstanceId() {
        // given：标识仅含空白字符
        // when // then
        assertThrows(IllegalStateException.class, () -> new AppInstanceProperties("   "));
    }

    @Test
    void should_throw_when_construct_given_unresolvedPlaceholder() {
        // given：占位文本被原样传入（部署时把 "${APP_INSTANCE_ID}" 当字面值写入环境变量）
        // when // then：未解析的占位文本同样按缺失处理，避免多实例共用同一脏标识而互相错杀
        assertThrows(IllegalStateException.class, () -> new AppInstanceProperties("${APP_INSTANCE_ID}"));
    }

    @Test
    void should_exposeTrimmedId_when_construct_given_paddedInstanceId() {
        // given：标识含首尾空白（环境变量值被误带空格，脏值会原样进入缓存键名而肉眼难辨）
        AppInstanceProperties properties = new AppInstanceProperties("  node-a  ");

        // when // then：规整为无空白的标识
        assertEquals("node-a", properties.getInstanceId());
    }

    @Test
    void should_exposeId_when_construct_given_normalInstanceId() {
        // given：部署者为该实例手填的唯一标识
        AppInstanceProperties properties = new AppInstanceProperties("node-a");

        // when // then：标识原样可读（缓存键的实例维度取此值）
        assertEquals("node-a", properties.getInstanceId());
    }

    @Test
    void should_keepEnvKeyName_when_read_given_environmentVariableContract() {
        // given // when // then：环境变量名是对外契约（部署文档与编排文件均按此名传值），不得被改动
        assertEquals("APP_INSTANCE_ID", AppInstanceProperties.ENV_APP_INSTANCE_ID);
    }
}