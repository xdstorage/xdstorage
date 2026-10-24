package io.github.xdstorage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T-2 (docs/tasks/2.1.0/05): the naming↔tag grouping contract, enforced in both directions.
 *
 * A stress/integration class is BOTH named per convention AND tagged:
 *   - a class named {@code *StressTest} must carry the stress tag;
 *   - a class under {@code /integration/} must carry the integration tag;
 *   - a class carrying the stress tag must be named {@code *StressTest};
 *   - a class carrying the integration tag must live under {@code /integration/}.
 *
 * Without the reverse direction a tag would silently exclude a misnamed class from the
 * release gate (the defect this task exists to close); without the forward direction a
 * renamed tagless stress test would silently rejoin it. Source-walking over
 * {@code src/main/test} (resolved against the working directory — the surefire and IDE
 * default at the repository root) keeps the check dependency-free. The tag literals in
 * this file are assembled from pieces precisely so the reverse-direction scan of this
 * very source never matches itself.
 */
@DisplayName("T-2: stress/integration naming↔tag pairing contract")
public class XdStorageTestGroupingContractTest {

    // Assembled from pieces so this source file never literally contains a tag annotation
    // — the reverse-direction scan of this very source never matches itself.
    private static final String STRESS_TAG = "@" + "Tag" + "(\"" + "stress" + "\")";
    private static final String INTEGRATION_TAG = "@" + "Tag" + "(\"" + "integration" + "\")";

    /**
     * Legacy names carrying the stress tag without the *StressTest suffix — grandfathered
     * with rationale (the FLAKY.md ledger, the pom backstop patterns and the task's own
     * scope all reference these exact names; a rename would churn all three for no
     * semantic gain). Every entry here is a decision, not an oversight: extend the list
     * only with a comment saying why the name cannot change.
     */
    private static final java.util.Set<String> STRESS_TAG_NAMING_EXCEPTIONS =
            new java.util.HashSet<>(java.util.Arrays.asList(
                    // pre-2.0 multithread XML stress; name frozen by the ledger + backstop patterns
                    "XmlDataStorageMultithreadsTest",
                    // the YAML twin of the same pre-2.0 pair (added with the pair, same
                    // ledger/backstop rationale as its XML sibling)
                    "YamlDataStorageMultithreadsTest"));

    @Test
    public void stressNamingAndTagArePairedInBothDirections() throws IOException {
        final List<String> violations = new ArrayList<>();
        try (Stream<Path> sources = Files.walk(testRoot())) {
            sources.filter(p -> p.toString().endsWith("Test.java")).forEach(p -> {
                final String fileName = p.getFileName().toString();
                final String className = fileName.substring(0, fileName.length() - ".java".length());
                final String text;
                try {
                    // release=8: Files.readString is Java 11+
                    text = new String(Files.readAllBytes(p), java.nio.charset.StandardCharsets.UTF_8);
                } catch (final IOException e) {
                    violations.add(p + ": unreadable (" + e + ")");
                    return;
                }
                final boolean stressNamed = className.endsWith("StressTest");
                final boolean inIntegrationPackage = p.toString().replace('\\', '/').contains("/integration/");
                final boolean taggedStress = text.contains(STRESS_TAG);
                final boolean taggedIntegration = text.contains(INTEGRATION_TAG);

                if (stressNamed && !taggedStress) {
                    violations.add(className + ": named *StressTest but has no " + STRESS_TAG
                            + " — it silently rejoins the release gate (add the tag)");
                }
                if (inIntegrationPackage && !taggedIntegration) {
                    violations.add(className + ": lives in the integration package but has no "
                            + INTEGRATION_TAG + " (add the tag)");
                }
                if (taggedStress && !stressNamed && !STRESS_TAG_NAMING_EXCEPTIONS.contains(className)) {
                    violations.add(className + ": carries " + STRESS_TAG
                            + " but is not named *StressTest — the release gate excludes it by tag while the"
                            + " name-based backstop does not; rename the class or drop the tag");
                }
                if (taggedIntegration && !inIntegrationPackage) {
                    violations.add(className + ": carries " + INTEGRATION_TAG
                            + " but does not live in the integration package; move it or drop the tag");
                }
            });
        }
        assertTrue(violations.isEmpty(),
                "T-2 grouping contract violations (" + violations.size() + "):\n  " + String.join("\n  ", violations));
    }

    private static Path testRoot() {
        final Path root = Paths.get("src", "main", "test");
        assertTrue(Files.isDirectory(root),
                "test source root not found at " + root.toAbsolutePath()
                        + " — the contract test must run with the repository root (or module dir) as the working directory");
        return root;
    }
}
