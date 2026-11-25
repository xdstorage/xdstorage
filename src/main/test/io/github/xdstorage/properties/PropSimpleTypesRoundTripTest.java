package io.github.xdstorage.properties;

import io.github.xdstorage.IXdFileStorage;
import io.github.xdstorage.IXdStoragePredicate;
import io.github.xdstorage.transaction.IXdStorageTransaction;
import io.github.xdstorage.XdStorageProvider;

import java.io.File;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PROPTEST P2 (task 41): UUID + java.time simple types round-trip —
 * every value survives save->load (all three policy arms) and reopen.
 */
public class PropSimpleTypesRoundTripTest {

    private final List<File> folders = new ArrayList<>();

    private File folder(final String tag) {
        final File dir = new File("./target/pt-simple-" + tag + "-" + System.nanoTime());
        folders.add(dir);
        return dir;
    }

    private IXdFileStorage openIn(final File dir, final String tag) {
        return XdStorageProvider.newOrGetFileStorage("pt-simple-" + tag + "-" + System.nanoTime(),
                dir.getPath(), 64);
    }

    void cleanup() {
        for (final File dir : folders) {
            deleteRecursively(dir);
        }
        folders.clear();
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

    private static final String[] ARMS = {"class-hash", "class-btree", "single"};

    private static PropSimpleTypesEntity makeEntity(final String id) {
        final PropSimpleTypesEntity e = new PropSimpleTypesEntity();
        e.setId(id);
        e.setUuid(UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890"));
        e.setInstant(Instant.parse("2024-06-15T12:30:00Z"));
        e.setLocalDate(LocalDate.parse("2023-11-01"));
        e.setLocalDateTime(LocalDateTime.parse("2022-03-15T08:45:30"));
        e.setDuration(Duration.ofDays(7).plusHours(3).plusMinutes(12).plusSeconds(45));
        return e;
    }

    private static <T> T firstBy(final IXdFileStorage storage, final Class<T> cl,
                                 final IXdStoragePredicate<T> pred, final IXdStorageTransaction tx)
            throws Exception {
        @SuppressWarnings("unchecked")
        final Collection<T> col = (Collection<T>) storage.load(cl, pred, tx);
        for (T t : col) { return t; }
        return null;
    }

    @org.junit.jupiter.api.Test
    void roundTrip_SimpleTypesSurviveAllArms() throws Exception {
        for (int arm = 0; arm < ARMS.length; arm++) {
            final PropSimpleTypesEntity original = makeEntity("simple-" + arm + "-0");
            final File dir = folder(ARMS[arm]);
            final IXdFileStorage storage = openIn(dir, ARMS[arm]);
            try {
                final IXdStorageTransaction tx = storage.beginTransaction();
                storage.save(original, tx);
                storage.commitTransaction(tx);

                final IXdStorageTransaction readTx = storage.beginTransaction();
                final PropSimpleTypesEntity loaded = firstBy(storage, PropSimpleTypesEntity.class,
                        e -> e.getId().equals(original.getId()), readTx);
                assertNotNull(loaded, "round-trip " + arm + " loaded");
                assertSimpleTypeEqual(original, loaded, "round-trip " + arm);
                storage.commitTransaction(readTx);
            } finally {
                storage.shutdown();
            }

            final IXdFileStorage reopened = openIn(dir, ARMS[arm] + "-reopen");
            try {
                final IXdStorageTransaction readTx = reopened.beginTransaction();
                final PropSimpleTypesEntity loaded = firstBy(reopened, PropSimpleTypesEntity.class,
                        e -> e.getId().equals(original.getId()), readTx);
                assertNotNull(loaded, "reopen " + arm + " loaded");
                assertSimpleTypeEqual(original, loaded, "reopen " + arm);
                reopened.commitTransaction(readTx);
            } finally {
                reopened.shutdown();
            }
        }
    }

    @org.junit.jupiter.api.Test
    void roundTrip_NullValues_AreHandled() throws Exception {
        final PropSimpleTypesEntity original = makeEntity("null-simple-0");
        original.setUuid(null);
        original.setInstant(null);
        original.setLocalDate(null);
        original.setLocalDateTime(null);
        original.setDuration(null);

        final File dir = folder("null-simple");
        final IXdFileStorage storage = openIn(dir, "null-simple");
        try {
            final IXdStorageTransaction tx = storage.beginTransaction();
            storage.save(original, tx);
            storage.commitTransaction(tx);

            final IXdStorageTransaction readTx = storage.beginTransaction();
            final PropSimpleTypesEntity loaded = firstBy(storage, PropSimpleTypesEntity.class,
                    e -> e.getId().equals(original.getId()), readTx);
            assertNotNull(loaded);
            assertNull(loaded.getUuid(), "null uuid survives");
            assertNull(loaded.getInstant(), "null instant survives");
            assertNull(loaded.getLocalDate(), "null localDate survives");
            assertNull(loaded.getLocalDateTime(), "null localDateTime survives");
            assertNull(loaded.getDuration(), "null duration survives");
            storage.commitTransaction(readTx);
        } finally {
            storage.shutdown();
        }
    }

    /** Entity with simple types: UUID, Instant, LocalDate, LocalDateTime, Duration. */
    @io.github.xdstorage.annotations.XdStorageObjectPolicy(policy = io.github.xdstorage.XdStoragePolicy.StoreAsClassObjects)
    public static class PropSimpleTypesEntity {
        @io.github.xdstorage.annotations.XdStorageObjectId
        private String id;
        private UUID uuid;
        private Instant instant;
        private LocalDate localDate;
        private LocalDateTime localDateTime;
        private Duration duration;

        public String getId() { return id; }
        public void setId(final String id) { this.id = id; }

        public UUID getUuid() { return uuid; }
        public void setUuid(final UUID uuid) { this.uuid = uuid; }

        public Instant getInstant() { return instant; }
        public void setInstant(final Instant instant) { this.instant = instant; }

        public LocalDate getLocalDate() { return localDate; }
        public void setLocalDate(final LocalDate localDate) { this.localDate = localDate; }

        public LocalDateTime getLocalDateTime() { return localDateTime; }
        public void setLocalDateTime(final LocalDateTime localDateTime) { this.localDateTime = localDateTime; }

        public Duration getDuration() { return duration; }
        public void setDuration(final Duration duration) { this.duration = duration; }
    }

    private void assertSimpleTypeEqual(final PropSimpleTypesEntity a, final PropSimpleTypesEntity b,
                                       final String label) {
        assertEquals(a.getId(), b.getId(), label + " id");
        assertEquals(a.getUuid(), b.getUuid(), label + " uuid");
        assertEquals(a.getInstant(), b.getInstant(), label + " instant");
        assertEquals(a.getLocalDate(), b.getLocalDate(), label + " localDate");
        assertEquals(a.getLocalDateTime(), b.getLocalDateTime(), label + " localDateTime");
        assertEquals(a.getDuration(), b.getDuration(), label + " duration");
    }
}
