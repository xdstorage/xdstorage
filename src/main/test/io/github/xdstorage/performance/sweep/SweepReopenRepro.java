package io.github.xdstorage.performance.sweep;

import io.github.xdstorage.IXdFileStorage;
import io.github.xdstorage.XdStorageProvider;
import io.github.xdstorage.search.query.IXdStorageCriterionOperator;
import io.github.xdstorage.search.query.XdStoragePrimaryCriterion;
import io.github.xdstorage.search.query.XdStorageSearchQuery;
import io.github.xdstorage.transaction.IXdStorageTransaction;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Minimal repro: save with BTree (and Hash control) id-index / search index -> shutdown -> reopen -> load/search. */
public final class SweepReopenRepro {

    public static void main(final String[] args) throws Exception {
        int bad = 0;
        bad += repro("btree", SweepBTreeT64Entity.class);
        bad += repro("hash", SweepHashEntity.class);
        bad += searchRepro();
        if (bad > 0)
            System.exit(3);
    }

    private static int searchRepro() throws Exception {
        final Class<?> cl = SweepSearchShipT64.class;
        final Path dir = Paths.get("target", "sweep", "repro-search-" + System.nanoTime());
        final String name = "repro-search-" + System.nanoTime();
        IXdFileStorage storage = XdStorageProvider.newOrGetFileStorage(name, dir.toString(), 512);
        final IXdStorageTransaction tx = storage.beginTransaction(60000L);
        try {
            for (int i = 0; i < 5; i++)
                storage.save(newShip(cl, (long) i), tx);
            storage.commitTransaction(tx);
        } catch (final Exception e) {
            storage.rollbackTransaction(tx);
            throw e;
        }
        storage.shutdown();

        storage = XdStorageProvider.newOrGetFileStorage(name, dir.toString(), 512);
        final IXdStorageTransaction tx2 = storage.beginTransaction(60000L);
        int bad = 0;
        try {
            final Object byId = storage.load(cl, 3L, tx2);
            System.out.println("[search] load by id -> " + (byId == null ? "NULL" : "ok"));
            final Object found = searchEquals(storage, cl, "Ship-3", tx2);
            final int equalsCount = ((java.util.Collection<?>) found).size();
            System.out.println("[search] EQUALS Ship-3 -> " + equalsCount + " result(s)");
            final Object scanned = searchScan(storage, cl, tx2);
            final int scanCount = ((java.util.Collection<?>) scanned).size();
            System.out.println("[search] full scan -> " + scanCount + " result(s)");
            if (byId == null) bad++;
            if (equalsCount != 1) bad++;
            if (scanCount != 5) bad++;
            storage.commitTransaction(tx2);
        } catch (final Exception e) {
            storage.rollbackTransaction(tx2);
            System.out.println("[search] tx failed: " + e);
            bad++;
        }
        System.out.println("[search] BAD=" + bad);
        storage.shutdown();
        if (System.getProperty("repro.keep") == null)
            Files.walk(dir).sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        return bad;
    }

    private static Object newShip(final Class<?> cl, final long i) throws Exception {
        final Object ship = cl.getDeclaredConstructor().newInstance();
        final Class<?> crewCl = SweepSearchCrewMember.class;
        cl.getMethod("setId", Long.class).invoke(ship, i);
        cl.getMethod("setName", String.class).invoke(ship, "Ship-" + i);
        cl.getMethod("setTonnage", int.class).invoke(ship, (int) (i % 50));
        cl.getMethod("setCaptain", crewCl).invoke(ship, crewCl.getDeclaredConstructor(Long.class, String.class)
                .newInstance(i * 10, "Rank-" + (i % 5)));
        cl.getMethod("addCrewMember", crewCl).invoke(ship, crewCl.getDeclaredConstructor(Long.class, String.class)
                .newInstance(i * 10 + 1, "Rank-3"));
        return ship;
    }

    private static Object searchEquals(final IXdFileStorage storage, final Class<?> cl, final String name,
                                       final IXdStorageTransaction tx) throws Exception {
        return storage.load(cl, "sweep_idx",
                new XdStorageSearchQuery(new XdStoragePrimaryCriterion(IXdStorageCriterionOperator.EQUALS, name), null),
                        tx);
    }

    private static Object searchScan(final IXdFileStorage storage, final Class<?> cl,
                                     final IXdStorageTransaction tx) throws Exception {
        return storage.load(cl, "sweep_idx",
                new XdStorageSearchQuery(new XdStoragePrimaryCriterion(IXdStorageCriterionOperator.NOT_EQUALS,
                        "@@none@@"), null), tx);
    }

    private static int repro(final String tag, final Class<?> cl) throws Exception {
        final Path dir = Paths.get("target", "sweep", "repro-" + tag + "-" + System.nanoTime());
        final String name = "repro-" + tag + "-" + System.nanoTime();
        IXdFileStorage storage = XdStorageProvider.newOrGetFileStorage(name, dir.toString(), 512);
        final IXdStorageTransaction tx = storage.beginTransaction(60000L);
        try {
            for (int i = 0; i < 5; i++) {
                final Object e = cl.getDeclaredConstructor().newInstance();
                cl.getMethod("setId", String.class).invoke(e, "id-" + i);
                cl.getMethod("setPayload", String.class).invoke(e, "payload-" + i);
                storage.save(e, tx);
            }
            storage.commitTransaction(tx);
        } catch (final Exception e) {
            storage.rollbackTransaction(tx);
            throw e;
        }
        System.out.println("[" + tag + "] inserted 5, shutting down");
        storage.shutdown();

        storage = XdStorageProvider.newOrGetFileStorage(name, dir.toString(), 512);
        final IXdStorageTransaction tx2 = storage.beginTransaction(60000L);
        int nulls = 0;
        try {
            for (int i = 0; i < 5; i++) {
                final Object o = storage.load(cl, "id-" + i, tx2);
                System.out.println("[" + tag + "] load id-" + i + " -> " + (o == null ? "NULL" : "ok"));
                if (o == null)
                    nulls++;
            }
            storage.commitTransaction(tx2);
        } catch (final Exception e) {
            storage.rollbackTransaction(tx2);
            System.out.println("[" + tag + "] load tx failed: " + e);
        }
        System.out.println("[" + tag + "] NULLS=" + nulls + " of 5");
        storage.shutdown();
        if (System.getProperty("repro.keep") == null)
            Files.walk(dir).sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        else
            System.out.println("[" + tag + "] kept dir: " + dir);
        return nulls;
    }
}
