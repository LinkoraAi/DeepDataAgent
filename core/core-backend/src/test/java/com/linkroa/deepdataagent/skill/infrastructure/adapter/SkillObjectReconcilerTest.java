package com.linkroa.deepdataagent.skill.infrastructure.adapter;

import com.linkroa.deepdataagent.shared.storage.ObjectMetadata;
import com.linkroa.deepdataagent.shared.storage.ObjectStorage;
import com.linkroa.deepdataagent.skill.infrastructure.persistence.mapper.SkillContentVersionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link SkillObjectReconciler} 启动对账单测：以<b>物理行（含 is_deleted=1）</b>为口径——
 * 仅回滚 / insert 前的无物理行前缀才删除；已逻辑删除版本的内容法定保留；畸形 key 跳过、
 * 列举失败容错。
 */
@ExtendWith(MockitoExtension.class)
class SkillObjectReconcilerTest {

    @Mock
    private ObjectStorage objectStorage;
    @Mock
    private SkillContentVersionMapper versionMapper;

    private SkillObjectReconciler reconciler;

    @BeforeEach
    void setUp() {
        reconciler = new SkillObjectReconciler();
        ReflectionTestUtils.setField(reconciler, "objectStorage", objectStorage);
        ReflectionTestUtils.setField(reconciler, "versionMapper", versionMapper);
    }

    private ObjectMetadata object(String key) {
        return new ObjectMetadata(key, 1L, null, null, null);
    }

    @Test
    void should_deleteOnlyPrefixWithoutPhysicalRow_when_reconcileOnce_given_liveDeletedAndOrphan() {
        // given（skill_a v1000 存活物理行；skill_c v3000 已逻辑删除但物理行仍在；
        // skill_b v2000 无任何物理行=半成品）
        when(objectStorage.list("skills/")).thenReturn(List.of(
                object("skills/skill_a/1000/SKILL.md"),
                object("skills/skill_a/1000/refs/a.md"),
                object("skills/skill_c/3000/SKILL.md"),
                object("skills/skill_b/2000/SKILL.md"),
                object("skills/unparsable"),
                object("skills/skill_x/x/SKILL.md")));
        when(versionMapper.countPhysicalBySkillIdAndVersion("skill_a", "1000")).thenReturn(1);
        when(versionMapper.countPhysicalBySkillIdAndVersion("skill_c", "3000")).thenReturn(1);
        when(versionMapper.countPhysicalBySkillIdAndVersion("skill_b", "2000")).thenReturn(0);

        // when
        reconciler.reconcileOnce();

        // then（仅无物理行的 skill_b v2000 前缀被删；存活与已逻辑删除版本保留；畸形 key 跳过）
        verify(versionMapper).countPhysicalBySkillIdAndVersion("skill_a", "1000");
        verify(versionMapper).countPhysicalBySkillIdAndVersion("skill_c", "3000");
        verify(objectStorage).deletePrefix("skills/skill_b/2000/");
        verify(objectStorage, never()).deletePrefix("skills/skill_a/1000/");
        verify(objectStorage, never()).deletePrefix("skills/skill_c/3000/");
    }

    @Test
    void should_notThrowAndSkipCleanup_when_reconcileOnce_given_listFails() {
        // given
        when(objectStorage.list("skills/")).thenThrow(new RuntimeException("s3 unavailable"));

        // when & then（列举失败不阻断启动，不删任何前缀）
        reconciler.reconcileOnce();
        verify(objectStorage, never()).deletePrefix(anyString());
        verify(versionMapper, never())
                .countPhysicalBySkillIdAndVersion(anyString(), anyString());
    }

    @Test
    void should_doNothing_when_reconcileOnce_given_emptyListing() {
        // given
        when(objectStorage.list("skills/")).thenReturn(List.of());

        // when
        reconciler.reconcileOnce();

        // then
        verify(objectStorage, never()).deletePrefix(anyString());
    }
}
