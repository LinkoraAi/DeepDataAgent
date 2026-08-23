package com.linkroa.deepdataagent.agent.domain.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SkillResourceManifest} 技能包结构化资源元数据值对象单测。
 */
class SkillResourceManifestTest {

    @Test
    void should_returnEmptyLists_when_empty_given_noArgs() {
        // when
        SkillResourceManifest manifest = SkillResourceManifest.empty();

        // then
        assertTrue(manifest.references().isEmpty());
        assertTrue(manifest.scripts().isEmpty());
    }

    @Test
    void should_normalizeNullToEmpty_when_create_given_nullLists() {
        // when
        SkillResourceManifest manifest = SkillResourceManifest.create(null, null);

        // then
        assertTrue(manifest.references().isEmpty());
        assertTrue(manifest.scripts().isEmpty());
    }

    @Test
    void should_createLists_when_create_given_validLists() {
        // given
        List<String> references = List.of("references/api.md");
        List<String> scripts = List.of("scripts/run.py");

        // when
        SkillResourceManifest manifest = SkillResourceManifest.create(references, scripts);

        // then
        assertEquals(references, manifest.references());
        assertEquals(scripts, manifest.scripts());
    }
}