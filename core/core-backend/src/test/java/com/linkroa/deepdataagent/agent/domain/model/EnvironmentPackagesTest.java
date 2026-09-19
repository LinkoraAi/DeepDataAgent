package com.linkroa.deepdataagent.agent.domain.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link EnvironmentPackages} 值对象条目格式校验与归一单测。
 */
class EnvironmentPackagesTest {

    @Test
    void should_acceptValidEntries_when_construct_given_eachManager() {
        // given // when
        EnvironmentPackages packages = new EnvironmentPackages(
                List.of("git", "curl+openssl"),
                List.of("serde_core"),
                List.of("rails"),
                List.of("github.com/foo/bar"),
                List.of("typescript@5.0.0", "@types/node@20.11.0"),
                List.of("pandas==2.2.0"));

        // then
        assertEquals(List.of("git", "curl+openssl"), packages.apt());
        assertEquals(List.of("serde_core"), packages.cargo());
        assertEquals(List.of("rails"), packages.gem());
        assertEquals(List.of("github.com/foo/bar"), packages.go());
        assertEquals(List.of("typescript@5.0.0", "@types/node@20.11.0"), packages.npm());
        assertEquals(List.of("pandas==2.2.0"), packages.pip());
    }

    @Test
    void should_normalizeNullLists_when_construct_given_nullEntries() {
        // given // when
        EnvironmentPackages packages = new EnvironmentPackages(null, null, null, null, null, null);

        // then（null 收敛为空列表，六类全量回显）
        assertTrue(packages.isEmpty());
        assertEquals(List.of(), packages.apt());
        assertEquals(List.of(), packages.pip());
    }

    @Test
    void should_throwException_when_construct_given_blankEntry() {
        // given // when // then
        assertThrows(IllegalArgumentException.class,
                () -> new EnvironmentPackages(List.of(" "), null, null, null, null, null));
    }

    @Test
    void should_throwException_when_construct_given_overlongEntry() {
        // given
        String oversized = "a".repeat(256);

        // when // then
        assertThrows(IllegalArgumentException.class,
                () -> new EnvironmentPackages(null, null, null, null, null, List.of(oversized)));
    }

    @Test
    void should_throwException_when_construct_given_illegalAptName() {
        // given // when // then（apt 仅小写包名字符集）
        assertThrows(IllegalArgumentException.class,
                () -> new EnvironmentPackages(List.of("Git"), null, null, null, null, null));
    }

    @Test
    void should_throwException_when_construct_given_pipNonPinnedConstraint() {
        // given // when // then（pip 仅 == 锁定，>= 等区间限定拒绝）
        assertThrows(IllegalArgumentException.class,
                () -> new EnvironmentPackages(null, null, null, null, null, List.of("pandas>=2.0")));
    }

    @Test
    void should_throwException_when_construct_given_goModuleWithVersionSuffix() {
        // given // when // then（go 本期取默认版本，@ 版本段拒绝）
        assertThrows(IllegalArgumentException.class,
                () -> new EnvironmentPackages(null, null, null, List.of("github.com/foo/bar@v1.0.0"), null, null));
    }

    @Test
    void should_returnAllEmpty_when_empty_given_noArgs() {
        // given // when
        EnvironmentPackages packages = EnvironmentPackages.empty();

        // then
        assertTrue(packages.isEmpty());
    }
}
