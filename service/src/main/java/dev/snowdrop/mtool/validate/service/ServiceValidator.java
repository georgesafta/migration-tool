package dev.snowdrop.mtool.validate.service;

import dev.snowdrop.mtool.model.java.JavaTypeDeclaration;
import dev.snowdrop.mtool.model.java.JavaTypeKind;
import dev.snowdrop.mtool.model.validate.ValidationReport;
import dev.snowdrop.mtool.model.validate.ValidationResult;
import dev.snowdrop.mtool.model.validate.ValidationResult.Status;
import dev.snowdrop.mtool.model.validate.ValidationRule;
import dev.snowdrop.mtool.project.JavaSourceText;
import dev.snowdrop.mtool.project.JavaSourceText.AnnotationUse;
import dev.snowdrop.mtool.project.JavaSourceText.Parsed;
import dev.snowdrop.mtool.project.ProjectFiles;
import dev.snowdrop.mtool.project.build.BuildFile;
import dev.snowdrop.mtool.project.build.Dependency;
import dev.snowdrop.mtool.scanner.analyzer.JavaAnalyzer;
import dev.snowdrop.mtool.validate.MigrationStrategy;
import dev.snowdrop.mtool.validate.Validator;
import org.jboss.logging.Logger;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Validates the service layer of a Spring to Quarkus migration: dependency injection, configuration injection,
 * transactions, scheduling, async, {@code @Configuration}/{@code @Bean}, and application bootstrap.
 * <p>
 * The expected end state depends on the migration strategy supplied by the caller (when none is supplied it
 * assumes {@code full-quarkus} and reports a warning):
 * <ul>
 * <li>{@code full-quarkus}: no Spring service-layer API may remain.</li>
 * <li>{@code spring-compat}: Spring annotations supported by the {@code quarkus-spring-*} extensions are allowed,
 * but usages those extensions cannot handle are reported, as is a missing extension.</li>
 * </ul>
 * Annotations are matched by their resolved fully qualified name (explicit import, wildcard import or qualified
 * use) in comment-stripped source, so Lombok's {@code @Value} or a custom {@code @Service} are not mistaken for
 * Spring's. Test sources ({@code src/test}) are excluded; the testing module owns them. Controllers are owned by
 * the rest validator, but {@code @Autowired} and {@code @Value} are checked everywhere, including controllers.
 */
public class ServiceValidator implements Validator {

    private static final Logger logger = Logger.getLogger(ServiceValidator.class);

    private static final String SPRING_TRANSACTIONAL = "org.springframework.transaction.annotation.Transactional";
    private static final String SPRING_SCHEDULED = "org.springframework.scheduling.annotation.Scheduled";
    private static final String QUARKUS_SCHEDULED = "io.quarkus.scheduler.Scheduled";
    private static final String SPRING_ASYNC = "org.springframework.scheduling.annotation.Async";
    private static final String SPRING_CONFIGURATION = "org.springframework.context.annotation.Configuration";
    private static final String SPRING_BEAN = "org.springframework.context.annotation.Bean";
    private static final String SPRING_BOOT_APPLICATION = "org.springframework.boot.autoconfigure.SpringBootApplication";

    private static final Set<String> SPRING_DI = Set.of(
            "org.springframework.stereotype.Service",
            "org.springframework.stereotype.Component",
            "org.springframework.stereotype.Repository",
            "org.springframework.beans.factory.annotation.Autowired",
            "org.springframework.beans.factory.annotation.Value",
            "org.springframework.beans.factory.annotation.Qualifier",
            "org.springframework.context.annotation.Scope",
            "org.springframework.web.context.annotation.RequestScope",
            "org.springframework.web.context.annotation.SessionScope",
            "org.springframework.web.context.annotation.ApplicationScope");

    /** Processed by neither Quarkus nor quarkus-spring-di. */
    private static final Set<String> SPRING_DI_UNSUPPORTED = Set.of(
            "org.springframework.context.annotation.Primary",
            "org.springframework.context.annotation.Lazy",
            "org.springframework.context.annotation.Profile");

    private static final List<String> CONDITIONAL_PACKAGES = List.of(
            "org.springframework.context.annotation",
            "org.springframework.boot.autoconfigure.condition");

    /** Explicit list of service-owned Spring types; package prefixes would also match controller stereotypes. */
    private static final Set<String> SPRING_CONTEXT_TYPES = Set.of(
            "org.springframework.context.ApplicationContext",
            "org.springframework.context.ApplicationContextAware",
            "org.springframework.context.ApplicationEventPublisher",
            "org.springframework.context.ApplicationListener",
            "org.springframework.context.event.EventListener",
            "org.springframework.beans.factory.BeanFactory",
            "org.springframework.beans.factory.ListableBeanFactory",
            "org.springframework.beans.factory.BeanFactoryAware",
            "org.springframework.beans.factory.BeanFactoryUtils",
            "org.springframework.web.context.support.SpringBeanAutowiringSupport");
    private static final Pattern SPRING_ENABLE_IMPORT = Pattern.compile("org\\.springframework\\.[\\w.]*\\.Enable\\w+");

    private static final Set<String> SPRING_RUNNERS = Set.of(
            "org.springframework.boot.CommandLineRunner",
            "org.springframework.boot.ApplicationRunner");
    private static final Pattern SPRING_APPLICATION_RUN = Pattern.compile("\\bSpringApplication\\s*\\.\\s*run\\s*\\(");

    private static final Set<String> QUARKUS_SCOPES = Set.of(
            "ApplicationScoped", "Singleton", "RequestScoped", "SessionScoped", "Dependent");
    private static final Set<String> SPRING_STEREOTYPE_SIMPLE_NAMES = Set.of(
            "Service", "Component", "Configuration", "Repository");
    private static final Set<String> SPRING_BEAN_STEREOTYPES = Set.of(
            "org.springframework.stereotype.Service",
            "org.springframework.stereotype.Component",
            SPRING_CONFIGURATION);
    private static final Set<String> QUARKUS_MAIN_ANNOTATIONS = Set.of("QuarkusMain", "TopCommand");

    private static final Pattern NESTED = Pattern.compile("\\bpropagation\\s*=[^,)]*NESTED");
    private static final Pattern READ_ONLY_TRUE = Pattern.compile("\\breadOnly\\s*=\\s*true\\b");
    private static final Pattern TIMEOUT = Pattern.compile("\\btimeout\\w*\\s*=");
    private static final Pattern FIXED_DELAY = Pattern.compile("\\bfixedDelay\\w*\\s*=");
    private static final Pattern CRON = Pattern.compile("\\bcron\\s*=");
    private static final Pattern INITIAL_DELAY = Pattern.compile("\\binitialDelay\\w*\\s*=");

    private static final Dependency SPRING_DI_EXTENSION = Dependency.of("io.quarkus", "quarkus-spring-di");
    private static final Dependency SPRING_TX_EXTENSION = Dependency.of("io.quarkus", "quarkus-spring-tx");
    private static final Dependency SPRING_SCHEDULED_EXTENSION = Dependency.of("io.quarkus", "quarkus-spring-scheduled");

    private static final int MAX_EVIDENCE_ITEMS = 5;

    private final JavaAnalyzer analyzer;
    private final Path springProjectPath;
    private final MigrationStrategy requestedStrategy;

    /**
     * @param strategy the migration strategy chosen for the project, or {@code null} if the caller did not supply
     *        one (full-quarkus is then assumed, with a warning)
     */
    public ServiceValidator(JavaAnalyzer analyzer, Path springProjectPath, MigrationStrategy strategy) {
        this.analyzer = analyzer;
        this.springProjectPath = springProjectPath == null ? null : springProjectPath.toAbsolutePath().normalize();
        this.requestedStrategy = strategy;
    }

    @Override
    public String name() {
        return "service";
    }

    @Override
    public ValidationReport validate(Path projectPath) {
        ValidationReport report = new ValidationReport(name());
        Path quarkusProjectPath = projectPath.toAbsolutePath().normalize();

        MigrationStrategy strategy = requestedStrategy != null ? requestedStrategy : MigrationStrategy.FULL_QUARKUS;
        boolean compat = strategy == MigrationStrategy.SPRING_COMPAT;
        ValidationRule strategyRule = new ValidationRule("Migration strategy",
                "The strategy argument selects which Spring APIs may remain");
        if (requestedStrategy == null) {
            report.add(new ValidationResult(strategyRule, Status.WARNING,
                    "No strategy supplied; assuming full-quarkus (pass strategy 'full-quarkus' or 'spring-compat')"));
        } else {
            report.add(new ValidationResult(strategyRule, Status.PASSED, "Using strategy: " + strategy.value()));
        }

        logger.infof("Analyzing Quarkus project: %s (strategy: %s)", quarkusProjectPath, strategy.value());
        ProjectFiles targetFiles = ProjectFiles.scan(quarkusProjectPath);
        List<Parsed> target = parseMainSources(targetFiles);
        logger.infof("Parsed %d main Java files", target.size());

        checkDiAnnotations(report, quarkusProjectPath, target, compat);
        if (!compat) {
            checkSpringContextTypes(report, quarkusProjectPath, target);
        }
        checkTransactional(report, quarkusProjectPath, target, compat);
        checkScheduled(report, quarkusProjectPath, target, compat);
        checkAsync(report, quarkusProjectPath, target);
        checkConfigurationAndBean(report, quarkusProjectPath, target, compat);
        checkBootstrap(report, quarkusProjectPath, target);

        List<Parsed> source = null;
        if (springProjectPath != null) {
            logger.infof("Analyzing Spring project: %s", springProjectPath);
            source = parseMainSources(ProjectFiles.scan(springProjectPath));
            checkBeanParity(report, quarkusProjectPath, compat);
            checkRunnerMigrated(report, springProjectPath, source, target);
            checkScheduledParity(report, source, target);
        }

        if (compat) {
            checkCompatExtensions(report, targetFiles.buildFile(), target);
        }
        checkJavaxLifecycle(report, quarkusProjectPath, target);

        return report;
    }

    // ---------------------------------------------------------------- rules

    private void checkDiAnnotations(ValidationReport report, Path root, List<Parsed> target, boolean compat) {
        ValidationRule rule = compat
                ? new ValidationRule("Spring DI annotations",
                        "@Primary, @Lazy, @Profile and @Conditional* are not processed by quarkus-spring-di")
                : new ValidationRule("Spring DI annotations",
                        "Spring @Service/@Component/@Repository/@Autowired/@Value/@Qualifier/@Scope must be replaced with CDI and MicroProfile Config");
        Findings found = new Findings();
        for (Parsed p : target) {
            for (AnnotationUse use : p.annotations()) {
                boolean spring = match(p, use, SPRING_DI_UNSUPPORTED) != null
                        || isSpringConditional(p, use)
                        || (!compat && match(p, use, SPRING_DI) != null);
                if (spring) {
                    found.add(root, p, use);
                }
            }
        }
        result(report, rule, found, Status.FAILED,
                compat ? "No unsupported Spring DI annotations" : "No Spring DI annotations remain",
                compat ? " unsupported by quarkus-spring-di: " : " must be migrated to CDI/MicroProfile Config: ");
    }

    private void checkSpringContextTypes(ValidationReport report, Path root, List<Parsed> target) {
        ValidationRule rule = new ValidationRule("Spring context types",
                "ApplicationContext, BeanFactory, ApplicationEventPublisher, @EventListener and @Enable* must be replaced with CDI");
        Findings found = new Findings();
        for (Parsed p : target) {
            for (String imp : p.imports()) {
                if (SPRING_CONTEXT_TYPES.contains(imp) || SPRING_ENABLE_IMPORT.matcher(imp).matches()) {
                    found.add(root, p, importLine(p, imp), "import " + imp);
                }
            }
        }
        result(report, rule, found, Status.FAILED, "No Spring context types imported",
                " must be replaced with CDI (Instance, Event, @Observes): ");
    }

    private void checkTransactional(ValidationReport report, Path root, List<Parsed> target, boolean compat) {
        if (!compat) {
            ValidationRule rule = new ValidationRule("Transactional",
                    "Spring @Transactional must be replaced with jakarta.transaction.Transactional");
            Findings found = new Findings();
            forEach(target, SPRING_TRANSACTIONAL, (p, use) -> found.add(root, p, use));
            result(report, rule, found, Status.FAILED, "No Spring @Transactional",
                    " use jakarta.transaction.Transactional: ");
            return;
        }
        ValidationRule rule = new ValidationRule("Transactional",
                "quarkus-spring-tx rejects propagation=NESTED and ignores readOnly and timeout");
        Findings nested = new Findings();
        Findings ignored = new Findings();
        forEach(target, SPRING_TRANSACTIONAL, (p, use) -> {
            if (NESTED.matcher(use.args()).find()) {
                nested.add(root, p, use);
            } else if (READ_ONLY_TRUE.matcher(use.args()).find() || TIMEOUT.matcher(use.args()).find()) {
                ignored.add(root, p, use);
            }
        });
        if (!nested.isEmpty()) {
            result(report, rule, nested, Status.FAILED, "", " use propagation=NESTED (build error in quarkus-spring-tx): ");
        } else if (!ignored.isEmpty()) {
            result(report, rule, ignored, Status.WARNING, "",
                    " set readOnly or timeout, which quarkus-spring-tx silently ignores: ");
        } else {
            report.add(new ValidationResult(rule, Status.PASSED, "No unsupported @Transactional attributes"));
        }
    }

    private void checkScheduled(ValidationReport report, Path root, List<Parsed> target, boolean compat) {
        if (!compat) {
            ValidationRule rule = new ValidationRule("Scheduled",
                    "Spring @Scheduled must be replaced with io.quarkus.scheduler.Scheduled");
            Findings found = new Findings();
            forEach(target, SPRING_SCHEDULED, (p, use) -> found.add(root, p, use));
            result(report, rule, found, Status.FAILED, "No Spring @Scheduled",
                    " use io.quarkus.scheduler.Scheduled: ");
            return;
        }
        ValidationRule rule = new ValidationRule("Scheduled",
                "quarkus-spring-scheduled does not support fixedDelay, or initialDelay combined with cron");
        Findings found = new Findings();
        forEach(target, SPRING_SCHEDULED, (p, use) -> {
            boolean fixedDelay = FIXED_DELAY.matcher(use.args()).find();
            boolean cronWithDelay = CRON.matcher(use.args()).find() && INITIAL_DELAY.matcher(use.args()).find();
            if (fixedDelay || cronWithDelay) {
                found.add(root, p, use.line(), "@Scheduled(" + (fixedDelay ? "fixedDelay" : "cron + initialDelay") + ")");
            }
        });
        result(report, rule, found, Status.FAILED, "No unsupported @Scheduled attributes",
                " use attributes quarkus-spring-scheduled does not support: ");
    }

    private void checkAsync(ValidationReport report, Path root, List<Parsed> target) {
        ValidationRule rule = new ValidationRule("Async",
                "Spring @Async has no Quarkus equivalent (also unsupported by the compat extensions)");
        Findings found = new Findings();
        forEach(target, SPRING_ASYNC, (p, use) -> found.add(root, p, use));
        result(report, rule, found, Status.FAILED, "No Spring @Async",
                " must be reworked (Mutiny, @RunOnVirtualThread, or ManagedExecutor): ");
    }

    private void checkConfigurationAndBean(ValidationReport report, Path root, List<Parsed> target, boolean compat) {
        Findings found = new Findings();
        if (!compat) {
            ValidationRule rule = new ValidationRule("Configuration and Bean",
                    "Spring @Configuration/@Bean must be replaced with @Produces in a scoped CDI class");
            forEach(target, SPRING_CONFIGURATION, (p, use) -> found.add(root, p, use));
            forEach(target, SPRING_BEAN, (p, use) -> found.add(root, p, use));
            result(report, rule, found, Status.FAILED, "No Spring @Configuration or @Bean",
                    " use @Produces in an @ApplicationScoped class: ");
            return;
        }
        ValidationRule rule = new ValidationRule("Configuration and Bean",
                "quarkus-spring-di requires @Bean methods to be inside a @Configuration class");
        for (Parsed p : target) {
            boolean hasConfiguration = p.annotations().stream().anyMatch(u -> p.resolves(u, SPRING_CONFIGURATION));
            if (hasConfiguration) {
                continue;
            }
            for (AnnotationUse use : p.annotations()) {
                if (p.resolves(use, SPRING_BEAN)) {
                    found.add(root, p, use);
                }
            }
        }
        result(report, rule, found, Status.FAILED, "All @Bean methods are in @Configuration classes",
                " is outside a @Configuration class: ");
    }

    private void checkBootstrap(ValidationReport report, Path root, List<Parsed> target) {
        ValidationRule rule = new ValidationRule("Spring Boot main class removed",
                "SpringApplication.run, @SpringBootApplication, CommandLineRunner and ApplicationRunner must not remain");
        Findings found = new Findings();
        for (Parsed p : target) {
            if (SPRING_APPLICATION_RUN.matcher(p.masked()).find()) {
                found.add(root, p, lineOf(p.masked(), SPRING_APPLICATION_RUN), "SpringApplication.run(...)");
            }
            for (AnnotationUse use : p.annotations()) {
                if (p.resolves(use, SPRING_BOOT_APPLICATION)) {
                    found.add(root, p, use);
                }
            }
            for (String imp : p.imports()) {
                if (SPRING_RUNNERS.contains(imp)) {
                    found.add(root, p, importLine(p, imp), "import " + imp);
                }
            }
        }
        result(report, rule, found, Status.FAILED, "No Spring Boot main class or runner remains",
                " must be removed or migrated to @QuarkusMain/@TopCommand: ");
    }

    /** Rule 8: every Spring bean in the source has a scoped class in the target. */
    private void checkBeanParity(ValidationReport report, Path quarkusProjectPath, boolean compat) {
        if (springProjectPath == null) {
            return;
        }
        ValidationRule rule = new ValidationRule("Bean scope parity",
                "Every Spring @Service/@Component/@Configuration class has a scoped CDI class in the target");
        Set<String> scopes = new TreeSet<>(QUARKUS_SCOPES);
        if (compat) {
            scopes.addAll(SPRING_STEREOTYPE_SIMPLE_NAMES);
        }

        List<JavaTypeDeclaration> sourceTypes = analyzer.extractTypes(springProjectPath);
        List<JavaTypeDeclaration> targetTypes = analyzer.extractTypes(quarkusProjectPath);

        Set<String> scopedTargetNames = new TreeSet<>();
        Set<String> allTargetClassNames = new TreeSet<>();
        for (JavaTypeDeclaration t : targetTypes) {
            if (isTest(t.sourcePath(), quarkusProjectPath) || t.kind() != JavaTypeKind.CLASS) {
                continue;
            }
            allTargetClassNames.add(t.simpleName());
            if (t.annotations().stream().anyMatch(a -> scopes.contains(simple(a.name())))) {
                scopedTargetNames.add(t.simpleName());
            }
        }

        List<String> unscoped = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        int beans = 0;
        for (JavaTypeDeclaration t : sourceTypes) {
            if (isTest(t.sourcePath(), springProjectPath) || t.kind() != JavaTypeKind.CLASS) {
                continue;
            }
            boolean isBean = t.annotations().stream().anyMatch(a -> isSpringBeanStereotype(t, a.name()));
            if (!isBean) {
                continue;
            }
            beans++;
            List<String> candidates = candidateNames(t.simpleName());
            if (candidates.stream().anyMatch(scopedTargetNames::contains)) {
                continue;
            }
            if (candidates.stream().anyMatch(allTargetClassNames::contains)) {
                unscoped.add(t.simpleName());
            } else {
                missing.add(t.simpleName());
            }
        }

        if (!unscoped.isEmpty()) {
            report.add(new ValidationResult(rule, Status.FAILED, "Target class has no CDI scope annotation ("
                    + String.join(", ", unscoped) + "); beans are discovered only with a scope"
                    + (missing.isEmpty() ? "" : "; no target class for: " + String.join(", ", missing))));
        } else if (!missing.isEmpty()) {
            report.add(new ValidationResult(rule, Status.WARNING, "No scoped target class for source bean(s): "
                    + String.join(", ", missing) + " (acceptable if removed on purpose, e.g. a delegating service)"));
        } else {
            report.add(
                    new ValidationResult(rule, Status.PASSED, "All " + beans + " source bean(s) have a scoped target class"));
        }
    }

    /** Rule 9b: a source CommandLineRunner/ApplicationRunner needs a @QuarkusMain or @TopCommand in the target. */
    private void checkRunnerMigrated(ValidationReport report, Path sourceRoot, List<Parsed> source, List<Parsed> target) {
        List<String> runners = new ArrayList<>();
        for (Parsed p : source) {
            if (SPRING_RUNNERS.stream().anyMatch(p::imports)) {
                runners.add(sourceRoot.relativize(p.file()).toString());
            }
        }
        if (runners.isEmpty()) {
            return;
        }
        ValidationRule rule = new ValidationRule("Runner migrated",
                "A Spring CommandLineRunner/ApplicationRunner needs a @QuarkusMain or @TopCommand class in the target");
        boolean migrated = target.stream()
                .flatMap(p -> p.annotations().stream())
                .anyMatch(u -> QUARKUS_MAIN_ANNOTATIONS.contains(u.simpleName()));
        if (migrated) {
            report.add(new ValidationResult(rule, Status.PASSED,
                    "Source runner(s) " + runners + " replaced by @QuarkusMain/@TopCommand"));
        } else {
            report.add(new ValidationResult(rule, Status.WARNING, "Source runner(s) " + runners
                    + " but no @QuarkusMain or @TopCommand in the target (startup logic may be lost)"));
        }
    }

    /** Rule 10: no scheduled method is lost. */
    private void checkScheduledParity(ValidationReport report, List<Parsed> source, List<Parsed> target) {
        ValidationRule rule = new ValidationRule("Scheduled method parity",
                "The target has at least as many scheduled methods as the source");
        long sourceCount = count(source, Set.of(SPRING_SCHEDULED));
        long targetCount = count(target, Set.of(SPRING_SCHEDULED, QUARKUS_SCHEDULED));
        if (targetCount >= sourceCount) {
            report.add(new ValidationResult(rule, Status.PASSED,
                    "Source: " + sourceCount + " scheduled method(s), target: " + targetCount));
        } else {
            report.add(new ValidationResult(rule, Status.FAILED, "Source has " + sourceCount
                    + " scheduled method(s) but target only " + targetCount + "; scheduled work was dropped"));
        }
    }

    /** Rule 12 (compat): every bridged feature still in use has its quarkus-spring-* extension in the build file. */
    private void checkCompatExtensions(ValidationReport report, BuildFile buildFile, List<Parsed> target) {
        ValidationRule rule = new ValidationRule("Spring compat extensions",
                "Each Spring feature kept in spring-compat needs its quarkus-spring-* extension in the build file");

        boolean di = target.stream().anyMatch(p -> p.annotations().stream().anyMatch(
                u -> match(p, u, SPRING_DI) != null || p.resolves(u, SPRING_CONFIGURATION) || p.resolves(u, SPRING_BEAN)));
        boolean tx = anyUse(target, SPRING_TRANSACTIONAL);
        boolean scheduled = anyUse(target, SPRING_SCHEDULED);

        if (!di && !tx && !scheduled) {
            report.add(new ValidationResult(rule, Status.PASSED, "No bridged Spring feature in use, no extension required"));
            return;
        }
        if (!buildFile.exists()) {
            report.add(new ValidationResult(rule, Status.FAILED,
                    "Build file not found (neither pom.xml nor build.gradle), cannot confirm the quarkus-spring-* extensions"));
            return;
        }

        List<String> missing = new ArrayList<>();
        List<String> present = new ArrayList<>();
        requireExtension(di, SPRING_DI_EXTENSION, "Spring DI annotations", buildFile, present, missing);
        requireExtension(tx, SPRING_TX_EXTENSION, "Spring @Transactional", buildFile, present, missing);
        requireExtension(scheduled, SPRING_SCHEDULED_EXTENSION, "Spring @Scheduled", buildFile, present, missing);

        if (!missing.isEmpty()) {
            report.add(new ValidationResult(rule, Status.FAILED, "Missing from build file: " + String.join("; ", missing)));
        } else {
            report.add(new ValidationResult(rule, Status.PASSED, "Present: " + String.join(", ", present)));
        }
    }

    private void checkJavaxLifecycle(ValidationReport report, Path root, List<Parsed> target) {
        ValidationRule rule = new ValidationRule("Jakarta lifecycle annotations",
                "javax.annotation.PostConstruct/PreDestroy must be jakarta.annotation");
        Findings found = new Findings();
        for (String name : List.of("PostConstruct", "PreDestroy")) {
            String fqn = "javax.annotation." + name;
            for (Parsed p : target) {
                for (AnnotationUse use : p.annotations()) {
                    if (p.resolves(use, fqn)) {
                        found.add(root, p, use);
                    }
                }
            }
        }
        result(report, rule, found, Status.FAILED, "No javax.annotation lifecycle annotations",
                " use the javax namespace: ");
    }

    // -------------------------------------------------------------- helpers

    /** Parses the Java files of a project, excluding {@code src/test}. Files are read through the shared project index. */
    private static List<Parsed> parseMainSources(ProjectFiles files) {
        List<Parsed> parsed = new ArrayList<>();
        for (Path file : files.find("", ".java")) {
            if (!isTest(file.toString(), files.root())) {
                parsed.add(JavaSourceText.parse(file, files.read(file)));
            }
        }
        return parsed;
    }

    /** Excludes {@code src/test} relative to the project root, so a root that itself lives under a test dir still works. */
    private static boolean isTest(String file, Path root) {
        if (file == null) {
            return false;
        }
        String path = file.replace('\\', '/');
        String base = root.toString().replace('\\', '/');
        if (path.startsWith(base)) {
            path = path.substring(base.length());
        }
        return ("/" + path).contains("/src/test/");
    }

    /** Returns the matching FQN from {@code fqns} that {@code use} resolves to, or null. */
    private static String match(Parsed p, AnnotationUse use, Set<String> fqns) {
        if (use.qualified()) {
            return fqns.contains(use.name()) ? use.name() : null;
        }
        for (String fqn : fqns) {
            if (fqn.endsWith("." + use.simpleName()) && p.imports(fqn)) {
                return fqn;
            }
        }
        return null;
    }

    private static boolean isSpringConditional(Parsed p, AnnotationUse use) {
        if (!use.simpleName().startsWith("Conditional")) {
            return false;
        }
        for (String pkg : CONDITIONAL_PACKAGES) {
            if (use.qualified() ? use.name().equals(pkg + "." + use.simpleName()) : p.imports(pkg + "." + use.simpleName())) {
                return true;
            }
        }
        return false;
    }

    private static void forEach(List<Parsed> files, String fqn, java.util.function.BiConsumer<Parsed, AnnotationUse> action) {
        for (Parsed p : files) {
            for (AnnotationUse use : p.annotations()) {
                if (p.resolves(use, fqn)) {
                    action.accept(p, use);
                }
            }
        }
    }

    private static boolean anyUse(List<Parsed> files, String fqn) {
        return files.stream().anyMatch(p -> p.annotations().stream().anyMatch(u -> p.resolves(u, fqn)));
    }

    private static long count(List<Parsed> files, Set<String> fqns) {
        return files.stream()
                .flatMap(p -> p.annotations().stream().filter(u -> match(p, u, fqns) != null))
                .count();
    }

    private static void requireExtension(boolean inUse, Dependency extension, String feature, BuildFile buildFile,
            List<String> present, List<String> missing) {
        if (!inUse) {
            return;
        }
        if (buildFile.hasDependency(extension)) {
            present.add(extension.artifactId());
        } else {
            missing.add(extension.artifactId() + " (needed for " + feature + ")");
        }
    }

    /** Source-side check using the extractor: the annotation name may be simple or, with JavaParser, qualified. */
    private static boolean isSpringBeanStereotype(JavaTypeDeclaration type, String annotationName) {
        if (annotationName == null || annotationName.isEmpty()) {
            return false;
        }
        if (annotationName.contains(".")) {
            return SPRING_BEAN_STEREOTYPES.contains(annotationName);
        }
        for (String fqn : SPRING_BEAN_STEREOTYPES) {
            if (fqn.endsWith("." + annotationName) && type.importMap().containsValue(fqn)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> candidateNames(String simpleName) {
        return simpleName.endsWith("Impl") && simpleName.length() > 4
                ? List.of(simpleName, simpleName.substring(0, simpleName.length() - 4))
                : List.of(simpleName);
    }

    private static String simple(String name) {
        if (name == null) {
            return "";
        }
        return name.substring(name.lastIndexOf('.') + 1);
    }

    private static int importLine(Parsed p, String imp) {
        java.util.regex.Matcher m = Pattern.compile("\\bimport\\s+" + Pattern.quote(imp) + "\\s*;").matcher(p.code());
        return m.find() ? lineAt(p.code(), m.start()) : 0;
    }

    private static int lineOf(String text, Pattern pattern) {
        java.util.regex.Matcher m = pattern.matcher(text);
        return m.find() ? lineAt(text, m.start()) : 0;
    }

    private static int lineAt(String text, int offset) {
        int line = 1;
        for (int i = 0; i < offset; i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    /**
     * Adds a result for {@code rule}: PASSED with {@code passMessage} when nothing was found, otherwise
     * {@code failStatus} with a count, {@code reason} and the first findings.
     */
    private static void result(ValidationReport report, ValidationRule rule, Findings found, Status failStatus,
            String passMessage, String reason) {
        if (found.isEmpty()) {
            report.add(new ValidationResult(rule, Status.PASSED, passMessage));
        } else {
            report.add(new ValidationResult(rule, failStatus, found.size() + " occurrence(s)" + reason + found.describe()));
        }
    }

    private static final class Findings {

        private final List<String> items = new ArrayList<>();

        void add(Path root, Parsed p, AnnotationUse use) {
            add(root, p, use.line(), "@" + use.simpleName());
        }

        void add(Path root, Parsed p, int line, String what) {
            items.add(root.relativize(p.file()) + ":" + line + " " + what);
        }

        boolean isEmpty() {
            return items.isEmpty();
        }

        int size() {
            return items.size();
        }

        String describe() {
            String shown = String.join("; ", items.subList(0, Math.min(items.size(), MAX_EVIDENCE_ITEMS)));
            int more = items.size() - MAX_EVIDENCE_ITEMS;
            return more > 0 ? shown + " (+" + more + " more)" : shown;
        }
    }
}
