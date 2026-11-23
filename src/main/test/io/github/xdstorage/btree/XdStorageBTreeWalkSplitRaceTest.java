package io.github.xdstorage.btree;

import io.github.xdstorage.transaction.IXdStorageTransaction;
import io.github.xdstorage.transaction.XdStorageTransaction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("R56SCAN: split первого листа в окне prewarm — обход должен быть полным")
public class XdStorageBTreeWalkSplitRaceTest extends AbstractBTreeIntegrationTest {

    private final AtomicLong txSeq = new AtomicLong();

    private XdStorageTransaction workerTx(final long seq) {
        return new XdStorageTransaction(null, "THREAD_" + seq, null, 5000L, "TX_" + seq) {
            @Override public void startCriticalSection(boolean read) {}
            @Override public void finishCriticalSection() {}
        };
    }

    @Test
    @DisplayName("Split первого листа в окне prewarm — обход возвращает все ключи (epoch retry)")
    void splitInPrewarmWindow_WalkReturnsAllKeys() throws Exception {
        final Set<String> model = new HashSet<>();
        XdStorageTransaction tx = workerTx(txSeq.incrementAndGet());
        for (int i = 1; i <= 40; ++i) {
            final String key = "k_" + String.format("%04d", i);
            btree.insert(key, "v_" + i, storageFacade, tx);
            model.add(key);
        }

        final List<Comparable> collected = Collections.synchronizedList(new ArrayList<>());
        final List<Throwable> errors = java.util.Collections.synchronizedList(new ArrayList<>());
        XdStorageTransaction readTx = workerTx(txSeq.incrementAndGet());
        btree.read(storageFacade, readTx, (key, value) -> collected.add(String.valueOf(key)));
        assertEquals(40, collected.size(), "сетап: 40 ключей до гонки");

        collected.clear();
        final CountDownLatch readerCaptured = new CountDownLatch(1);
        final CountDownLatch splitDone = new CountDownLatch(1);
        final AtomicBoolean gateFired = new AtomicBoolean(false);
        btree.setPrewarmGate(() -> {
            if (gateFired.compareAndSet(false, true)) {
                readerCaptured.countDown();
                try { splitDone.await(30, TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        });

        final Thread reader = new Thread(() -> {
            try {
                IXdStorageTransaction rt = storageFacade.beginTransaction(30000L);
                btree.read(storageFacade, rt, (key, value) -> collected.add(String.valueOf(key)));
                storageFacade.rollbackTransaction(rt);
            } catch (Throwable t) { errors.add(t); }
        });
        reader.start();

        assertTrue(readerCaptured.await(15, TimeUnit.SECONDS), "не дошёл до ворот");

        tx = workerTx(txSeq.incrementAndGet());
        for (int i = 1; i <= 12; ++i) {
            final String key = "k_a" + String.format("%04d", i);
            btree.insert(key, "new_" + i, storageFacade, tx);
            model.add(key);
        }
        splitDone.countDown();
        reader.join(30000);
        assertFalse(reader.isAlive());
        assertTrue(errors.isEmpty(), "обход упал: " + errors);
        assertEquals(model.size(), collected.size(), "потеря ключей в обходе");
    }
}
