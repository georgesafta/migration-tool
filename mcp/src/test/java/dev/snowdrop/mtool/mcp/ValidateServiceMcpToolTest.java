package dev.snowdrop.mtool.mcp;

import io.quarkiverse.mcp.server.test.McpAssured;
import io.quarkiverse.mcp.server.test.McpAssured.McpStreamableTestClient;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Calls quarkit_validate with the service validator through the MCP server. The projects are created under
 * target/, inside the default allowed directory (".").
 */
@QuarkusTest
public class ValidateServiceMcpToolTest {

    private static final Path ROOT = Path.of("target", "validate-service-mcp");

    private static final String SPRING_SERVICE = """
            package com.example;
            import org.springframework.stereotype.Service;
            @Service
            public class TodoService {}
            """;

    private Path target;
    private Path source;

    @BeforeEach
    void createProjects() throws IOException {
        if (Files.exists(ROOT)) {
            try (Stream<Path> paths = Files.walk(ROOT)) {
                paths.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
        target = ROOT.resolve("quarkus");
        source = ROOT.resolve("spring");
        write(target.resolve("src/main/java/com/example/TodoService.java"), SPRING_SERVICE);
        write(source.resolve("src/main/java/com/example/TodoService.java"), SPRING_SERVICE);
    }

    @Test
    public void fullQuarkusStrategyFlagsSpringAnnotations() {
        call(Map.of("target", target.toString(), "source", source.toString(), "validator", "service",
                "skipCompile", "true", "strategy", "full-quarkus"),
                text -> {
                    assertTrue(text.startsWith("## service"), text);
                    assertTrue(text.contains("[PASS] Migration strategy — Using strategy: full-quarkus"), text);
                    assertTrue(text.contains("[FAIL] Spring DI annotations"), text);
                });
    }

    @Test
    public void springCompatStrategyAllowsSpringAnnotations() {
        call(Map.of("target", target.toString(), "source", source.toString(), "validator", "service",
                "skipCompile", "true", "strategy", "spring-compat"),
                text -> {
                    assertTrue(text.contains("[PASS] Migration strategy — Using strategy: spring-compat"), text);
                    assertTrue(text.contains("[PASS] Spring DI annotations"), text);
                });
    }

    @Test
    public void missingStrategyIsAnErrorForServiceValidator() {
        call(Map.of("target", target.toString(), "source", source.toString(), "validator", "service",
                "skipCompile", "true"),
                text -> assertTrue(text.startsWith("Error: strategy is required for the 'service' validator"), text));
    }

    @Test
    public void allSkipsServiceWithoutStrategy() {
        call(Map.of("target", target.toString(), "source", source.toString(), "validator", "all",
                "skipCompile", "true"),
                text -> {
                    assertTrue(text.contains("## service\nStatus: SKIPPED"), text);
                    assertTrue(text.contains("[WARN] service — not run: set strategy"), text);
                    assertFalse(text.contains("Spring DI annotations"), text);
                });
    }

    @Test
    public void allRunsServiceWithStrategy() {
        call(Map.of("target", target.toString(), "source", source.toString(), "validator", "all",
                "skipCompile", "true", "strategy", "full-quarkus"),
                text -> {
                    assertFalse(text.contains("## service\nStatus: SKIPPED"), text);
                    assertTrue(text.contains("[FAIL] Spring DI annotations"), text);
                });
    }

    @Test
    public void errorsDocumentedInTheSkill() {
        call(Map.of("target", target.toString(), "validator", "service", "skipCompile", "true"),
                text -> assertTrue(text.startsWith("Error: source project path is required for the 'service'"), text));
        call(Map.of("target", target.toString(), "source", source.toString(), "validator", "service",
                "skipCompile", "true", "strategy", "hybrid"),
                text -> assertTrue(text.startsWith("Error: unknown strategy 'hybrid'"), text));
    }

    private static void call(Map<String, Object> args, java.util.function.Consumer<String> assertion) {
        McpStreamableTestClient client = McpAssured.newConnectedStreamableClient();
        client.when()
                .toolsCall("quarkit_validate", args, response -> assertion.accept(response.content().get(0).asText().text()))
                .thenAssertResults();
    }

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
