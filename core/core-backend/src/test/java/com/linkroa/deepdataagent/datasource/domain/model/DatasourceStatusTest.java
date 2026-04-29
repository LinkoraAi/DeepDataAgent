package com.linkroa.deepdataagent.datasource.domain.model;

import com.linkroa.deepdataagent.datasource.domain.model.enums.DatasourceStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DatasourceStatusTest {

    @Test
    void should_containEnabledAndDisabled_when_values_given_called() {
        DatasourceStatus[] values = DatasourceStatus.values();
        assertEquals(2, values.length);
    }

    @Test
    void should_returnEnabled_when_valueOf_given_ENABLED() {
        assertEquals(DatasourceStatus.ENABLED, DatasourceStatus.valueOf("ENABLED"));
    }

    @Test
    void should_returnDisabled_when_valueOf_given_DISABLED() {
        assertEquals(DatasourceStatus.DISABLED, DatasourceStatus.valueOf("DISABLED"));
    }
}
