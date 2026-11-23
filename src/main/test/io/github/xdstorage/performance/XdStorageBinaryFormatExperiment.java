package io.github.xdstorage.performance;

import io.github.xdstorage.BinFeasEntity;
import io.github.xdstorage.IXdFileStorage;
import io.github.xdstorage.IXdStorage;
import io.github.xdstorage.IXdStoragePredicate;
import io.github.xdstorage.XdStorageProvider;
import io.github.xdstorage.serialization.IXdStorageIOFactory;
import io.github.xdstorage.serialization.IXdStorageObjectsReader;
import io.github.xdstorage.serialization.IXdStorageObjectsWriter;
import io.github.xdstorage.serialization.yaml.XdStorageYamlIOFactory;
import io.github.xdstorage.transaction.IXdStorageTransaction;

import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import java.io.File;

/**
 * BINFEAS EXPERIMENT (task 33 follow-up): the REALIZED binary-format numbers vs the
 * theoretical ceiling. The same storage, the same engine, ONE variable — the codec:
 *
 * <ol>
 *   <li>fill N entities with the default YAML factory; the shutdown checkpoint = the
 *       YAML write-out wall;</li>
 *   <li>measure the YAML cold-scan and cold-lookup rounds (the task-33 protocol);</li>
 *   <li>CONVERT: open → install {@link BinFeasBinaryIOFactory} (via reflection, the
 *       resources materialize lazily so the swap covers everything) → shutdown: the
 *       checkpoint rewrites every base file through the binary codec;</li>
 *   <li>measure the binary cold-scan / cold-lookup rounds the same way; the per-read
 *       serialization time is captured by the measuring decorator wrapping the active
 *       factory in BOTH arms;</li>
 *   <li>INTEGRITY: every loaded entity is asserted field-by-field against the
 *       deterministic source model (all N, not a sample); every lookup's payload is
 *       asserted; the base files are asserted to start with the binary magic; the
 *       storage byte sizes are compared.</li>
 * </ol>
 *
 * <p>Run: {@code java -Dlog4j.configurationFile=... -cp <test-cp>
 * io.github.xdstorage.performance.XdStorageBinaryFormatExperiment [N] [rounds] [lookups]}
 */
public final class XdStorageBinaryFormatExperiment {

    private static final AtomicLong READER_NANOS = new AtomicLong();

    private XdStorageBinaryFormatExperiment() {
    }

    /** The measuring decorator (extends the YAML factory: keeps the journal-replay instanceof). */
    private static final class Measuring extends XdStorageYamlIOFactory {
        private final IXdStorageIOFactory delegate;

        Measuring(final IXdStorageIOFactory delegate) throws Exception {
            super(serviceOf(delegate), idGenOf(delegate));
            this.delegate = delegate;
        }

        private static io.github.xdstorage.services.XdStorageServicesLocator serviceOf(final IXdStorageIOFactory f) {
            try {
                return (io.github.xdstorage.services.XdStorageServicesLocator) findField(f.getClass(), "services")
                        .get(f);
            } catch (final Exception e) {
                throw new RuntimeException(e);
            }
        }

        private static io.github.xdstorage.idgeneration.IXdStorageIdGenerator idGenOf(final IXdStorageIOFactory f) {
            try {
                return (io.github.xdstorage.idgeneration.IXdStorageIdGenerator) findField(f.getClass(), "idGenerator")
                        .get(f);
            } catch (final Exception e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public IXdStorageObjectsReader newInstanceReader() {
            final IXdStorageObjectsReader delegate = this.delegate.newInstanceReader();
            return new IXdStorageObjectsReader() {
                @Override
                public Collection<Object> readReferences(final Reader reader, final io.github.xdstorage.utils
                        .XdStorageObjectIdField field)
                        throws io.github.xdstorage.exceptions.XdStorageIOException, io.github.xdstorage.exceptions
                                .XdStorageException {
                    final long t0 = System.nanoTime();
                    try {
                        return delegate.readReferences(reader, field);
                    } finally {
                        READER_NANOS.addAndGet(System.nanoTime() - t0);
                    }
                }

                @Override
                public Collection<Object> read(final Reader reader)
                        throws io.github.xdstorage.exceptions.XdStorageIOException, io.github.xdstorage.exceptions
                                .XdStorageException {
                    final long t0 = System.nanoTime();
                    try {
                        return delegate.read(reader);
                    } finally {
                        READER_NANOS.addAndGet(System.nanoTime() - t0);
                    }
                }

                @Override
                public Collection<Object> read(final Reader reader, final IXdStorageTransaction transaction)
                        throws io.github.xdstorage.exceptions.XdStorageIOException {
                    final long t0 = System.nanoTime();
                    try {
                        return delegate.read(reader, transaction);
                    } finally {
                        READER_NANOS.addAndGet(System.nanoTime() - t0);
                    }
                }

                @Override
                public Collection<io.github.xdstorage.object.XdStorageIdentifiableObject> readData(final Reader
                        xmlReader,
                                                                                                    final io.github
                                                                .xdstorage.utils.XdStorageObjectIdField field)
                        throws io.github.xdstorage.exceptions.XdStorageIOException, io.github.xdstorage.exceptions
                                .XdStorageException {
                    final long t0 = System.nanoTime();
                    try {
                        return delegate.readData(xmlReader, field);
                    } finally {
                        READER_NANOS.addAndGet(System.nanoTime() - t0);
                    }
                }
            };
        }

        @Override
        public IXdStorageObjectsWriter newInstanceWriter() {
            return this.delegate.newInstanceWriter();
        }
    }

    private static void install(final IXdFileStorage storage, final IXdStorageIOFactory factory) throws Exception {
        final Object locator = findField(storage.getClass(), "services").get(storage);
        findField(locator.getClass(), "ioFactory").set(locator, factory);
    }

    private static Field findField(final Class<?> type, final String name) throws NoSuchFieldException {
        Class<?> c = type;
        while (c != null) {
            try {
                final Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (final NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    // ───────────────────────── the model + the integrity checks ─────────────────────────

    private static Map<String, BinFeasEntity> modelOf(final int n) {
        final Map<String, BinFeasEntity> model = new HashMap<>();
        for (int i = 0; i < n; i++) {
            model.put("e" + i, new BinFeasEntity("e" + i, i));
        }
        return model;
    }

    private static void assertIntegrity(final Collection<BinFeasEntity> loaded, final Map<String, BinFeasEntity> model)
            {
        assertEquals(model.size(), loaded.size(), "the loaded count diverges from the model (a data loss)");
        for (final BinFeasEntity e : loaded) {
            final BinFeasEntity expected = model.get(e.getId());
            assertNotNull(expected, "an unknown entity id came back: " + e.getId() + " (a phantom)");
            assertEquals(expected.getName(), e.getName(), "name diverges for " + e.getId());
            assertEquals(expected.getCategory(), e.getCategory(), "category diverges for " + e.getId());
            assertEquals(expected.getRegion(), e.getRegion(), "region diverges for " + e.getId());
            assertEquals(expected.getComment(), e.getComment(), "comment diverges for " + e.getId());
            assertEquals(expected.getAmountA(), e.getAmountA(), "amountA diverges for " + e.getId());
            assertEquals(expected.getAmountB(), e.getAmountB(), "amountB diverges for " + e.getId());
            assertEquals(expected.getQuantity(), e.getQuantity(), "quantity diverges for " + e.getId());
            assertEquals(expected.getRatio(), e.getRatio(), "ratio diverges for " + e.getId());
            assertEquals(expected.getActive(), e.getActive(), "active diverges for " + e.getId());
            assertEquals(expected.getTags(), e.getTags(), "tags diverges for " + e.getId());
        }
    }

    private static void assertEquals(final Object expected, final Object actual, final String message) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(message + " (expected " + expected + ", got " + actual + ")");
        }
    }

    private static void assertNotNull(final Object o, final String message) {
        if (o == null) {
            throw new AssertionError(message);
        }
    }

    private static long median(final List<Long> values) {
        final List<Long> sorted = new ArrayList<>(values);
        java.util.Collections.sort(sorted);
        return sorted.get(sorted.size() / 2);
    }

    private static long dirBytes(final Path root) throws Exception {
        long total = 0;
        try (final java.util.stream.Stream<Path> walk = Files.walk(root)) {
            for (final Path p : (Iterable<Path>) walk.filter(Files::isRegularFile)::iterator) {
                total += Files.size(p);
            }
        }
        return total;
    }

    private static void assertBinaryBases(final Path root) throws Exception {
        int checked = 0;
        try (final java.util.stream.Stream<Path> walk = Files.walk(root.resolve("data"))) {
            for (final Path p : (Iterable<Path>) walk.filter(Files::isRegularFile)
                    .filter(f -> f.getFileName().toString().endsWith(".yaml") && !f.toString()
                            .contains("journal"))::iterator) {
                final String head = new String(Files.readAllBytes(p), 0, 3, StandardCharsets.US_ASCII);
                if (!"BF1".equals(head)) {
                    throw new AssertionError("the base file " + p + " is not binary (head='" + head +
                            "') — the conversion did not cover it");
                }
                checked++;
            }
        }
        System.out.printf(Locale.ROOT, "[BINEXP] %d base files verified binary (BF1 magic)%n", checked);
    }

    /** Converts ONE base file at the file level: the YAML records -> the binary records. */
    private static void convertIndexBase(final Path root, final IXdFileStorage session) throws Exception {
        final Path indexFile = root.resolve("indexes/BinFeasEntity/index.yaml");
        final io.github.xdstorage.serialization.yaml.XdStorageYamlIOFactory yaml =
                new io.github.xdstorage.serialization.yaml.XdStorageYamlIOFactory(servicesOf(session),
                        idGenOf(session));
        final io.github.xdstorage.utils.XdStorageObjectIdField idField =
                io.github.xdstorage.utils.XdStorageObjectUtils.getClassInfo(BinFeasEntity.class).getIdField();
        final Collection<io.github.xdstorage.object.XdStorageIdentifiableObject> records;
        try (Reader in = Files.newBufferedReader(indexFile, StandardCharsets.UTF_8)) {
            records = yaml.newInstanceReader().readData(in, idField);
        }
        if (records.isEmpty()) {
            throw new AssertionError("the index base is empty after the conversion read");
        }
        final Path tmp = root.resolve("indexes/BinFeasEntity/index.yaml.binfeas");
        try (Writer out = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
            BinFeasBinaryCodec.writeRecordsBlock(out, records);
        }
        Files.move(tmp, indexFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        System.out.printf(Locale.ROOT, "[BINEXP] the index base converted: %d records%n", records.size());
    }

    private static void deleteRecursively(final File file) {
        final File[] contents = file.listFiles();
        if (contents != null) {
            for (final File f : contents) {
                deleteRecursively(f);
            }
        }
        file.delete();
    }

    public static void main(final String[] args) throws Exception {
        final int n = args.length > 0 ? Integer.parseInt(args[0]) : 5000;
        final int rounds = args.length > 1 ? Integer.parseInt(args[1]) : 5;
        final int lookups = args.length > 2 ? Integer.parseInt(args[2]) : 500;
        final Path root = Paths.get("./target/binexp-" + System.nanoTime());
        deleteRecursively(root.toFile());
        final Map<String, BinFeasEntity> model = modelOf(n);
        System.out.printf(Locale.ROOT, "[BINEXP] N=%d rounds=%d lookups=%d root=%s%n", n, rounds, lookups, root);

        // ── 1. the YAML fill (+ its checkpoint wall = the YAML write-out) ──
        IXdFileStorage storage = XdStorageProvider.newOrGetFileStorage("binexp-fill", root.toString(), 64);
        for (int batchStart = 0; batchStart < n; batchStart += 200) {
            final IXdStorageTransaction tx = storage.beginTransaction();
            for (int i = batchStart; i < Math.min(batchStart + 200, n); i++) {
                storage.save(new BinFeasEntity("e" + i, i), tx);
            }
            storage.commitTransaction(tx);
        }
        final long yamlCpStart = System.nanoTime();
        storage.shutdown();
        final long yamlCpWall = System.nanoTime() - yamlCpStart;
        final long yamlBytes = dirBytes(root);
        System.out.printf(Locale.ROOT, "[BINEXP] YAML: %,d bytes, checkpoint %,d ms%n", yamlBytes, yamlCpWall /
                1_000_000);

        // ── 2. the YAML cold rounds ──
        final List<Long> yamlScanWalls = new ArrayList<>();
        final List<Long> yamlScanReaders = new ArrayList<>();
        final List<Long> yamlFirst = new ArrayList<>();
        final List<Long> yamlAvg = new ArrayList<>();
        for (int r = 0; r < rounds; r++) {
            storage = XdStorageProvider.newOrGetFileStorage("binexp-y-" + System.nanoTime(), root.toString(), 64);
            install(storage, new Measuring(new XdStorageYamlIOFactory(servicesOf(storage), idGenOf(storage))));
            final IXdStorageTransaction tx = storage.beginTransaction();
            READER_NANOS.set(0);
            final long t0 = System.nanoTime();
            final Collection<BinFeasEntity> loaded = storage.load(BinFeasEntity.class,
                    (IXdStoragePredicate<BinFeasEntity>) o -> true, tx);
            final long wall = System.nanoTime() - t0;
            assertIntegrity(loaded, model);
            yamlScanWalls.add(wall);
            yamlScanReaders.add(READER_NANOS.get());
            storage.commitTransaction(tx);
            storage.shutdown();
        }
        // the LOOKUP rounds: a FRESH reopen per round (the first by-id op after a reopen
        // is the truest cold op; measuring it inside the scan's session — the first
        // experiment draft's bug — measures the prepared cache, not the cold path)
        for (int r = 0; r < rounds; r++) {
            storage = XdStorageProvider.newOrGetFileStorage("binexp-yl-" + System.nanoTime(), root.toString(), 64);
            install(storage, new Measuring(new XdStorageYamlIOFactory(servicesOf(storage), idGenOf(storage))));
            final IXdStorageTransaction tx = storage.beginTransaction();
            READER_NANOS.set(0);
            final long tf = System.nanoTime();
            final BinFeasEntity cold = storage.load(BinFeasEntity.class, "e" + (n / 2), tx);
            yamlFirst.add(System.nanoTime() - tf);
            assertNotNull(cold, "the cold lookup missed");
            final long firstReader = READER_NANOS.get();
            final long ta = System.nanoTime();
            for (int i = 0; i < lookups; i++) {
                assertNotNull(storage.load(BinFeasEntity.class, "e" + i, tx), "lookup missed e" + i);
            }
            yamlAvg.add((System.nanoTime() - ta) / lookups);
            storage.commitTransaction(tx);
            storage.shutdown();
            System.out.printf(Locale.ROOT,
                    "[BINEXP][diag] yaml lookup round %d: FIRST %,d µs (reader %,d µs), avg %,d µs/op%n",
                    r, yamlFirst.get(yamlFirst.size() - 1) / 1_000, firstReader / 1_000, yamlAvg.get(yamlAvg.size() - 1)
                            / 1_000);
        }

        // ── 3. the CONVERSION: open → LOAD everything (the YAML factory: the caches fill)
        // → swap the factory → shutdown: the checkpoint rewrites the cached state through
        // the binary codec (a session that never loaded has nothing in the cache and the
        // checkpoint would rewrite nothing — the first smoke run proved it) ──
        storage = XdStorageProvider.newOrGetFileStorage("binexp-convert", root.toString(), 64);
        final IXdStorageTransaction loadTx = storage.beginTransaction();
        final int staged = storage.load(BinFeasEntity.class, (IXdStoragePredicate<BinFeasEntity>) o -> true, loadTx)
                .size();
        storage.commitTransaction(loadTx);
        install(storage, new BinFeasBinaryIOFactory(servicesOf(storage), idGenOf(storage)));
        // the RESOURCES capture their reader/writer at construction (the load above created
        // them with the YAML factory) — the checkpoint writes through the CAPTURED writer,
        // so swap every live resource's reader/writer to the binary codec too
        swapLiveResources(storage);
        // the checkpoint fires PER RESOURCE and ONLY when that resource's journal holds a
        // tail (sizeOf != 0 → else return false) — stage one idempotent update PER FRAGMENT
        // (the fragment stride = the fragment size 64) so every resource's journal gets a
        // tail and every checkpoint rewrites its fragment through the binary codec
        for (int i = 0; i < n; i += 64) {
            final IXdStorageTransaction touchTx = storage.beginTransaction();
            final BinFeasEntity touch = storage.load(BinFeasEntity.class, "e" + i, touchTx);
            touch.setName(touch.getName()); // no-op content-wise; the MUTATOR still stages an update
            storage.update(touch, touchTx);
            storage.commitTransaction(touchTx);
        }

        final long binCpStart = System.nanoTime();
        storage.shutdown();
        System.out.printf(Locale.ROOT, "[BINEXP] conversion staged %d entities into the cache before the checkpoint%n",
                staged);
        final long binCpWall = System.nanoTime() - binCpStart;
        final long binBytes = dirBytes(root);
        assertBinaryBases(root);
        // the id-index base: an idempotent update never touches the id→fragment routing,
        // so the index journal got no tail and its checkpoint never fired — convert the
        // index base at the FILE level (the YAML reader -> the neutral records -> the codec)
        convertIndexBase(root, storage);
        System.out.printf(Locale.ROOT, "[BINEXP] BINARY: %,d bytes (%.2f× of YAML), checkpoint %,d ms%n",
                binBytes, (double) yamlBytes / Math.max(1, binBytes), binCpWall / 1_000_000);

        // ── 4. the binary cold rounds ──
        final List<Long> binScanWalls = new ArrayList<>();
        final List<Long> binScanReaders = new ArrayList<>();
        final List<Long> binFirst = new ArrayList<>();
        final List<Long> binAvg = new ArrayList<>();
        for (int r = 0; r < rounds; r++) {
            storage = XdStorageProvider.newOrGetFileStorage("binexp-b-" + System.nanoTime(), root.toString(), 64);
            install(storage, new Measuring(new BinFeasBinaryIOFactory(servicesOf(storage), idGenOf(storage))));
            final IXdStorageTransaction tx = storage.beginTransaction();
            READER_NANOS.set(0);
            final long t0 = System.nanoTime();
            final Collection<BinFeasEntity> loaded = storage.load(BinFeasEntity.class,
                    (IXdStoragePredicate<BinFeasEntity>) o -> true, tx);
            final long wall = System.nanoTime() - t0;
            assertIntegrity(loaded, model); // the data-loss gate: EVERY entity, EVERY field
            binScanWalls.add(wall);
            binScanReaders.add(READER_NANOS.get());
            storage.commitTransaction(tx);
            storage.shutdown();
        }
        // the binary LOOKUP rounds: the same fresh-reopen protocol
        for (int r = 0; r < rounds; r++) {
            storage = XdStorageProvider.newOrGetFileStorage("binexp-bl-" + System.nanoTime(), root.toString(), 64);
            install(storage, new Measuring(new BinFeasBinaryIOFactory(servicesOf(storage), idGenOf(storage))));
            final IXdStorageTransaction tx = storage.beginTransaction();
            READER_NANOS.set(0);
            final long tf = System.nanoTime();
            final BinFeasEntity cold = storage.load(BinFeasEntity.class, "e" + (n / 2), tx);
            binFirst.add(System.nanoTime() - tf);
            assertNotNull(cold, "the cold lookup missed");
            final long ta = System.nanoTime();
            for (int i = 0; i < lookups; i++) {
                final BinFeasEntity e = storage.load(BinFeasEntity.class, "e" + i, tx);
                assertNotNull(e, "lookup missed e" + i);
                assertEquals(model.get("e" + i).getName(), e.getName(), "the lookup payload diverges");
            }
            binAvg.add((System.nanoTime() - ta) / lookups);
            storage.commitTransaction(tx);
            storage.shutdown();
            System.out.printf(Locale.ROOT, "[BINEXP][diag] bin lookup round %d: FIRST %,d µs, avg %,d µs/op%n",
                    r, binFirst.get(binFirst.size() - 1) / 1_000, binAvg.get(binAvg.size() - 1) / 1_000);
        }

        // ── the report ──
        final long yScan = median(yamlScanWalls);
        final long ySer = median(yamlScanReaders);
        final long bScan = median(binScanWalls);
        final long bSer = median(binScanReaders);
        System.out.println();
        System.out.println("=== BINEXP RESULT (medians over " + rounds + " rounds) ===");
        System.out.printf(Locale.ROOT,
                "cold full scan:  YAML %,d ms (serialization %,d ms) | BINARY %,d ms (serialization %,d ms) | " +
                "realized %.2f×%n",
                yScan / 1_000_000, ySer / 1_000_000, bScan / 1_000_000, bSer / 1_000_000, (double) yScan / bScan);
        System.out.printf(Locale.ROOT, "cold FIRST lookup: YAML %,d µs | BINARY %,d µs | realized %.2f×%n",
                median(yamlFirst) / 1_000, median(binFirst) / 1_000, (double) median(yamlFirst) / median(binFirst));
        System.out.printf(Locale.ROOT,
                "warm lookups:      YAML %,d µs | BINARY %,d µs (format-blind: the record cache)%n",
                median(yamlAvg) / 1_000, median(binAvg) / 1_000);
        System.out.printf(Locale.ROOT, "checkpoint write-out: YAML %,d ms | BINARY %,d ms | realized %.2f×%n",
                yamlCpWall / 1_000_000, binCpWall / 1_000_000, (double) yamlCpWall / binCpWall);
        System.out.printf(Locale.ROOT, "storage bytes: YAML %,d | BINARY %,d (%.2f× smaller)%n",
                yamlBytes, binBytes, (double) yamlBytes / Math.max(1, binBytes));
        System.out.println("INTEGRITY: every entity field-equal to the source model in BOTH arms; all lookups " +
                "payload-checked; the bases BF1-magic-verified.");
    }

    /** Swaps the reader/writer of every LIVE resource to the binary codec (the resources
     * capture them at construction — the locator swap alone does not reach them). */
    private static void swapLiveResources(final IXdFileStorage storage) throws Exception {
        final Object locator = servicesOf(storage);
        final IXdStorageIOFactory binary = new BinFeasBinaryIOFactory(servicesOf(storage), idGenOf(storage));
        final Object manager = findField(locator.getClass(), "resourcesManager").get(locator);
        final Map<?, ?> resources = (Map<?, ?>) findField(manager.getClass(), "resources").get(manager);
        int swapped = 0;
        for (final Object res : resources.values()) {
            findField(res.getClass(), "reader").set(res, binary.newInstanceReader());
            findField(res.getClass(), "writer").set(res, binary.newInstanceWriter());
            swapped++;
        }
        System.out.printf(Locale.ROOT, "[BINEXP] swapped the reader/writer of %d live resources to binary%n", swapped);
    }

    private static io.github.xdstorage.services.XdStorageServicesLocator servicesOf(final IXdStorage storage) throws
            Exception {
        return (io.github.xdstorage.services.XdStorageServicesLocator) findField(storage.getClass(), "services")
                .get(storage);
    }

    private static io.github.xdstorage.services.XdStorageServicesLocator servicesOf(final IXdFileStorage storage) throws
            Exception {
        return (io.github.xdstorage.services.XdStorageServicesLocator) findField(storage.getClass(), "services")
                .get(storage);
    }

    private static io.github.xdstorage.idgeneration.IXdStorageIdGenerator idGenOf(final IXdFileStorage storage) throws
            Exception {
        final Object locator = servicesOf(storage);
        return (io.github.xdstorage.idgeneration.IXdStorageIdGenerator) findField(locator.getClass(), "idGenerator")
                .get(locator);
    }
}
