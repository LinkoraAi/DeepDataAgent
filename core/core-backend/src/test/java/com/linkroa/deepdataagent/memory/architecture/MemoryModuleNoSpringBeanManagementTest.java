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

    private static final Path SPRING_PACKAGE = MEMORY_SOURCE_ROOT.resolve("spring");
    private static final Path CONFIG_PACKAGE = MEMORY_SOURCE_ROOT.resolve("config");

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
    void should_notUseSpringBeanManagementInCoreMemoryModule() throws IOException {
        List<String> violations;
        try (var stream = Files.walk(MEMORY_SOURCE_ROOT)) {
            violations = stream
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !path.startsWith(SPRING_PACKAGE))
                    .filter(path -> !path.startsWith(CONFIG_PACKAGE))  // 排除 config 包
                    .flatMap(MemoryModuleNoSpringBeanManagementTest::violationsIn)
                    .toList();
        }

        assertTrue(
                violations.isEmpty(),
                () -> "core memory module must not use Spring bean management:\n" + String.join("\n", violations)
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

    @Test
    void should_allowSpringAnnotationsInSpringPackage() throws IOException {
        // 验证 spring 包中的文件可以包含 Spring 注解
        List<String> springFiles;
        try (var stream = Files.walk(SPRING_PACKAGE)) {
            springFiles = stream
                    .filter(path -> path.toString().endsWith(".java"))
                    .map(Path::toString)
                    .toList();
        }

        // spring 包应该存在且可以包含 Spring 注解
        assertTrue(!springFiles.isEmpty(), "spring package should contain Java files");
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
