package io.github.xdstorage.performance;

import io.github.xdstorage.BinFeasEntity;
import io.github.xdstorage.IXdFileStorage;
import io.github.xdstorage.IXdStoragePredicate;
import io.github.xdstorage.XdStorageProvider;
import io.github.xdstorage.serialization.IXdStorageIOFactory;
import io.github.xdstorage.serialization.IXdStorageObjectsReader;
import io.github.xdstorage.serialization.IXdStorageObjectsWriter;
import io.github.xdstorage.object.XdStorageIdentifiableObject;
import io.github.xdstorage.transaction.IXdStorageTransaction;
import io.github.xdstorage.utils.XdStorageObjectIdField;

import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPOutputStream;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * BINFEAS (docs/tasks/2.1.0/33): the FP-9 gate probe — measures the SERIALIZATION share
 * of the cold paths and the file-size lever, without touching production code.
 *
 * <p>The method (the research design):
 * <ul>
 *   <li>a measuring DECORATOR is swapped into the services locator's ioFactory field via
 *       reflection (a measurement-harness privilege, no production change): every reader
 *       call = parse+build time, every writer call = serialization time;</li>
 *   <li>the file IO happens OUTSIDE the reader (XdStorageResource hands the reader a
 *       char stream), so the split is clean: serialization vs (raw byte floor) vs the
 *       engine residual (wrappers, caches, index trees, id machinery);</li>
 *   <li>phases: the cold FULL scan (every fragment + the id-index tree first-touch),
 *       the cold point-lookups (the FIRST lookup after reopen reported separately — the
 *       truest cold op), the checkpoint write-out (journalWrites=true keeps commits in
 *       the journal; the bulk serialization fires at the shutdown checkpoint), and the
 *       raw IO floor (page-cache-warm reads of the same bytes, disclosed);</li>
 *   <li>the bytes lever: gzip every data file — a cheap LOWER bound of the size
 *       reduction a binary format would achieve (a binary schema encodes structure more
 *       compactly than gzip-on-text typically, but gzip is the honest floor);</li>
 *   <li>medians over R rounds; the OS page cache is deliberately warm (files just
 *       written) — this is the CONSERVATIVE choice for the parse share: on a disk-cold
 *       start the IO share grows and the parse share shrinks, so a binary format wins
 *       LESS there.</li>
 * </ul>
 *
 * <p>Run (surefire-invisible; the canonical conditions — WARN-console logging, quiet
 * box): {@code java -Dlog4j.configurationFile=... -cp <test-cp>
 * io.github.xdstorage.performance.XdStorageBinaryFormatFeasibilityProbe [N] [rounds]}
 */
public final class XdStorageBinaryFormatFeasibilityProbe {

    /** The per-phase accumulation the proxies feed; reset before each phase. */
    private static final AtomicLong READER_NANOS = new AtomicLong();
    private static final AtomicLong WRITER_NANOS = new AtomicLong();
    private static final AtomicLong READER_BYTES = new AtomicLong();

    private XdStorageBinaryFormatFeasibilityProbe() {
    }

    // ─────────────────────────── the measuring decorator ───────────────────────────

    private static final class MeasuringFactory extends io.github.xdstorage.serialization.yaml.XdStorageYamlIOFactory {
        private final IXdStorageIOFactory delegate;

        MeasuringFactory(final IXdStorageIOFactory delegate) {
            // the delegate IS the real XdStorageYamlIOFactory (the engine resolves the
            // factory by format); the superclass wiring is satisfied by the same args
            super(servicesOf(delegate), idGeneratorOf(delegate));
            this.delegate = delegate;
        }

        private static io.github.xdstorage.services.XdStorageServicesLocator servicesOf(final IXdStorageIOFactory f) {
            try {
                final Field f1 = findField(f.getClass(), "services");
                return (io.github.xdstorage.services.XdStorageServicesLocator) f1.get(f);
            } catch (final Exception e) {
                throw new RuntimeException("cannot resolve the delegate factory's locator", e);
            }
        }

        private static io.github.xdstorage.idgeneration.IXdStorageIdGenerator idGeneratorOf(final IXdStorageIOFactory f)
                {
            try {
                final Field f1 = findField(f.getClass(), "idGenerator");
                return (io.github.xdstorage.idgeneration.IXdStorageIdGenerator) f1.get(f);
            } catch (final Exception e) {
                throw new RuntimeException("cannot resolve the delegate factory's id generator", e);
            }
        }

        @Override
        public IXdStorageObjectsReader newInstanceReader() {
            return new ReaderProxy(this.delegate.newInstanceReader());
        }

        @Override
        public IXdStorageObjectsWriter newInstanceWriter() {
            return new WriterProxy(this.delegate.newInstanceWriter());
        }
    }

    private static final class ReaderProxy implements IXdStorageObjectsReader {
        private final IXdStorageObjectsReader delegate;

        ReaderProxy(final IXdStorageObjectsReader delegate) {
            this.delegate = delegate;
        }

        @Override
        public Collection<Object> readReferences(final Reader reader, final XdStorageObjectIdField field)
                throws io.github.xdstorage.exceptions.XdStorageIOException, io.github.xdstorage.exceptions
                        .XdStorageException {
            final long t0 = System.nanoTime();
            try {
                return this.delegate.readReferences(reader, field);
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
                return this.delegate.read(reader);
            } finally {
                READER_NANOS.addAndGet(System.nanoTime() - t0);
            }
        }

        @Override
        public Collection<Object> read(final Reader reader, final IXdStorageTransaction transaction)
                throws io.github.xdstorage.exceptions.XdStorageIOException {
            final long t0 = System.nanoTime();
            try {
                return this.delegate.read(reader, transaction);
            } finally {
                READER_NANOS.addAndGet(System.nanoTime() - t0);
            }
        }

        @Override
        public Collection<XdStorageIdentifiableObject> readData(final Reader xmlReader, final XdStorageObjectIdField
                field)
                throws io.github.xdstorage.exceptions.XdStorageIOException, io.github.xdstorage.exceptions
                        .XdStorageException {
            final long t0 = System.nanoTime();
            try {
                return this.delegate.readData(xmlReader, field);
            } finally {
                READER_NANOS.addAndGet(System.nanoTime() - t0);
            }
        }
    }

    private static final class WriterProxy implements IXdStorageObjectsWriter {
        private final IXdStorageObjectsWriter delegate;

        WriterProxy(final IXdStorageObjectsWriter delegate) {
            this.delegate = delegate;
        }

        @Override
        public void writeReferences(final Writer writer, final XdStorageObjectIdField field,
                                    final Collection<Object> references) throws io.github.xdstorage.exceptions
                                            .XdStorageIOException {
            final long t0 = System.nanoTime();
            try {
                this.delegate.writeReferences(writer, field, references);
            } finally {
                WRITER_NANOS.addAndGet(System.nanoTime() - t0);
            }
        }

        @Override
        public void writeObjects(final Writer writer, final Collection<Object> objects)
                throws io.github.xdstorage.exceptions.XdStorageIOException {
            final long t0 = System.nanoTime();
            try {
                this.delegate.writeObjects(writer, objects);
            } finally {
                WRITER_NANOS.addAndGet(System.nanoTime() - t0);
            }
        }
    }

    /** Swaps the measuring factory into the storage's services locator via reflection. */
    private static void installDecorator(final IXdFileStorage storage) throws Exception {
        final Field servicesField = storage.getClass().getSuperclass() != null
                ? findField(storage.getClass(), "services") : null;
        final Object locator = servicesField.get(storage);
        final Field ioFactoryField = findField(locator.getClass(), "ioFactory");
        final IXdStorageIOFactory real = (IXdStorageIOFactory) ioFactoryField.get(locator);
        ioFactoryField.set(locator, new MeasuringFactory(real));
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

    // ─────────────────────────── the phases ───────────────────────────

    private static long rawReadAll(final Path root) throws Exception {
        final long t0 = System.nanoTime();
        final byte[] buf = new byte[1 << 16];
        try (final java.util.stream.Stream<Path> walk = Files.walk(root)) {
            for (final Path p : (Iterable<Path>) walk.filter(Files::isRegularFile)::iterator) {
                try (InputStream in = new FileInputStream(p.toFile())) {
                    while (in.read(buf) != -1) {
                        // drain
                    }
                }
            }
        }
        return System.nanoTime() - t0;
    }

    private static long gzipDataFiles(final Path root) throws Exception {
        long raw = 0;
        long gz = 0;
        try (final java.util.stream.Stream<Path> walk = Files.walk(root)) {
            for (final Path p : (Iterable<Path>) walk.filter(Files::isRegularFile)
                    .filter(f -> f.toString().endsWith(".yaml") && !f.toString().contains("journal"))::iterator) {
                raw += Files.size(p);
                final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                try (InputStream in = new FileInputStream(p.toFile());
                     GZIPOutputStream gzOut = new GZIPOutputStream(bytes)) {
                    final byte[] buf = new byte[1 << 16];
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        gzOut.write(buf, 0, n);
                    }
                }
                gz += bytes.size();
            }
        }
        System.out.printf(Locale.ROOT, "[BINFEAS] gzip lever: yaml %,d b -> gzip %,d b (ratio %.1f×)%n", raw, gz,
                (double) raw / Math.max(1, gz));
        return raw;
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

    private static long median(final List<Long> values) {
        final List<Long> sorted = new ArrayList<>(values);
        java.util.Collections.sort(sorted);
        return sorted.get(sorted.size() / 2);
    }

    public static void main(final String[] args) throws Exception {
        final int n = args.length > 0 ? Integer.parseInt(args[0]) : 5000;
        final int rounds = args.length > 1 ? Integer.parseInt(args[1]) : 5;
        final int lookups = args.length > 2 ? Integer.parseInt(args[2]) : 500;
        final Path root = Paths.get("./target/binfeas-" + System.nanoTime());
        deleteRecursively(root.toFile());
        System.out.printf(Locale.ROOT, "[BINFEAS] N=%d rounds=%d lookups=%d root=%s%n", n, rounds, lookups, root);

        // ── the fill ──
        IXdFileStorage storage = XdStorageProvider.newOrGetFileStorage("binfeas-" + System.nanoTime(), root.toString(),
                64);
        final long fillStart = System.nanoTime();
        for (int batchStart = 0; batchStart < n; batchStart += 200) {
            final IXdStorageTransaction tx = storage.beginTransaction();
            for (int i = batchStart; i < Math.min(batchStart + 200, n); i++) {
                storage.save(new BinFeasEntity("e" + i, i), tx);
            }
            storage.commitTransaction(tx);
        }
        final long fillWall = System.nanoTime() - fillStart;
        storage.shutdown();

        final long bytesTotal = dirBytes(root);
        int fileCount = 0;
        try (final java.util.stream.Stream<Path> walk = Files.walk(root)) {
            fileCount = (int) walk.filter(Files::isRegularFile).count();
        }
        System.out.printf(Locale.ROOT, "[BINFEAS] fill: %d entities in %,d ms; storage %,d bytes in %d files%n",
                n, fillWall / 1_000_000, bytesTotal, fileCount);

        // ── the scan rounds (each reopen = a fresh cold parse of everything) ──
        final List<Long> scanWalls = new ArrayList<>();
        final List<Long> scanReaders = new ArrayList<>();
        for (int r = 0; r < rounds; r++) {
            storage = XdStorageProvider.newOrGetFileStorage("binfeas-scan", root.toString(), 64);
            installDecorator(storage);
            READER_NANOS.set(0);
            final long t0 = System.nanoTime();
            final IXdStorageTransaction tx = storage.beginTransaction();
            final int loaded = storage.load(BinFeasEntity.class, (IXdStoragePredicate<BinFeasEntity>) o -> true, tx)
                    .size();
            storage.commitTransaction(tx);
            final long wall = System.nanoTime() - t0;
            final long reader = READER_NANOS.get();
            scanWalls.add(wall);
            scanReaders.add(reader);
            System.out.printf(Locale.ROOT, "[BINFEAS] scan round %d: %,d ms wall, reader %,d ms (%d loaded)%n",
                    r, wall / 1_000_000, reader / 1_000_000, loaded);
            storage.shutdown();
        }

        // ── the cold point-lookup rounds (the FIRST op reported separately) ──
        final List<Long> firstLookups = new ArrayList<>();
        final List<Long> firstLookupReaders = new ArrayList<>();
        final List<Long> avgLookups = new ArrayList<>();
        for (int r = 0; r < rounds; r++) {
            storage = XdStorageProvider.newOrGetFileStorage("binfeas-lookup", root.toString(), 64);
            installDecorator(storage);
            final IXdStorageTransaction tx = storage.beginTransaction();
            READER_NANOS.set(0);
            final long t0 = System.nanoTime();
            storage.load(BinFeasEntity.class, "e" + (n / 2), tx); // the ONE truly cold op
            final long firstWall = System.nanoTime() - t0;
            final long firstReader = READER_NANOS.get();
            READER_NANOS.set(0);
            final long tAll = System.nanoTime();
            for (int i = 0; i < lookups; i++) {
                storage.load(BinFeasEntity.class, "e" + i, tx);
            }
            final long avgWall = (System.nanoTime() - tAll) / lookups;
            firstLookups.add(firstWall);
            firstLookupReaders.add(firstReader);
            avgLookups.add(avgWall);
            storage.commitTransaction(tx);
            storage.shutdown();
            System.out.printf(Locale.ROOT,
                    "[BINFEAS] lookup round %d: FIRST %,d µs (reader %,d µs), then avg %,d µs/op%n",
                    r, firstWall / 1_000, firstReader / 1_000, avgWall / 1_000);
        }

        // ── the raw IO floor (the same bytes, page-cache warm) ──
        final List<Long> ioFloors = new ArrayList<>();
        for (int r = 0; r < rounds; r++) {
            ioFloors.add(rawReadAll(root));
        }
        System.out.printf(Locale.ROOT, "[BINFEAS] IO floor (page-cache warm): median %,d ms%n", median(ioFloors) /
                1_000_000);

        // ── the gzip bytes lever ──
        gzipDataFiles(root);

        // ── the checkpoint write-out serialization share ──
        storage = XdStorageProvider.newOrGetFileStorage("binfeas-write", root.toString(), 64);
        installDecorator(storage);
        READER_NANOS.set(0);
        WRITER_NANOS.set(0);
        final long wStart = System.nanoTime();
        for (int batchStart = n; batchStart < 2 * n; batchStart += 200) {
            final IXdStorageTransaction tx = storage.beginTransaction();
            for (int i = batchStart; i < Math.min(batchStart + 200, 2 * n); i++) {
                storage.save(new BinFeasEntity("e" + i, i), tx);
            }
            storage.commitTransaction(tx);
        }
        final long writesWall = System.nanoTime() - wStart;
        final long writeJournals = WRITER_NANOS.get();
        WRITER_NANOS.set(0);
        final long cpStart = System.nanoTime();
        storage.shutdown(); // the checkpoint: the bulk write-out serialization
        final long checkpointWall = System.nanoTime() - cpStart;
        final long checkpointWriter = WRITER_NANOS.get();
        System.out.printf(Locale.ROOT,
                "[BINFEAS] write wave: %d saves in %,d ms (journal-serialized %,d ms counted at the factory)%n",
                n, writesWall / 1_000_000, writeJournals / 1_000_000);
        System.out.printf(Locale.ROOT, "[BINFEAS] shutdown checkpoint: %,d ms wall, writer %,d ms%n",
                checkpointWall / 1_000_000, checkpointWriter / 1_000_000);

        // ── the report ──
        final long scanWall = median(scanWalls);
        final long scanReader = median(scanReaders);
        final long ioFloor = median(ioFloors);
        final long firstLookup = median(firstLookups);
        final long engineScan = scanWall - scanReader;
        System.out.println();
        System.out.println("=== BINFEAS RESULT (medians) ===");
        System.out.printf(Locale.ROOT,
                "scan cold full load:   wall %,d ms | serialization %,d ms (%.1f%%) | IO floor %,d ms (%.1f%%) | " +
                "engine residual %,d ms (%.1f%%)%n",
                scanWall / 1_000_000, scanReader / 1_000_000, 100.0 * scanReader / scanWall,
                ioFloor / 1_000_000, 100.0 * ioFloor / scanWall,
                engineScan / 1_000_000, 100.0 * engineScan / scanWall);
        final double firstReaderAvg = firstLookupReaders.stream().mapToLong(Long::longValue).average().orElse(0);
        System.out.printf(Locale.ROOT, "cold FIRST lookup:     %,d µs | serialization inside %,d µs (%.1f%%)%n",
                firstLookup / 1_000, (long) (firstReaderAvg / 1_000),
                100.0 * firstReaderAvg / firstLookup);
        System.out.printf(Locale.ROOT, "warm lookups:          avg %,d µs/op (no parse — the record cache)%n",
                median(avgLookups) / 1_000);
        System.out.printf(Locale.ROOT, "checkpoint write-out:  serialization %.1f%% of the checkpoint wall%n",
                100.0 * checkpointWriter / Math.max(1, checkpointWall));
        System.out.printf(Locale.ROOT, "storage bytes: %,d in %d files%n", bytesTotal, fileCount);
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
}
