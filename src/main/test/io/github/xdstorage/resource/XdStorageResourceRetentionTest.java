package io.github.xdstorage.resource;

import io.github.xdstorage.IXdFileStorage;
import io.github.xdstorage.IXdStorage;
import io.github.xdstorage.XdStorageProvider;
import io.github.xdstorage.annotations.XdStorageObjectId;
import io.github.xdstorage.annotations.XdStorageObjectIdIndexType;
import io.github.xdstorage.annotations.XdStorageObjectPolicy;
import io.github.xdstorage.index.XdStorageIndexType;
import io.github.xdstorage.XdStoragePolicy;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.lang.reflect.Field;
import java.util.Collection;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R-55: ресурсы УДЕРЖИВАЮТСЯ после обнуления счетчика ссылок (ретеншн).
 * Раньше последний release выкидывал resource-объект из карты: следующий захват
 * создавал НОВЫЙ инстанс с пустым кэшем записей и prepared=false — файл заново
 * парсился с диска (горячие ноды Б+Дерева перемалывали десятки тысяч parse-циклов),
 * возникал дуализм «осиротевший ресурс в живой транзакции против свежего,
 * читающего устаревший диск», а recreate сбрасывал слотовый счетчик фрагмента.
 * Регрессионный пин: после release ресурс обязан остаться тем же инстансом
 * с нетронутым кэшем записей.
 */
public class XdStorageResourceRetentionTest {

    private static final String STORAGE_NAME = "retention-test-" + System.nanoTime();
    private static final String STORAGE_DIR = "./target/retention-db-" + System.nanoTime();

    private static IXdFileStorage storage;
    private static XdStorageAbstractResourcesManager manager;

    @XdStorageObjectPolicy(policy = XdStoragePolicy.StoreAsClassObjects)
    @XdStorageObjectIdIndexType(indexType = XdStorageIndexType.BTree, t = 4)
    public static class RetainedEntity {
        @XdStorageObjectId
        private Long id;
        private String value;

        public RetainedEntity() {}

        public RetainedEntity(final String value) {
            this.value = value;
        }

        public Long getId() { return id; }
        public void setId(final Long id) { this.id = id; }
        public String getValue() { return value; }
        public void setValue(final String value) { this.value = value; }
    }

    @Test
    @DisplayName("release последним держателем НЕ выбрасывает ресурс: тот же инстанс, кэш жив")
    public void releasedResourceIsRetainedWithItsRecordCache() throws Exception {
        deleteDir(new File(STORAGE_DIR));
        storage = XdStorageProvider.newOrGetFileStorage(STORAGE_NAME, STORAGE_DIR, 250);
        try {
            final IXdStorage txStorage = (IXdStorage) storage;
            io.github.xdstorage.transaction.IXdStorageTransaction tx = txStorage.beginTransaction(100L);
            try {
                for (int i = 1; i <= 3; ++i) {
                    txStorage.save(new RetainedEntity("v" + i), tx);
                }
                tx.commit();
            } catch (final Throwable t) {
                tx.rollback();
                throw t;
            }

            manager = managerOf(txStorage);
            runRetentionAssertions(txStorage);
        } finally {
            if (storage != null) {
                storage.shutdown();
            }
            deleteDir(new File(STORAGE_DIR));
        }
    }

    private static void runRetentionAssertions(final IXdStorage txStorage) throws Exception {
        final Object resourceId = findEntityResourceKey();

        final IXdStorageResourceObject<?> resource = managerResources().get(resourceId);
        assertNotNull(resource, "Ресурс entity-фрагмента обязан присутствовать после коммита");

        final Object cacheBefore = recordCacheOf(resource);
        final long countBefore = objectsCountOf(resource);
        assertEquals(3L, countBefore, "В кэше ресурса обязаны жить 3 сущности");

        // Последний держатель отпускает ресурс
        manager.releaseResource(resource);

        // РЕТЕНШН: ресурс остался в карте, тот же инстанс, кэш не потерян
        final IXdStorageResourceObject<?> retained = managerResources().get(resourceId);
        assertNotNull(retained, "R-55: ресурс обязан удерживаться после release (recreate = parse-шторм + дуализм)");
        assertSame(resource, retained, "Обязан вернуться ТОТ ЖЕ инстанс ресурса");
        assertSame(cacheBefore, recordCacheOf(retained), "Кэш записей обязан пережить release");
        assertEquals(countBefore, objectsCountOf(retained), "Записи обязаны пережить release");

        // Функциональная санити: данные читаются из удержанного ресурса
        io.github.xdstorage.transaction.IXdStorageTransaction tx = txStorage.beginTransaction(100L);
        try {
            final Collection<RetainedEntity> all = txStorage.load(RetainedEntity.class, tx);
            assertEquals(3, all.size());
            tx.commit();
        } catch (final Throwable t) {
            tx.rollback();
            throw t;
        }
    }

    // =========================================================================

    private static XdStorageAbstractResourcesManager managerOf(final IXdStorage liveStorage) throws Exception {
        final Field servicesField = liveStorage.getClass().getDeclaredField("services");
        servicesField.setAccessible(true);
        final io.github.xdstorage.services.XdStorageServicesLocator services =
                (io.github.xdstorage.services.XdStorageServicesLocator) servicesField.get(liveStorage);
        return services.getResourcesManager();
    }

    @SuppressWarnings("unchecked")
    private static Map<Object, IXdStorageResourceObject<?>> managerResources() throws Exception {
        final Field resourcesField = XdStorageAbstractResourcesManager.class.getDeclaredField("resources");
        resourcesField.setAccessible(true);
        return (Map<Object, IXdStorageResourceObject<?>>) resourcesField.get(manager);
    }

    private static Object findEntityResourceKey() throws Exception {
        for (final Map.Entry<Object, IXdStorageResourceObject<?>> entry : managerResources().entrySet()) {
            // Только DATA-ресурс доменного класса (не индексные ресурсы: их id
            // тоже содержит имя класса, но объекты там — деревья индекса)
            if (RetainedEntity.class.equals(entry.getValue().getObjectsClass())) {
                return entry.getKey();
            }
        }
        throw new IllegalStateException("Ресурс " + RetainedEntity.class.getSimpleName() + " не найден в карте ресурсов");
    }

    private static Object recordCacheOf(final IXdStorageResourceObject<?> resource) throws Exception {
        final Field cacheField = XdStorageAbstractResource.class.getDeclaredField("cache");
        cacheField.setAccessible(true);
        return cacheField.get(resource);
    }

    private static long objectsCountOf(final IXdStorageResourceObject<?> resource) throws Exception {
        final XdStorageResourceCache cache = (XdStorageResourceCache) recordCacheOf(resource);
        return cache.getObjectsCount();
    }

    private static void deleteDir(final File file) {
        final File[] contents = file.listFiles();
        if (contents != null) {
            for (final File f : contents) {
                deleteDir(f);
            }
        }
        file.delete();
    }
}
