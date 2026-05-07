package de.seism0saurus.glacier.mastodon;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test that pins the {@link SubscriptionManagerImpl} keep-alive invariant.
 *
 * <p>The Bigbone streaming connection is kept open by a {@code Thread.sleep(60_000L)} loop
 * in the virtual thread.  Any change to a different blocking primitive (e.g.
 * {@code Object.wait()}, {@code LockSupport.parkNanos}, a {@code CountDownLatch}) causes
 * Bigbone to close the stream immediately — a silent, hard-to-debug regression (P1-07).
 *
 * <p>This test reads the production source of {@code SubscriptionManagerImpl} and asserts:
 * <ol>
 *   <li>The {@code sleepForever} method body contains a call to {@code Thread.sleep}.</li>
 *   <li>No non-comment code line in {@code sleepForever} uses forbidden blocking alternatives.</li>
 * </ol>
 *
 * <p>The test is a Failsafe {@code *IT} class and does NOT require a Spring context — it
 * operates purely on the source file, making it fast and deterministic.
 *
 * <p>Arrange: locate {@code SubscriptionManagerImpl.java} relative to the Maven project root.<br>
 * Act: extract the {@code sleepForever} method body and scan it.<br>
 * Assert: {@code Thread.sleep(} present; forbidden patterns absent from non-comment code lines.
 */
class BigboneKeepAliveIT {

    /**
     * Blocking primitives that must NOT appear as executable code in the keep-alive
     * method. If any of these replace {@code Thread.sleep}, the Bigbone stream closes silently.
     */
    private static final List<String> FORBIDDEN_CODE_PATTERNS = List.of(
            "LockSupport.parkNanos",
            "LockSupport.park(",
            "CountDownLatch",
            "Phaser"
    );

    /**
     * Relative path to the production source file from the Maven project root
     * ({@code ${basedir}}).
     */
    private static final String RELATIVE_SOURCE_PATH =
            "src/main/java/de/seism0saurus/glacier/mastodon/SubscriptionManagerImpl.java";

    /**
     * Pattern to identify comment-only lines (lines whose non-whitespace content
     * starts with {@code //} or {@code *}). This avoids false positives from Javadoc
     * that references the forbidden patterns by name.
     */
    private static final Pattern COMMENT_LINE = Pattern.compile("^\\s*(//|\\*)");

    @Test
    void sleepForeverMethodUsesThreadSleepNotAnyOtherBlockingPrimitive() throws IOException {
        Path sourceFile = resolveSourceFile();
        List<String> lines = Files.readAllLines(sourceFile);

        // Locate the sleepForever method body
        String sleepForeverBody = extractSleepForeverBody(lines);

        // Assert Thread.sleep is present (the intentional keep-alive mechanism)
        assertThat(sleepForeverBody)
                .as("sleepForever in SubscriptionManagerImpl must keep the Bigbone stream alive with " +
                        "Thread.sleep — any other blocking primitive closes the stream immediately (P1-07)")
                .contains("Thread.sleep(");

        // Extract non-comment code lines from the method body
        List<String> codeLines = lines.stream()
                .filter(line -> !COMMENT_LINE.matcher(line).find())
                .toList();

        String codeOnlySource = String.join("\n", codeLines);

        // Assert none of the forbidden alternatives appear in code (not just in comments)
        for (String forbidden : FORBIDDEN_CODE_PATTERNS) {
            assertThat(codeOnlySource)
                    .as("SubscriptionManagerImpl must NOT use '%s' anywhere in the source — " +
                            "it would cause Bigbone to close the stream if used as keep-alive (P1-07)", forbidden)
                    .doesNotContain(forbidden);
        }
    }

    /**
     * Extracts the text of the {@code sleepForever} method body from the source lines.
     *
     * <p>Locates the line containing {@code private static void sleepForever} and
     * collects all subsequent lines up to and including the closing brace, respecting
     * brace nesting depth.
     *
     * @param lines all lines of the source file
     * @return the method body as a single string
     */
    private static String extractSleepForeverBody(List<String> lines) {
        int start = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains("private static void sleepForever")) {
                start = i;
                break;
            }
        }

        assertThat(start)
                .as("sleepForever method must exist in SubscriptionManagerImpl (P1-07 invariant)")
                .isGreaterThanOrEqualTo(0);

        StringBuilder body = new StringBuilder();
        int depth = 0;
        boolean inMethod = false;
        for (int i = start; i < lines.size(); i++) {
            String line = lines.get(i);
            body.append(line).append("\n");
            for (char c : line.toCharArray()) {
                if (c == '{') {
                    depth++;
                    inMethod = true;
                } else if (c == '}') {
                    depth--;
                    if (inMethod && depth == 0) {
                        return body.toString();
                    }
                }
            }
        }

        return body.toString();
    }

    /**
     * Resolves the source file path from the Maven project root.
     *
     * <p>Maven sets {@code user.dir} to the project root during build execution, which
     * is the reliable anchor point for locating source files in a Maven project.
     */
    private static Path resolveSourceFile() {
        Path projectRoot = Paths.get(System.getProperty("user.dir"));
        Path sourceFile = projectRoot.resolve(RELATIVE_SOURCE_PATH);

        assertThat(sourceFile)
                .as("Source file %s must exist — ensure the test is run from the Maven project root", sourceFile)
                .exists()
                .isReadable();

        return sourceFile;
    }
}
