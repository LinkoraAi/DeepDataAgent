package com.linkroa.deepdataagent.memory.architecture;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

class MemoryModuleNoSpringBeanManagementTest {

    private static final Path MEMORY_SOURCE_ROOT = Path.of(
            "src/main/java/com/linkroa/deepdataagent/memory"
    );

    private static final List<String> REMOVED_HELPER_CLASSES = List.of(
            "DeepLongMemoryFactory.java",
            "DeepLongMemorySupport.java"
    );

    private static final List<String> FORBIDDEN_PATTERNS = List.of(
            "org.springframework.stereotype.",
            "org.springframework.context.annotation.",
            "org.springframework.boot.context.properties.ConfigurationProperties",
            "org.springframework.beans.factory.annotation.",
            "jakarta.annotation.PostConstruct",
            "jakarta.annotation.PreDestroy",
            "@Component",
            "@Repository",
            "@Configuration",
            "@Bean",
            "@ConfigurationProperties",
            "@Qualifier",
            "@Autowired",
            "@Value",
            "@PostConstruct",
            "@PreDestroy"
    );

    @Test
    void should_notUseSpringBeanManagementInMemoryModule() throws IOException {
        List<String> violations;
        try (var stream = Files.walk(MEMORY_SOURCE_ROOT)) {
            violations = stream
                    .filter(path -> path.toString().endsWith(".java"))
                    .flatMap(MemoryModuleNoSpringBeanManagementTest::violationsIn)
                    .toList();
        }

        assertTrue(
                violations.isEmpty(),
                () -> "memory module must not use Spring bean management:\n" + String.join("\n", violations)
        );
    }

    @Test
    void should_notKeepExtraFactoryOrSupportClasses() {
        List<String> existingHelpers = REMOVED_HELPER_CLASSES.stream()
                .map(MEMORY_SOURCE_ROOT::resolve)
                .filter(Files::exists)
                .map(Path::toString)
                .toList();

        assertTrue(
                existingHelpers.isEmpty(),
                () -> "DeepLongMemory should expose the direct builder API without helper classes:\n"
                        + String.join("\n", existingHelpers)
        );
    }

    private static java.util.stream.Stream<String> violationsIn(Path path) {
        try {
            String content = Files.readString(path);
            return FORBIDDEN_PATTERNS.stream()
                    .filter(content::contains)
                    .map(pattern -> path + " contains " + pattern);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read " + path, e);
        }
    }
}
