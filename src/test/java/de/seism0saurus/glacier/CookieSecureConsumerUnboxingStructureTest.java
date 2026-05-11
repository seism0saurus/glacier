package de.seism0saurus.glacier;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Structural test enforcing the safe unboxing idiom in all {@code glacier.cookie.secure}
 * consumers (ADR-P3B-3, SR-P3B-04a).
 *
 * <h2>Why this test exists</h2>
 * <p>{@link GlacierCookieProperties#getSecure()} returns {@link Boolean} (boxed). In test
 * contexts where the bean is mocked, {@code getSecure()} may return {@code null}. Calling
 * {@code .booleanValue()} on a null {@link Boolean} throws {@link NullPointerException}.
 *
 * <p>The mandated idiom is {@code Boolean.TRUE.equals(props.getSecure())} — this is
 * null-safe (evaluates to {@code false} when {@code getSecure()} returns null) and
 * correctly handles all three states: {@code true}, {@code false}, and null
 * (production startup prevented null by {@code @NotNull}).
 *
 * <p>References: ADR-P3B-3; SR-P3B-04; SR-P3B-04a.
 */
class CookieSecureConsumerUnboxingStructureTest {

    /**
     * Pattern detecting the forbidden {@code .getSecure().booleanValue()} unboxing call.
     * Any call to {@code booleanValue()} on the result of {@code getSecure()} is unsafe
     * (SR-P3B-04a, ADR-P3B-3).
     */
    private static final Pattern UNSAFE_UNBOXING_PATTERN =
            Pattern.compile("\\.getSecure\\(\\)\\.booleanValue\\(\\)");

    /**
     * The 7 known consumer class filenames that must use the safe unboxing idiom.
     */
    private static final Set<String> CONSUMER_FILENAMES = Set.of(
            "InformationController.java",
            "WebSocketConfiguration.java",
            "CsrfTokenCookieFactory.java",
            "ShareViewerCookieFactory.java",
            "ShareCsrfGuard.java",
            "ImageProxyHmacSecretValidator.java",
            "StartupSanityChecker.java"
    );

    /**
     * Arrange: walk {@code src/main/java} for the 7 known consumer filenames
     * Act: check each file for the unsafe {@code .getSecure().booleanValue()} pattern
     * Assert: no file contains the unsafe unboxing call
     *
     * <p>Consumers must use {@code Boolean.TRUE.equals(props.getSecure())} instead.
     * Assigning to a primitive local ({@code boolean x = Boolean.TRUE.equals(...)}) is
     * also acceptable.
     */
    @Test
    void noConsumer_usesUnsafeGetSecureBooleanValueUnboxing() throws IOException {
        Path mainRoot = Path.of("src/main/java").toAbsolutePath();
        List<String> unsafeConsumers = new ArrayList<>();

        try (Stream<Path> paths = Files.walk(mainRoot)) {
            paths.filter(Files::isRegularFile)
                    .filter(p -> CONSUMER_FILENAMES.contains(p.getFileName().toString()))
                    .forEach(p -> {
                        try {
                            String content = Files.readString(p);
                            if (UNSAFE_UNBOXING_PATTERN.matcher(content).find()) {
                                unsafeConsumers.add(p.getFileName().toString());
                            }
                        } catch (IOException e) {
                            throw new RuntimeException("Failed to read: " + p, e);
                        }
                    });
        }

        assertThat(unsafeConsumers)
                .as("These consumer files use .getSecure().booleanValue() — this is unsafe: "
                        + "getSecure() may return null in test mocking paths, causing NPE. "
                        + "Use Boolean.TRUE.equals(props.getSecure()) instead "
                        + "(ADR-P3B-3, SR-P3B-04, SR-P3B-04a). "
                        + "Violating files: %s", unsafeConsumers)
                .isEmpty();
    }
}
