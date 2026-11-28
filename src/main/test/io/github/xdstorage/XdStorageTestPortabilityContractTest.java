package io.github.xdstorage;

import io.github.xdstorage.helpers.StoragePaths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T-PORT: the tests spell no file separator. Three Windows runs of the suite (2026-11-27,
 * 2026-11-28) lost tests to one habit — a path's string form tested against a slash literal,
 * green on Linux and red on Windows, where {@code Path} says {@code \}. The habit is cheap to
 * write and invisible until the other leg runs; this contract refuses it at home.
 *
 * <p>What it sees: the direct spellings — {@code path.toString().contains("/…")},
 * {@code split("/")}, {@code indexOf('/')}. A path normalized on the same line is left alone;
 * so are comments. What it does not look at: a {@code "/"} INSIDE a built path
 * ({@code folder + "/" + name}) — Windows accepts the slash there, only a comparison breaks.
 * What it cannot see: a path concatenated into an expected string
 * ({@code sources.relativize(file) + ":"} against {@code "io/github/…"}) — that one is caught
 * only by reading, or by Windows.</p>
 */
@DisplayName("T-PORT: тесты не пишут разделитель буквами — что зелёное на Linux, зелёное и на Windows")
public class XdStorageTestPortabilityContractTest {

    /** This file: its own pattern would match its own text. */
    private static final String SELF = "XdStorageTestPortabilityContractTest.java";

    private static final Pattern RAW_SEPARATOR = Pattern.compile(
            "\\.(toString|getPath|getAbsolutePath|getCanonicalPath)\\(\\)\\s*\\.\\s*"
                    + "(contains|startsWith|endsWith|indexOf|lastIndexOf)\\(\"/"
                    + "|split\\(\"/\"\\)|indexOf\\('/'\\)|lastIndexOf\\('/'\\)");

    @Test
    @DisplayName("ни один тест не проверяет путь строкой с '/' внутри")
    public void noTestSpellsTheSeparatorLiterally() throws IOException {
        final Path tests = Paths.get("src/main/test").toAbsolutePath();
        assertTrue(Files.isDirectory(tests), "the test sources must be reachable from the run directory: "
                + tests);
        final List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(tests)) {
            for (final Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
                if (file.getFileName().toString().equals(SELF)) {
                    continue;
                }
                final List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    final String line = lines.get(i);
                    final String code = line.trim();
                    if (code.startsWith("//") || code.startsWith("/*") || code.startsWith("*")) {
                        continue;   // a comment may spell anything
                    }
                    if (line.contains("replace(")) {
                        continue;   // normalized on this very line
                    }
                    if (RAW_SEPARATOR.matcher(line).find()) {
                        offenders.add(StoragePaths.slashes(tests.relativize(file)) + ":" + (i + 1) + "  " + code);
                    }
                }
            }
        }
        assertTrue(offenders.isEmpty(), "a path is tested against a slash literal — green on Linux, red on Windows,"
                + " where Path says a backslash. Spell it with StoragePaths.slashes(...) or"
                + " StoragePaths.hasElement(...), or build the expectation as a Path:\n"
                + String.join("\n", offenders));
    }
}
