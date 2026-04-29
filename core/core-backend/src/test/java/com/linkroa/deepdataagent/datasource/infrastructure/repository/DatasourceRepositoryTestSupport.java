package com.linkroa.deepdataagent.datasource.infrastructure.repository;

import com.linkroa.deepdataagent.DeepDataAgentApplication;
import org.junit.jupiter.api.Tag;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Tag("integration")
@SpringBootTest(classes = DeepDataAgentApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
abstract class DatasourceRepositoryTestSupport {

    private static final Path DB_DIR = createTempDirectory();

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("app.sqlite.path", () -> DB_DIR.resolve("test.db").toString());
    }

    private static Path createTempDirectory() {
        try {
            return Files.createTempDirectory("datasource-mp-test-");
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create temporary datasource test directory", e);
        }
    }
}
