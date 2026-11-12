package dev.snowdrop.mtool.validate;

import java.util.Arrays;
import java.util.Optional;

/**
 * The migration strategy chosen for the project. The caller supplies it; validators never read it from disk.
 */
public enum MigrationStrategy {

    FULL_QUARKUS("full-quarkus"),
    SPRING_COMPAT("spring-compat");

    private final String value;

    MigrationStrategy(String value) {
        this.value = value;
    }

    /** The external name, as used in the {@code strategy} tool argument and CLI option. */
    public String value() {
        return value;
    }

    public static Optional<MigrationStrategy> parse(String text) {
        if (text == null) {
            return Optional.empty();
        }
        String trimmed = text.trim();
        return Arrays.stream(values()).filter(s -> s.value.equalsIgnoreCase(trimmed)).findFirst();
    }
}
