package io.github.xdstorage.serialization.yaml;

import io.github.xdstorage.helpers.XdStorageDefaultSimpleTypeHelper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.StringWriter;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R-43/R-56: write-out сериализует ЖИВЫЕ коллекции нод Б+Дерева. Итерация по живой
 * synchronized-коллекции без монитора рвалась ConcurrentModificationException, когда
 * писатель сериализует ноду, чей guard-tryLock не взялся (нода занята мутатором).
 * XdStorageDefaultObjectsWriter получил снимки под монитором еще в R-43; YAML-писатель
 * (формат ПО УМОЛЧАНИЮ) — только в R-56. Регрессионный пин: параллельная мутация
 * коллекции во время выгрузки не роняет писателя.
 */
public class XdStorageYamlWriterCmeTest {

    @Test
    @DisplayName("writeCollectionInline переживает параллельную мутацию коллекции без CME")
    public void collectionWriteSurvivesConcurrentMutation() throws Exception {
        final List<String> live = Collections.synchronizedList(new LinkedList<>());
        for (int i = 0; i < 64; ++i) {
            live.add("item-" + i);
        }

        final AtomicBoolean stop = new AtomicBoolean(false);
        final AtomicLong mutations = new AtomicLong();
        final Thread mutator = new Thread(() -> {
            long n = 0;
            while (!stop.get()) {
                synchronized (live) {
                    live.add("mutant-" + n);
                    if (live.size() > 96) {
                        live.remove(0);
                    }
                }
                mutations.incrementAndGet();
                ++n;
            }
        }, "cme-mutator");
        mutator.setDaemon(true);
        mutator.start();

        try {
            final XdStorageDefaultSimpleTypeHelper simpleTypeHelper = new XdStorageDefaultSimpleTypeHelper();
            int writes = 0;
            while (writes < 500) {
                final StringWriter out = new StringWriter();
                final XdStorageYamlEmitter emitter = new XdStorageYamlEmitter(out);
                XdStorageYamlBlockContainersWriter.writeCollectionInline(
                        String.class, live, emitter, null, simpleTypeHelper, null);
                ++writes;
            }
            assertTrue(writes >= 100, "Ожидалось не менее 100 выгрузок, выполнено: " + writes);
        } catch (final java.util.ConcurrentModificationException cme) {
            throw new AssertionError(
                    "R-56: итерация YAML-писателя обязана идти по монитору-снимку коллекции, CME недопустим", cme);
        } finally {
            stop.set(true);
            mutator.join(100L);
        }
        assertFalse(mutator.isAlive(), "Мутатор обязан завершиться");
    }
}
