package com.linkroa.deepdataagent.agent.domain.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentVersionDomainServiceTest {

    private final AgentVersionDomainService service = new AgentVersionDomainService();

    @Test
    void should_returnNextVersion1_when_nextVersionNumber_given_currentMax0() {
        // given
        // 尚无任何版本

        // when
        int next = service.nextVersionNumber(0);

        // then
        assertEquals(1, next);
    }

    @Test
    void should_returnNextVersionNPlus1_when_nextVersionNumber_given_currentMaxN() {
        // given
        // 当前最大发布号为 N

        // when
        int next = service.nextVersionNumber(5);

        // then
        assertEquals(6, next);
    }

    @Test
    void should_returnNextVersion1_when_nextVersionNumber_given_definitionWithNoVersion() {
        // given
        // latest_version 初始为 0（仅定义，未发布）

        // when
        int next = service.nextVersionNumber(0);

        // then
        assertEquals(1, next);
    }
}