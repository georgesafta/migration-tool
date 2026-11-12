package dev.snowdrop.mtool.tests.validate;

import dev.snowdrop.mtool.model.validate.ValidationReport;
import dev.snowdrop.mtool.model.validate.ValidationResult;
import dev.snowdrop.mtool.model.validate.ValidationResult.Status;
import dev.snowdrop.mtool.scanner.analyzer.JavaAnalyzer;
import dev.snowdrop.mtool.scanner.analyzer.JavaParserAnalyzer;
import dev.snowdrop.mtool.scanner.analyzer.TreeSitterAnalyzer;
import dev.snowdrop.mtool.validate.MigrationStrategy;
import dev.snowdrop.mtool.validate.service.ServiceValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class ServiceValidatorTest {

    private static final String SPRING_SERVICE = """
            package com.example;
            import org.springframework.stereotype.Service;
            import org.springframework.beans.factory.annotation.Autowired;
            @Service
            public class TodoService {
                @Autowired private Repo repo;
            }
            """;

    private static final String QUARKUS_SERVICE = """
            package com.example;
            import jakarta.enterprise.context.ApplicationScoped;
            import jakarta.inject.Inject;
            @ApplicationScoped
            public class TodoService {
                @Inject Repo repo;
            }
            """;

    @TempDir
    Path tempDir;

    /** Strategy passed to the validator; {@code null} means the caller supplied none. JUnit creates one instance per test. */
    private MigrationStrategy strategy;

    static Stream<JavaAnalyzer> analyzers() {
        return Stream.of(new TreeSitterAnalyzer(), new JavaParserAnalyzer());
    }

    // ------------------------------------------------------------ strategy

    @ParameterizedTest(name = "{0}")
    @MethodSource("analyzers")
    void missingStrategyWarnsAndAssumesFullQuarkus(JavaAnalyzer analyzer) throws IOException {
        Path target = project("target", analyzer);
        write(target, "TodoService.java", SPRING_SERVICE);

        ValidationReport report = validate(analyzer, target, null);

        assertResult(report, "Migration strategy", Status.WARNING);
        assertEvidence(report, "Migration strategy", "assuming full-quarkus");
        assertResult(report, "Spring DI annotations", Status.FAILED);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("analyzers")
    void suppliedStrategyIsUsed(JavaAnalyzer analyzer) throws IOException {
        Path target = project("target", analyzer);
        strategy = MigrationStrategy.SPRING_COMPAT;
        write(target, "TodoService.java", SPRING_SERVICE);

        ValidationReport report = validate(analyzer, target, null);

        assertResult(report, "Migration strategy", Status.PASSED);
        assertEvidence(report, "Migration strategy", "spring-compat");
        assertResult(report, "Spring DI annotations", Status.PASSED);
    }

    @Test
    void strategyParsingAcceptsKnownNamesOnly() {
        assertThat(MigrationStrategy.parse("full-quarkus")).contains(MigrationStrategy.FULL_QUARKUS);
        assertThat(MigrationStrategy.parse(" Spring-Compat ")).contains(MigrationStrategy.SPRING_COMPAT);
        assertThat(MigrationStrategy.parse("hybrid")).isEmpty();
        assertThat(MigrationStrategy.parse(null)).isEmpty();
    }

    // ------------------------------------------------------- full-quarkus

    @ParameterizedTest(name = "{0}")
    @MethodSource("analyzers")
    void cleanQuarkusServicePasses(JavaAnalyzer analyzer) throws IOException {
        Path target = project("target", analyzer);
        strategy = MigrationStrategy.FULL_QUARKUS;
        write(target, "TodoService.java", QUARKUS_SERVICE);

        ValidationReport report = validate(analyzer, target, null);

        assertThat(report.failed()).isZero();
        assertThat(report.warnings()).isZero();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("analyzers")
    void flagsSpringDiAnnotations(JavaAnalyzer analyzer) throws IOException {
        Path target = project("target", analyzer);
        strategy = MigrationStrategy.FULL_QUARKUS;
        write(target, "TodoService.java", SPRING_SERVICE);

        ValidationReport report = validate(analyzer, target, null);

        assertResult(report, "Spring DI annotations", Status.FAILED);
        assertEvidence(report, "Spring DI annotations", "TodoService.java:4 @Service");
        assertEvidence(report, "Spring DI annotations", "@Autowired");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("analyzers")
    void flagsFullyQualifiedSpringAnnotation(JavaAnalyzer analyzer) throws IOException {
        Path target = project("target", analyzer);
        strategy = MigrationStrategy.FULL_QUARKUS;
        write(target, "TodoService.java", """
                package com.example;
                @org.springframework.stereotype.Service
                public class TodoService {}
                """);

        ValidationReport report = validate(analyzer, target, null);

        assertResult(report, "Spring DI annotations", Status.FAILED);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("analyzers")
    void lombokValueAndCustomServiceAreNotSpring(JavaAnalyzer analyzer) throws IOException {
        Path target = project("target", analyzer);
        strategy = MigrationStrategy.FULL_QUARKUS;
        write(target, "Dto.java", """
                package com.example;
                import lombok.Value;
                @Value
                public class Dto { String name; }
                """);
        write(target, "Other.java", """
                package com.example;
                import com.acme.Service;
                @Service
                public class Other {}
                """);

        ValidationReport report = validate(analyzer, target, null);

        assertResult(report, "Spring DI annotations", Status.PASSED);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("analyzers")
    void ignoresSpringTextInCommentsAndStrings(JavaAnalyzer analyzer) throws IOException {
        Path target = project("target", analyzer);
        strategy = MigrationStrategy.FULL_QUARKUS;
        write(target, "TodoService.java", """
                package com.example;
                import jakarta.enterprise.context.ApplicationScoped;
                // was @Autowired private Repo repo;
                /* import org.springframework.stereotype.Service;
                   @Service */
                @ApplicationScoped
                public class TodoService {
                    String doc = "use @Autowired and SpringApplication.run(App.class)";
                }
                """);

        ValidationReport report = validate(analyzer, target, null);

        assertResult(report, "Spring DI annotations", Status.PASSED);
        assertResult(report, "Spring Boot main class removed", Status.PASSED);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("analyzers")
    void excludesTestSources(JavaAnalyzer analyzer) throws IOException {
        Path target = project("target", analyzer);
        strategy = MigrationStrategy.FULL_QUARKUS;
        Path testDir = target.resolve("src/test/java/com/example");
        Files.createDirectories(testDir);
        Files.writeString(testDir.resolve("TodoServiceTest.java"), SPRING_SERVICE);

        ValidationReport report = validate(analyzer, target, null);

        assertResult(report, "Spring DI annotations", Status.PASSED);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("analyzers")
    void controllerStereotypeImportIsNotAContextType(JavaAnalyzer analyzer) throws IOException {
        Path target = project("target", analyzer);
        strategy = MigrationStrategy.FULL_QUARKUS;
        write(target, "Ctl.java", """
                package com.example;
                import org.springframework.stereotype.Controller;
                public class Ctl {}
                """);
        write(target, "Holder.java", """
                package com.example;
                import org.springframework.context.ApplicationContext;
                import org.springframework.context.annotation.EnableScheduling;
                public class Holder {}
                """);

        ValidationReport report = validate(analyzer, target, null);

        assertResult(report, "Spring context types", Status.FAILED);
        assertEvidence(report, "Spring context types", "Holder.java:2 import org.springframework.context.ApplicationContext");
        assertEvidence(report, "Spring context types", "EnableScheduling");
        assertThat(evidence(report, "Spring context types")).doesNotContain("Ctl.java");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("analyzers")
    void flagsSpringTransactionalScheduledAsyncConfigurationAndBean(JavaAnalyzer analyzer) throws IOException {
        Path target = project("target", analyzer);
        strategy = MigrationStrategy.FULL_QUARKUS;
        write(target, "Jobs.java", """
                package com.example;
                import org.springframework.transaction.annotation.Transactional;
                import org.springframework.scheduling.annotation.Scheduled;
                import org.springframework.scheduling.annotation.Async;
                import org.springframework.context.annotation.Configuration;
                import org.springframework.context.annotation.Bean;
                @Configuration
                public class Jobs {
                    @Transactional void a() {}
                    @Scheduled(fixedRate = 1000) void b() {}
                    @Async void c() {}
                    @Bean Object d() { return null; }
                }
                """);

        ValidationReport report = validate(analyzer, target, null);

        assertResult(report, "Transactional", Status.FAILED);
        assertResult(report, "Scheduled", Status.FAILED);
        assertResult(report, "Async", Status.FAILED);
        assertResult(report, "Configuration and Bean", Status.FAILED);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("analyzers")
    void jakartaAndQuarkusEquivalentsPass(JavaAnalyzer analyzer) throws IOException {
        Path target = project("target", analyzer);
        strategy = MigrationStrategy.FULL_QUARKUS;
        write(target, "Jobs.java", """
                package com.example;
                import jakarta.transaction.Transactional;
                import io.quarkus.scheduler.Scheduled;
                import jakarta.enterprise.context.ApplicationScoped;
                @ApplicationScoped
                public class Jobs {
                    @Transactional void a() {}
                    @Scheduled(every = "1s") void b() {}
                }
                """);

        ValidationReport report = validate(analyzer, target, null);

        assertResult(report, "Transactional", Status.PASSED);
        assertResult(report, "Scheduled", Status.PASSED);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("analyzers")
    void flagsLeftoverMainClassAndRunner(JavaAnalyzer analyzer) throws IOException {
        Path target = project("target", analyzer);
        strategy = MigrationStrategy.FULL_QUARKUS;
        write(target, "App.java", """
                package com.example;
                import org.springframework.boot.CommandLineRunner;
                import org.springframework.boot.SpringApplication;
                import org.springframework.boot.autoconfigure.SpringBootApplication;
                @SpringBootApplication
                public class App implements CommandLineRunner {
                    public static void main(String[] args) { SpringApplication.run(App.class, args); }
                    public void run(String... args) {}
                }
                """);

        ValidationReport report = validate(analyzer, target, null);

        assertResult(report, "Spring Boot main class removed", Status.FAILED);
        assertEvidence(report, "Spring Boot main class removed", "SpringApplication.run");
        assertEvidence(report, "Spring Boot main class removed", "@SpringBootApplication");
        assertEvidence(report, "Spring Boot main class removed", "CommandLineRunner");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("analyzers")
    void flagsJavaxLifecycleAnnotations(JavaAnalyzer analyzer) throws IOException {
        Path target = project("target", analyzer);
        strategy = MigrationStrategy.FULL_QUARKUS;
        write(target, "Lc.java", """
                package com.example;
                import javax.annotation.PostConstruct;
                public class Lc { @PostConstruct void init() {} }
                """);

        ValidationReport report = validate(analyzer, target, null);

        assertResult(report, "Jakarta lifecycle annotations", Status.FAILED);
    }

    // ------------------------------------------------------- spring-compat

    @ParameterizedTest(name = "{0}")
    @MethodSource("analyzers")
    void compatAllowsSpringDiButFlagsPrimaryAndConditional(JavaAnalyzer analyzer) throws IOException {
        Path target = project("target", analyzer);
        strategy = MigrationStrategy.SPRING_COMPAT;
        write(target, "TodoService.java", SPRING_SERVICE);

        ValidationReport allowed = validate(analyzer, target, null);
        assertResult(allowed, "Spring DI annotations", Status.PASSED);

        write(target, "Other.java", """
                package com.example;
                import org.springframework.context.annotation.Primary;
                import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
                @Primary
                @ConditionalOnProperty("x")
                public class Other {}
                """);
        ValidationReport flagged = validate(analyzer, target, null);
        assertResult(flagged, "Spring DI annotations", Status.FAILED);
        assertEvidence(flagged, "Spring DI annotations", "@Primary");
        assertEvidence(flagged, "Spring DI annotations", "@ConditionalOnProperty");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("analyzers")
    void compatTransactionalNestedFailsAndReadOnlyWarns(JavaAnalyzer analyzer) throws IOException {
        Path target = project("target", analyzer);
        strategy = MigrationStrategy.SPRING_COMPAT;
        write(target, "Tx.java", """
                package com.example;
                import org.springframework.transaction.annotation.Transactional;
                import org.springframework.transaction.annotation.Propagation;
                public class Tx {
                    @Transactional(readOnly = true) void a() {}
                }
                """);
        assertResult(validate(analyzer, target, null), "Transactional", Status.WARNING);

        write(target, "Tx.java", """
                package com.example;
                import org.springframework.transaction.annotation.Transactional;
                import org.springframework.transaction.annotation.Propagation;
                public class Tx {
                    @Transactional(propagation = Propagation.NESTED) void a() {}
                }
                """);
        assertResult(validate(analyzer, target, null), "Transactional", Status.FAILED);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("analyzers")
    void compatScheduledFixedDelayAndCronWithInitialDelayFail(JavaAnalyzer analyzer) throws IOException {
        Path target = project("target", analyzer);
        strategy = MigrationStrategy.SPRING_COMPAT;
        write(target, "Jobs.java", """
                package com.example;
                import org.springframework.scheduling.annotation.Scheduled;
                public class Jobs {
                    @Scheduled(fixedRate = 1000) void ok() {}
                }
                """);
        assertResult(validate(analyzer, target, null), "Scheduled", Status.PASSED);

        write(target, "Jobs.java", """
                package com.example;
                import org.springframework.scheduling.annotation.Scheduled;
                public class Jobs {
                    @Scheduled(fixedDelay = 1000) void a() {}
                    @Scheduled(cron = "0 * * * * *", initialDelay = 5) void b() {}
                }
                """);
        ValidationReport report = validate(analyzer, target, null);
        assertResult(report, "Scheduled", Status.FAILED);
        assertEvidence(report, "Scheduled", "fixedDelay");
        assertEvidence(report, "Scheduled", "cron + initialDelay");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("analyzers")
    void compatBeanOutsideConfigurationFails(JavaAnalyzer analyzer) throws IOException {
        Path target = project("target", analyzer);
        strategy = MigrationStrategy.SPRING_COMPAT;
        write(target, "Cfg.java", """
                package com.example;
                import org.springframework.context.annotation.Bean;
                import org.springframework.context.annotation.Configuration;
                @Configuration
                public class Cfg { @Bean Object a() { return null; } }
                """);
        write(target, "Loose.java", """
                package com.example;
                import org.springframework.context.annotation.Bean;
                public class Loose { @Bean Object b() { return null; } }
                """);

        ValidationReport report = validate(analyzer, target, null);

        assertResult(report, "Configuration and Bean", Status.FAILED);
        assertEvidence(report, "Configuration and Bean", "Loose.java");
        assertThat(evidence(report, "Configuration and Bean")).doesNotContain("Cfg.java");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("analyzers")
    void compatRequiresExtensionsInBuildFile(JavaAnalyzer analyzer) throws IOException {
        Path target = project("target", analyzer);
        strategy = MigrationStrategy.SPRING_COMPAT;
        write(target, "TodoService.java", SPRING_SERVICE);
        write(target, "Jobs.java", """
                package com.example;
                import org.springframework.scheduling.annotation.Scheduled;
                public class Jobs { @Scheduled(fixedRate = 1) void a() {} }
                """);
        Files.writeString(target.resolve("pom.xml"), pom("quarkus-spring-di"));

        ValidationReport report = validate(analyzer, target, null);

        assertResult(report, "Spring compat extensions", Status.FAILED);
        assertEvidence(report, "Spring compat extensions", "quarkus-spring-scheduled");
        assertThat(evidence(report, "Spring compat extensions")).doesNotContain("quarkus-spring-di (");

        Files.writeString(target.resolve("pom.xml"), pom("quarkus-spring-di", "quarkus-spring-scheduled"));
        assertResult(validate(analyzer, target, null), "Spring compat extensions", Status.PASSED);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("analyzers")
    void compatExtensionMentionedOutsideDependenciesDoesNotCount(JavaAnalyzer analyzer) throws IOException {
        Path target = project("target", analyzer);
        strategy = MigrationStrategy.SPRING_COMPAT;
        write(target, "TodoService.java", SPRING_SERVICE);
        Files.writeString(target.resolve("pom.xml"), """
                <project>
                  <!-- <dependency><groupId>io.quarkus</groupId><artifactId>quarkus-spring-di</artifactId></dependency> -->
                  <dependencyManagement><dependencies>
                    <dependency><groupId>io.quarkus</groupId><artifactId>quarkus-spring-di</artifactId></dependency>
                  </dependencies></dependencyManagement>
                </project>
                """);

        assertResult(validate(analyzer, target, null), "Spring compat extensions", Status.FAILED);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("analyzers")
    void compatExtensionsAreFoundInGradleBuild(JavaAnalyzer analyzer) throws IOException {
        Path target = project("target", analyzer);
        strategy = MigrationStrategy.SPRING_COMPAT;
        write(target, "TodoService.java", SPRING_SERVICE);
        Files.writeString(target.resolve("build.gradle"),
                "dependencies {\n  implementation 'io.quarkus:quarkus-spring-di'\n}\n");

        assertResult(validate(analyzer, target, null), "Spring compat extensions", Status.PASSED);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("analyzers")
    void compatFailsWhenNoBuildFileAndExtensionsAreNeeded(JavaAnalyzer analyzer) throws IOException {
        Path target = project("target", analyzer);
        strategy = MigrationStrategy.SPRING_COMPAT;
        write(target, "TodoService.java", SPRING_SERVICE);

        ValidationReport report = validate(analyzer, target, null);

        assertResult(report, "Spring compat extensions", Status.FAILED);
        assertEvidence(report, "Spring compat extensions", "Build file not found");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("analyzers")
    void compatWithoutBridgedFeaturesNeedsNoExtension(JavaAnalyzer analyzer) throws IOException {
        Path target = project("target", analyzer);
        strategy = MigrationStrategy.SPRING_COMPAT;
        write(target, "TodoService.java", QUARKUS_SERVICE);

        assertResult(validate(analyzer, target, null), "Spring compat extensions", Status.PASSED);
    }

    // ------------------------------------------------------------- parity

    @ParameterizedTest(name = "{0}")
    @MethodSource("analyzers")
    void beanParityPassesWhenImplCollapsedIntoScopedClass(JavaAnalyzer analyzer) throws IOException {
        Path source = project("source", analyzer);
        write(source, "TodoServiceImpl.java", SPRING_SERVICE.replace("TodoService", "TodoServiceImpl"));
        Path target = project("target", analyzer);
        strategy = MigrationStrategy.FULL_QUARKUS;
        write(target, "TodoService.java", QUARKUS_SERVICE);

        ValidationReport report = validate(analyzer, target, source);

        assertResult(report, "Bean scope parity", Status.PASSED);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("analyzers")
    void beanParityFailsForUnscopedTargetAndWarnsForMissing(JavaAnalyzer analyzer) throws IOException {
        Path source = project("source", analyzer);
        write(source, "TodoService.java", SPRING_SERVICE);
        Path target = project("target", analyzer);
        strategy = MigrationStrategy.FULL_QUARKUS;
        write(target, "TodoService.java", "package com.example;\npublic class TodoService {}\n");

        assertResult(validate(analyzer, target, source), "Bean scope parity", Status.FAILED);

        Files.delete(target.resolve("src/main/java/com/example/TodoService.java"));
        assertResult(validate(analyzer, target, source), "Bean scope parity", Status.WARNING);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("analyzers")
    void scheduledParityFailsWhenMethodDropped(JavaAnalyzer analyzer) throws IOException {
        Path source = project("source", analyzer);
        write(source, "Jobs.java", """
                package com.example;
                import org.springframework.scheduling.annotation.Scheduled;
                public class Jobs {
                    @Scheduled(fixedRate = 1) void a() {}
                    @Scheduled(cron = "0 * * * * *") void b() {}
                }
                """);
        Path target = project("target", analyzer);
        strategy = MigrationStrategy.FULL_QUARKUS;
        write(target, "Jobs.java", """
                package com.example;
                import io.quarkus.scheduler.Scheduled;
                public class Jobs { @Scheduled(every = "1s") void a() {} }
                """);

        assertResult(validate(analyzer, target, source), "Scheduled method parity", Status.FAILED);

        write(target, "Jobs.java", """
                package com.example;
                import io.quarkus.scheduler.Scheduled;
                public class Jobs {
                    @Scheduled(every = "1s") void a() {}
                    @Scheduled(cron = "0 * * * * ?") void b() {}
                }
                """);
        assertResult(validate(analyzer, target, source), "Scheduled method parity", Status.PASSED);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("analyzers")
    void runnerWithoutQuarkusMainWarns(JavaAnalyzer analyzer) throws IOException {
        Path source = project("source", analyzer);
        write(source, "App.java", """
                package com.example;
                import org.springframework.boot.CommandLineRunner;
                public class App implements CommandLineRunner { public void run(String... a) {} }
                """);
        Path target = project("target", analyzer);
        strategy = MigrationStrategy.FULL_QUARKUS;

        assertResult(validate(analyzer, target, source), "Runner migrated", Status.WARNING);

        write(target, "App.java", """
                package com.example;
                import io.quarkus.runtime.annotations.QuarkusMain;
                @QuarkusMain
                public class App {}
                """);
        assertResult(validate(analyzer, target, source), "Runner migrated", Status.PASSED);
    }

    // ------------------------------------------------------- file handling

    @ParameterizedTest(name = "{0}")
    @MethodSource("analyzers")
    void latin1EncodedSourcesAreStillChecked(JavaAnalyzer analyzer) throws IOException {
        Path target = project("target", analyzer);
        strategy = MigrationStrategy.FULL_QUARKUS;
        String source = "package com.example;\nimport org.springframework.stereotype.Service;\n// caf\u00e9\n@Service\npublic class Cafe {}\n";
        Files.write(target.resolve("src/main/java/com/example/Cafe.java"), source.getBytes(StandardCharsets.ISO_8859_1));

        assertResult(validate(analyzer, target, null), "Spring DI annotations", Status.FAILED);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("analyzers")
    void relativeProjectPathsAreSupported(JavaAnalyzer analyzer) throws IOException {
        Path target = project("target", analyzer);
        Path source = project("source", analyzer);
        strategy = MigrationStrategy.FULL_QUARKUS;
        write(target, "TodoService.java", SPRING_SERVICE);
        Path cwd = Path.of("").toAbsolutePath();

        ValidationReport report = new ServiceValidator(analyzer, cwd.relativize(source), strategy)
                .validate(cwd.relativize(target));

        assertResult(report, "Spring DI annotations", Status.FAILED);
        assertEvidence(report, "Spring DI annotations", "src/main/java/com/example/TodoService.java:4 @Service");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("analyzers")
    void buildOutputDirectoriesAreNotScanned(JavaAnalyzer analyzer) throws IOException {
        Path target = project("target", analyzer);
        strategy = MigrationStrategy.FULL_QUARKUS;
        Files.writeString(target.resolve("pom.xml"), pom());
        Path generated = target.resolve("target/generated-sources/com/example");
        Files.createDirectories(generated);
        Files.writeString(generated.resolve("Generated.java"), SPRING_SERVICE);

        assertResult(validate(analyzer, target, null), "Spring DI annotations", Status.PASSED);
    }

    // ------------------------------------------------------------ fixture

    @ParameterizedTest(name = "{0}")
    @MethodSource("analyzers")
    void unmigratedTodoAppFixtureIsFlagged(JavaAnalyzer analyzer) throws URISyntaxException {
        Path todoApp = Paths.get(getClass().getClassLoader().getResource("spring-boot-todo-app").toURI());

        ValidationReport report = validate(analyzer, todoApp, todoApp);

        assertResult(report, "Spring DI annotations", Status.FAILED);
        assertResult(report, "Spring Boot main class removed", Status.FAILED);
        assertResult(report, "Bean scope parity", Status.FAILED);
        assertEvidence(report, "Spring DI annotations", "TaskServiceImpl.java");
        assertEvidence(report, "Spring DI annotations", "TaskController.java");
        assertThat(evidence(report, "Spring DI annotations")).doesNotContain("AppApplicationTests");
        assertThat(evidence(report, "Spring context types")).contains("No Spring context types imported");
    }

    // ------------------------------------------------------------ helpers

    private ValidationReport validate(JavaAnalyzer analyzer, Path target, Path source) {
        return new ServiceValidator(analyzer, source, strategy).validate(target);
    }

    /** A pom declaring the given io.quarkus artifacts as dependencies. */
    private static String pom(String... artifacts) {
        StringBuilder dependencies = new StringBuilder();
        for (String artifact : artifacts) {
            dependencies.append("<dependency><groupId>io.quarkus</groupId><artifactId>").append(artifact)
                    .append("</artifactId></dependency>");
        }
        return "<project><dependencies>" + dependencies + "</dependencies></project>";
    }

    private Path project(String name, JavaAnalyzer analyzer) throws IOException {
        Path dir = tempDir.resolve(analyzer.getClass().getSimpleName()).resolve(name);
        Files.createDirectories(dir.resolve("src/main/java/com/example"));
        return dir;
    }

    private void write(Path project, String fileName, String content) throws IOException {
        Files.writeString(project.resolve("src/main/java/com/example").resolve(fileName), content);
    }

    private void assertResult(ValidationReport report, String ruleName, Status expected) {
        ValidationResult result = find(report, ruleName);
        assertThat(result.status())
                .as("rule '%s' (evidence: %s)", ruleName, result.evidence())
                .isEqualTo(expected);
    }

    private void assertEvidence(ValidationReport report, String ruleName, String expected) {
        assertThat(find(report, ruleName).evidence()).contains(expected);
    }

    private String evidence(ValidationReport report, String ruleName) {
        return find(report, ruleName).evidence();
    }

    private ValidationResult find(ValidationReport report, String ruleName) {
        return report.getResults().stream()
                .filter(r -> r.rule().name().equals(ruleName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No rule named '" + ruleName + "' in " + report.getResults()));
    }
}
