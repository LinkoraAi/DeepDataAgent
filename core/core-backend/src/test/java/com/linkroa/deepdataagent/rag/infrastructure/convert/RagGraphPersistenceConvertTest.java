package com.linkroa.deepdataagent.rag.infrastructure.convert;

import com.linkroa.deepdataagent.rag.domain.model.EntityNode;
import com.linkroa.deepdataagent.rag.domain.model.EntityProperties;
import com.linkroa.deepdataagent.rag.domain.model.RelationEdge;
import com.linkroa.deepdataagent.rag.domain.model.RelationProperties;
import com.linkroa.deepdataagent.rag.infrastructure.persistence.entity.EntityNodeGraphEntity;
import com.linkroa.deepdataagent.rag.infrastructure.persistence.entity.RelationEdgeGraphEntity;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RagGraphPersistenceConvert} 单元测试（graph-source-file-paths 5.4：properties JSON
 * 读写由单值 {@code filePath} 改为数组 {@code filePaths}）。
 * <p>覆盖：实体/关系属性序列化产出的 JSON 键恒为 {@code filePaths}（数组形态、不再出现旧键
 * {@code filePath}）；含溢出占位元素的多路径列表往返无损；反序列化缺失 {@code filePaths} 键
 * 时经 {@code normalize} 归一空列表；空白 JSON 回落空属性。</p>
 *
 * @author DeepDataAgent
 */
class RagGraphPersistenceConvertTest {

    /** 知识库ID */
    private static final Long KB_ID = 7L;

    /** 文档 A 路径 */
    private static final String PATH_A = "kb/7/A.docx";

    /** 文档 B 路径 */
    private static final String PATH_B = "kb/7/B.pdf";

    /** 溢出占位元素（截断口径见 GraphSourceFilePaths） */
    private static final String PLACEHOLDER = "…等(KEEP;保留2/共5)";

    /** 实体名 */
    private static final String ENTITY_NAME = "张三";

    // ===== 实体侧 =====

    @Test
    void should_writeFilePathsAsJsonArray_when_entityPropertiesToJson_given_multiDocumentPaths() {
        // given：跨文档共享实体（含溢出占位元素）
        EntityProperties properties = new EntityProperties("PERSON", "描述", List.of(101L),
                List.of(PATH_A, PATH_B, PLACEHOLDER), Map.of("PERSON", 1), List.of("描述"));
        EntityNode node = new EntityNode(KB_ID, ENTITY_NAME, properties);

        // when：领域模型 → 持久化实体（properties 序列化）
        EntityNodeGraphEntity entity = RagGraphPersistenceConvert.INSTANCE.toEntity(node);

        // then：键名为 filePaths 的 JSON 数组，不再出现旧单值键 filePath
        assertTrue(entity.getProperties().contains("\"filePaths\":["),
                "properties JSON 应携带 filePaths 数组键: " + entity.getProperties());
        assertFalse(entity.getProperties().contains("\"filePath\":"),
                "旧单值键 filePath MUST NOT 再出现");
        assertTrue(entity.getProperties().contains(PATH_A));
        assertTrue(entity.getProperties().contains(PLACEHOLDER), "占位元素应原样落库");
    }

    @Test
    void should_roundTripFilePaths_when_toEntityNode_given_serializedEntityRow() {
        // given：序列化后的图行
        EntityNode original = new EntityNode(KB_ID, ENTITY_NAME, new EntityProperties("PERSON", "描述",
                List.of(101L, 102L), List.of(PATH_A, PATH_B), Map.of("PERSON", 2), List.of("描述")));

        // when：序列化 → 反序列化往返
        EntityNodeGraphEntity entity = RagGraphPersistenceConvert.INSTANCE.toEntity(original);
        EntityNode restored = RagGraphPersistenceConvert.INSTANCE.toEntityNode(entity);

        // then：filePaths 列表往返无损（顺序与元素逐一一致）
        assertEquals(List.of(PATH_A, PATH_B), restored.properties().filePaths());
        assertEquals(original.properties(), restored.properties());
    }

    @Test
    void should_normalizeToEmptyFilePaths_when_toEntityNode_given_jsonWithoutFilePathsKey() {
        // given：历史形态 JSON——无 filePaths 键（其余字段齐备）
        EntityNodeGraphEntity entity = new EntityNodeGraphEntity();
        entity.setKbId(KB_ID);
        entity.setEntityName(ENTITY_NAME);
        entity.setProperties("{\"entityType\":\"PERSON\",\"description\":\"描述\","
                + "\"sourceIds\":[101],\"entityTypeVotes\":{\"PERSON\":1},\"descriptions\":[\"描述\"]}");

        // when
        EntityNode restored = RagGraphPersistenceConvert.INSTANCE.toEntityNode(entity);

        // then：缺失键归一为空列表而非 null
        assertEquals(List.of(), restored.properties().filePaths(), "缺失 filePaths 键应归一空列表");
    }

    @Test
    void should_returnEmptyProperties_when_jsonToEntityProperties_given_blankJson() {
        // given：properties 列空白（脏行/缺列）

        // when & then：回落空属性（filePaths 空列表）
        EntityProperties properties = RagGraphPersistenceConvert.INSTANCE.jsonToEntityProperties("  ");
        assertTrue(properties.filePaths().isEmpty());
    }

    // ===== 关系侧 =====

    @Test
    void should_writeFilePathsAsJsonArray_when_relationPropertiesToJson_given_multiDocumentPaths() {
        // given：跨文档共享关系 + 占位元素
        RelationEdge edge = new RelationEdge(KB_ID, "张三", "公司",
                new RelationProperties(2.0, "任职于", List.of("雇佣"), List.of(101L, 102L),
                        List.of(PATH_A, PATH_B, PLACEHOLDER)));

        // when
        RelationEdgeGraphEntity entity = RagGraphPersistenceConvert.INSTANCE.toEntity(edge);

        // then：filePaths 数组键、无旧 filePath 键
        assertTrue(entity.getProperties().contains("\"filePaths\":["),
                "properties JSON 应携带 filePaths 数组键: " + entity.getProperties());
        assertFalse(entity.getProperties().contains("\"filePath\":"), "旧单值键 filePath MUST NOT 再出现");
    }

    @Test
    void should_roundTripFilePaths_when_toRelationEdge_given_serializedEdgeRow() {
        // given：多来源路径的关系边
        RelationEdge original = new RelationEdge(KB_ID, "张三", "公司",
                new RelationProperties(1.0, "任职于", List.of("雇佣"), List.of(101L),
                        List.of(PATH_A, PATH_B)));

        // when：往返
        RelationEdgeGraphEntity entity = RagGraphPersistenceConvert.INSTANCE.toEntity(original);
        RelationEdge restored = RagGraphPersistenceConvert.INSTANCE.toRelationEdge(entity);

        // then：列表往返无损
        assertEquals(List.of(PATH_A, PATH_B), restored.properties().filePaths());
        assertEquals(original.properties(), restored.properties());
    }

    @Test
    void should_returnEmptyFilePaths_when_jsonToRelationProperties_given_blankJson() {
        // given：properties 列空白

        // when & then
        RelationProperties properties = RagGraphPersistenceConvert.INSTANCE.jsonToRelationProperties(null);
        assertTrue(properties.filePaths().isEmpty());
    }

    // ===== TypeReference 常量化等价性（converge-rag-hot-path-object-creation / 3.4） =====

    @Test
    void should_decodeIdenticallyToAnonymousType_when_jsonListTypes_given_constantTypeReferences() throws Exception {
        // given：同一份 chunk_ids / keywords JSON 数组文本
        String longJson = "[101,102,103]";
        String stringJson = "[\"雇佣\",\"研究\"]";

        // when：经 static final 常量类型与经临时匿名实例各解一遍（Jackson 2 解码路径）
        List<Long> longsByConstant = RagGraphPersistenceConvert.OBJECT_MAPPER
                .readValue(longJson, RagGraphPersistenceConvert.LONG_LIST_TYPE);
        List<Long> longsByAnonymous = RagGraphPersistenceConvert.OBJECT_MAPPER
                .readValue(longJson, new com.fasterxml.jackson.core.type.TypeReference<List<Long>>() {
                });
        List<String> stringsByConstant = RagGraphPersistenceConvert.OBJECT_MAPPER
                .readValue(stringJson, RagGraphPersistenceConvert.STRING_LIST_TYPE);
        List<String> stringsByAnonymous = RagGraphPersistenceConvert.OBJECT_MAPPER
                .readValue(stringJson, new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {
                });

        // then：常量与匿名实例解码结果逐一一致；转换方法出口同样一致
        assertEquals(longsByAnonymous, longsByConstant, "LONG_LIST_TYPE 常量应与匿名实例解码一致");
        assertEquals(stringsByAnonymous, stringsByConstant, "STRING_LIST_TYPE 常量应与匿名实例解码一致");
        assertEquals(longsByConstant,
                RagGraphPersistenceConvert.INSTANCE.jsonToLongList(longJson));
        assertEquals(stringsByConstant,
                RagGraphPersistenceConvert.INSTANCE.jsonToStringList(stringJson));
    }
}
