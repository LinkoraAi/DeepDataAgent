package com.linkroa.deepdataagent.datasource.infrastructure.config;

import com.linkroa.deepdataagent.datasource.infrastructure.persistence.DatasourceSchemaInitializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DatasourceConfigTest {

    @Mock
    private DatasourceSchemaInitializer schemaInitializer;

    @Test
    void should_callSchemaInitializer_when_onApplicationReady_given_configInstance() {
        DatasourceConfig config = new DatasourceConfig(schemaInitializer);

        config.onApplicationReady();

        verify(schemaInitializer).initialize();
    }
}
