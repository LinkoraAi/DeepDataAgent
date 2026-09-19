package com.linkroa.deepdataagent.runtime.infrastructure.assembly;

import com.linkroa.deepdataagent.runtime.application.service.CoordinationLeaseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link DefaultCoordinationLeaseApi} 单测：跨 BC 契约进程内实现的薄委托语义。
 */
@ExtendWith(MockitoExtension.class)
class DefaultCoordinationLeaseApiTest {

    @Mock private CoordinationLeaseService coordinationLeaseService;

    private DefaultCoordinationLeaseApi api;

    @BeforeEach
    void setUp() {
        api = new DefaultCoordinationLeaseApi();
        ReflectionTestUtils.setField(api, "coordinationLeaseService", coordinationLeaseService);
    }

    @Test
    void should_delegateTrue_when_tryAcquireFireLease_given_serviceAccepts() {
        // given
        when(coordinationLeaseService.tryAcquireFireLease("dep-1")).thenReturn(true);

        // when // then
        assertTrue(api.tryAcquireFireLease("dep-1"));
        verify(coordinationLeaseService).tryAcquireFireLease("dep-1");
    }

    @Test
    void should_delegateFalse_when_tryAcquireFireLease_given_windowOccupied() {
        // given
        when(coordinationLeaseService.tryAcquireFireLease("dep-1")).thenReturn(false);

        // when // then
        assertFalse(api.tryAcquireFireLease("dep-1"));
    }

    @Test
    void should_delegateRelease_when_releaseFireLease_given_schedulerId() {
        // given // when
        api.releaseFireLease("dep-1");

        // then
        verify(coordinationLeaseService).releaseFireLease("dep-1");
    }
}
