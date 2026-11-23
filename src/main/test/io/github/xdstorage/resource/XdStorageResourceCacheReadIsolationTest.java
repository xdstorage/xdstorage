package io.github.xdstorage.resource;

import io.github.xdstorage.IXdStorageWatcher;
import io.github.xdstorage.transaction.XdStorageTransaction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("Unit-Группа 3: Извлечение данных и MVCC Snapshot Isolation")
public class XdStorageResourceCacheReadIsolationTest extends AbstractXdStorageResourceCacheTest {

    @BeforeEach
    public void fillBaseData() {
        resourceCache.fillCache(java.util.Arrays.asList(
                new TestEntity(1L, "Stable_1"),
                new TestEntity(2L, "Stable_2")
        ));
    }

    @Test
    @DisplayName("read(transaction) без параметров должен возвращать полную коллекцию снимка")
    public void testRead_FullSymmetricScan() throws Exception {
        XdStorageTransaction tx = createTx("TX-READ", 1000L);

        Collection<TestEntity> results = resourceCache.read(tx);

        assertEquals(2, results.size());
        verify(tx, times(1)).startCriticalSection();
    }

    @Test
    @DisplayName("read(transaction, predicate) должен фильтровать выборку по условию предиката")
    public void testRead_WithPredicateFiltering() throws Exception {
        XdStorageTransaction tx = createTx("TX-PREDICATE", 1000L);

        Collection<TestEntity> results = resourceCache.read(tx, node -> node.getId() == 1L);

        assertEquals(1, results.size());
        assertEquals("Stable_1", results.iterator().next().getValue());
    }

    @Test
    @DisplayName("readUnsafe() должен вычитывать транзитные незакоммиченные мутации текущей транзакции")
    public void testReadUnsafe_ShouldExposeDirtyChangesForOwner() throws Exception {
        XdStorageTransaction tx = createTx("TX-UNSAFE", 1000L);

        TestEntity updated = new TestEntity(1L, "Mutated_Unsafe");
        resourceCache.update(updated, tx);

        Collection<TestEntity> unsafeResults = resourceCache.readUnsafe(tx);

        TestEntity target = unsafeResults.stream().filter(n -> n.getId() == 1L).findFirst().orElse(null);
        assertNotNull(target);
        assertEquals("Mutated_Unsafe", target.getValue());
    }

    @Test
    @DisplayName("read(id, transaction) плоского поиска по ключу должен поддерживать Identity контракт")
    public void testRead_SingleKey_ShouldEnforceIdentity() throws Exception {
        XdStorageTransaction tx = createTx("TX-SINGLE", 1000L);

        Object firstRead = resourceCache.read(1L, tx);
        Object secondRead = resourceCache.read(1L, tx);

        assertNotNull(firstRead);
        assertSame(firstRead, secondRead, "Плоский метод чтения нарушил Identity Map контракт!");
    }

    @Test
    @DisplayName("readByReference() должен атомарно наполнять ленивую прокси-обертку СУБД данными")
    public void testReadByReference_LazyLoadingMapping() throws Exception {
        XdStorageTransaction tx = createTx("TX-REF", 1000L);
        TestEntity lazyProxyRef = new TestEntity(2L, null);

        resourceCache.readByReference(lazyProxyRef, tx);

        assertNotNull(lazyProxyRef.getValue());
        assertEquals("Stable_2", lazyProxyRef.getValue());
    }

    @Test
    @DisplayName("watch() должен транслировать Snapshot-снимки объектов в триггерный коллбэк воркера")
    public void testWatch_ShouldForwardEventsToWatcher() throws Exception {
        XdStorageTransaction tx = createTx("TX-WATCH", 1000L);
        List<TestEntity> watchedNodes = new ArrayList<>();

        resourceCache.watch(tx, new IXdStorageWatcher<TestEntity>() {
            @Override
            public void watch(TestEntity wrappedObject) {
                watchedNodes.add(wrappedObject);
            }
        });

        assertEquals(2, watchedNodes.size());
    }
}
