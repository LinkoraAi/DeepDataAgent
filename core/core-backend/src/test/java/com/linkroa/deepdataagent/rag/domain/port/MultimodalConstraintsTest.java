package com.linkroa.deepdataagent.rag.domain.port;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MultimodalConstraints} 单元测试。
 * <p>校验集中常量表的字面量口径（describe 1 图 / 通路 A 3 图 / 通路 B 2 图、
 * 单图 4MB）与常量类不可实例化约定。</p>
 *
 * @author DeepDataAgent
 */
class MultimodalConstraintsTest {

    /**
     * 场景：读取三个场景的图片数量上限。
     * 预期：与约定的 1/3/2 逐值一致。
     */
    @Test
    void should_definePerScenarioImageLimits_when_constants_given_threeScenarios() {
        // given / when / then
        assertEquals(1, MultimodalConstraints.MAX_IMAGES_PER_DESCRIBE_REQUEST, "摄入媒体描述单次上限 1 图");
        assertEquals(3, MultimodalConstraints.MAX_IMAGES_PER_QUERY_REQUEST, "通路 A 检索附图单次上限 3 图");
        assertEquals(2, MultimodalConstraints.MAX_IMAGES_PER_ANSWER_REQUEST, "通路 B 作答直读单次上限 2 图");
    }

    /**
     * 场景：读取单图字节上限。
     * 预期：恰为 4MB（4 × 1024 × 1024 字节）。
     */
    @Test
    void should_defineFourMegabyteLimit_when_maxImageBytes_given_singleImageCap() {
        // given / when / then
        assertEquals(4L * 1024 * 1024, MultimodalConstraints.MAX_IMAGE_BYTES);
    }

    /**
     * 场景：反射检查常量类构造器。
     * 预期：构造器为私有，防止实例化。
     */
    @Test
    void should_keepPrivateConstructor_when_construct_given_constantsClass() {
        // given
        Constructor<?>[] constructors = MultimodalConstraints.class.getDeclaredConstructors();

        // when / then
        assertEquals(1, constructors.length, "常量类应仅声明一个构造器");
        assertTrue(Modifier.isPrivate(constructors[0].getModifiers()), "常量类构造器必须私有");
    }
}
