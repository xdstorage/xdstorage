package io.github.xdstorage.properties;

import io.github.xdstorage.IXdFileStorage;
import io.github.xdstorage.transaction.IXdStorageTransaction;
import io.github.xdstorage.XdStorageProvider;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.lifecycle.AfterTry;

import java.io.File;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * PROPTEST Этап 2 (docs/tasks/2.2.0/03 — beyond the task's scope): the CONTAINERS and
 * the SCALAR MATRIX. The task's CRUD properties cover the wide-entity shape; these
 * properties go deeper:
 * <ul>
 *   <li>P2.1: the nested-container jungle (List&lt;List&lt;String&gt;&gt;,
 *       Map&lt;String,List&lt;Long&gt;&gt;, List&lt;Map&lt;String,String&gt;&gt;, arrays,
 *       null elements) round-trips exactly;</li>
 *   <li>P2.2: EVERY supported scalar type round-trips with random values (BigDecimal
 *       scale-exact, Date millis-exact, enum by name, Class by name, the null honest
 *       assignment).</li>
 * </ul>
 */
public class PropContainersScalarsTest {

    private final List<File> folders = new ArrayList<>();

    @AfterTry
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

    private static PropJungle jungle(final Random rnd) {
        final PropJungle j = new PropJungle();
        final int deepSize = rnd.nextInt(4);
        for (int i = 0; i < deepSize; i++) {
            final List<String> inner = new ArrayList<>();
            for (int k = 0; k < rnd.nextInt(4); k++) {
                inner.add(rnd.nextInt(5) == 0 ? null : "d" + i + "-" + rnd.nextInt(1000));
            }
            j.getDeepList().add(inner);
        }
        for (int i = 0; i < rnd.nextInt(3); i++) {
            final List<Long> longs = new ArrayList<>();
            for (int k = 0; k < rnd.nextInt(3); k++) {
                longs.add(rnd.nextLong());
            }
            j.getListMap().put("lm" + i, longs);
        }
        for (int i = 0; i < rnd.nextInt(3); i++) {
            final Map<String, String> m = new HashMap<>();
            for (int k = 0; k < rnd.nextInt(3); k++) {
                m.put("mk" + i + "-" + k, rnd.nextInt(4) == 0 ? null : "mv" + rnd.nextInt(500));
            }
j.getMapList().add(m);
        }
        j.setStringArray(new String[rnd.nextInt(4)]);
        for (int i = 0; i < j.getStringArray().length; i++) {
            j.getStringArray()[i] = "sa" + rnd.nextInt(500);
        }
        j.setLongArray(new Long[rnd.nextInt(3)]);
        for (int i = 0; i < j.getLongArray().length; i++) {
            j.getLongArray()[i] = rnd.nextLong();
        }
        j.setIntArray(new int[rnd.nextInt(4)]);
        for (int i = 0; i < j.getIntArray().length; i++) {
            j.getIntArray()[i] = rnd.nextInt(100_000);
        }
        return j;
    }

    private static String jungleToString(final PropJungle j) {
        // the structure equality: the List equality is order-sensitive; the Map equality ignores
        // the order — compare by the content shape
        final StringBuilder sb = new StringBuilder();
        sb.append("deep=").append(j.getDeepList()).append("\n");
        final Map<String, List<Long>> sortedListMap = new TreeMap<>(j.getListMap());
        sb.append("listMap=").append(sortedListMap).append("\n");
        sb.append("mapList=").append(j.getMapList()).append("\n");
        sb.append("sArr=").append(java.util.Arrays.toString(j.getStringArray())).append("\n");
        sb.append("lArr=").append(java.util.Arrays.toString(j.getLongArray())).append("\n");
        sb.append("iArr=").append(java.util.Arrays.toString(j.getIntArray()));
        return sb.toString();
    }

    @Property(tries = 40)
    void containerJungle_RoundTripsExactly(@ForAll final int seed) throws Exception {
        final Random rnd = new Random(seed);
        final File dir = new File("./target/pt-jungle-" + System.nanoTime());
        folders.add(dir);
        final IXdFileStorage storage = XdStorageProvider.newOrGetFileStorage(
                "pt-jungle-" + System.nanoTime(), dir.getPath(), 64);
        try {
            final PropJungleHost host = new PropJungleHost().withId("j" + seed);
            host.setJungle(jungle(rnd));
            final String expected = jungleToString(host.getJungle());

            final IXdStorageTransaction tx = storage.beginTransaction();
            storage.save(host, tx);
            storage.commitTransaction(tx);

            final IXdStorageTransaction readTx = storage.beginTransaction();
            final PropJungleHost loaded = storage.load(PropJungleHost.class, host.getId(), readTx);
            storage.commitTransaction(readTx);
            assertEquals(expected, jungleToString(loaded.getJungle()),
                    "the container jungle diverges (the null cells, the nesting, the arrays)");
        } finally {
            storage.shutdown();
        }
    }

    private static void scalarsFromSeed(final PropScalars s, final Random rnd) {
        s.setPrimitiveLong(rnd.nextLong());
        s.setPrimitiveBool(rnd.nextBoolean());
        s.setBoxedInt(rnd.nextInt());
        s.setBoxedDouble((rnd.nextDouble() - 0.5) * Math.pow(10, rnd.nextInt(10) - 3));
        s.setText("текст-" + rnd.nextInt(100_000));
        s.setDecimal(new BigDecimal(String.valueOf(rnd.nextInt(500_000)) + "." + Math.abs(rnd.nextInt(999999))));
        s.setMoment(new Date(Math.abs(rnd.nextLong() % 3_000_000_000_000L)));
        s.setColor(PropColor.values()[rnd.nextInt(PropColor.values().length)]);
        s.setClassName(rnd.nextBoolean() ? String.class : java.lang.Long.class);
        s.setMaybeNull(rnd.nextBoolean() ? null : "nullable-" + rnd.nextInt(1000));
    }

    private static String scalarsToString(final PropScalars s) {
        return s.getPrimitiveLong() + "|" + s.isPrimitiveBool() + "|" + s.getBoxedInt() + "|" + s.getBoxedDouble()
                + "|" + s.getText() + "|" + s.getDecimal().toPlainString() + "|" + s.getMoment().getTime()
                + "|" + s.getColor() + "|" + s.getClassName().getName() + "|" + s.getMaybeNull();
    }

    @Property(tries = 40)
    void scalarMatrix_RoundTripsExactly(@ForAll final int seed) throws Exception {
        final Random rnd = new Random(seed);
        final File dir = new File("./target/pt-scalars-" + System.nanoTime());
        folders.add(dir);
        final IXdFileStorage storage = XdStorageProvider.newOrGetFileStorage(
                "pt-scalars-" + System.nanoTime(), dir.getPath(), 64);
        try {
            final PropScalarsHost host = new PropScalarsHost().withId("s" + seed);
            scalarsFromSeed(host.getScalars(), rnd);
            final String expected = scalarsToString(host.getScalars());

            final IXdStorageTransaction tx = storage.beginTransaction();
            storage.save(host, tx);
            storage.commitTransaction(tx);

            final IXdStorageTransaction readTx = storage.beginTransaction();
            final PropScalarsHost loaded = storage.load(PropScalarsHost.class, host.getId(), readTx);
            storage.commitTransaction(readTx);
            assertEquals(expected, scalarsToString(loaded.getScalars()), "the scalar matrix diverges");
            // the honest-null assignment: a null stored field loads as null (not the default)
            if (host.getScalars().getMaybeNull() == null) {
                assertNull(loaded.getScalars().getMaybeNull(), "the stored null must load as null");
            }
        } finally {
            storage.shutdown();
        }
    }
}
