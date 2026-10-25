/**
 * The performance/mixed-content package (T-3b, docs/tasks/2.1.0/07). Four kinds of content
 * live here, and the naming convention is what keeps them straight:
 *
 * <ul>
 *   <li><b>Stress families</b> ({@code *StressTest}, tagged {@code @Tag("stress")} per
 *       task 05) — concurrency and isolation stress; excluded from the release gate by
 *       tag, run by the default {@code mvn test}.</li>
 *   <li><b>Genuine unit tests</b> ({@code XdStorageBTreeGenerationStrategyTest},
 *       {@code XdStorageObjectGraphPolicyAndIndexTest}, {@code YamlRoundTripTest}) —
 *       deterministic, always run.</li>
 *   <li><b>Diagnostics</b> ({@code GraphFlowDiagnosticTest}) — a focused
 *       where-does-the-key-get-lost repro for the save→load id-index flow; run it when
 *       investigating index-routing regressions.</li>
 *   <li><b>Benchmarks and sweeps</b> ({@code XdStorageCanonicalBenchmark},
 *       {@code XdStoragePerformanceBenchmark}, {@code XdStorageResourceCacheBenchmark},
 *       {@code XdStorageSearchEngineBenchmark}, {@code XdStorageDesignPointProbe},
 *       {@code sweep/}) — classes WITHOUT the {@code Test} suffix are invisible to
 *       surefire by design; they run via {@code scripts/run-benchmarks.sh} (JMH) or as
 *       main() sweeps, and their numbers live in docs/BENCHMARKS.md.</li>
 * </ul>
 *
 * <p>The multithreads stress pair ({@code Xml/YamlDataStorageMultithreadsTest}) doubles
 * as the recursive leftover-file leak detector for the file-leak family
 * (docs/tasks/2.1.0/41).
 */
package io.github.xdstorage.performance;
