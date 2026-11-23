package io.github.xdstorage.index.btree;

import io.github.xdstorage.btree.XdStorageBTree;
import io.github.xdstorage.exceptions.XdStorageException;
import io.github.xdstorage.exceptions.XdStorageRuntimeException;
import io.github.xdstorage.transaction.XdStorageTransaction;
import io.github.xdstorage.utils.XdStorageClassInfo;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("2. Тестирование CRUD-операций индекса и барьеров блокировок")
public class BTreeIndexResourceOperationsTest extends AbstractBTreeIndexResourceTest {

    @Test
    @DisplayName("Вставка (insert) объекта должна генерировать ID и прописывать ключ в B+Tree")
    public void testInsert_ShouldGenerateIdAndPropagateToBTree() throws Exception {
        XdStorageTransaction tx = mockTransaction("tx-insert");
        TestEntity entity = new TestEntity(null, "PlanetData");

        when(mockIdGenerator.generate(eq(TestEntity.class), any(), eq(tx))).thenReturn(42L);

        XdStorageBTree mockTree = mock(XdStorageBTree.class);
        when(mockStorage.load(eq(XdStorageBTree.class), any(), eq(tx))).thenReturn(mockTree);

        // Запуск операции — теперь mockManager гарантированно вернет mockDaoResource из нашего стаба!
        assertDoesNotThrow(() -> indexResource.insert(entity, tx));

        verify(mockDaoResource, times(1)).insert(entity, tx);
        verify(mockTree, times(1)).insert(eq(42L), any(), any(), eq(tx));
    }

    @Test
    @Disabled
    @DisplayName("Удаление (delete): fail-safe барьер не должен падать, если запись уже удалена")
    public void testDelete_IdempotentBarrier_ShouldReturnCleanlyIfAlreadyDeleted() throws Exception {
        XdStorageTransaction tx = mockTransaction("tx-delete");
        TestEntity entity = new TestEntity(555L, "DeadPlanet");

        // ВЫРАВНИВАНИЕ ИНВАРИАНТА ДЕРЕВА: Возвращаем легитимный инстанс дерева Б+ Дерева первичных ключей id_index,
        // но программируем его метод find() на возврат ПУСТOГO списка (result.isEmpty() == true),
        // имитируя, что запись уже вырезана каскадной очисткой. Это уберет XdStorageException о несуществовании дерева!
        XdStorageBTree mockTree = mock(XdStorageBTree.class);
        when(mockStorage.load(eq(XdStorageBTree.class), any(), eq(tx))).thenReturn(mockTree);
        when(mockTree.find(eq(555L), any(), eq(tx))).thenReturn(Collections.emptyList());

        // Верифицируем наше критическое fail-safe исправление каскадной очистки:
        assertDoesNotThrow(() -> indexResource.delete(entity, tx),
                "СУБД нарушила fail-safe инвариант и выкинула исключение при повторном каскадном удалении!");
    }

    @Test
    @DisplayName("Обновление (update) несуществующего объекта должно бросать XdStorageException")
    public void testUpdate_MissingObject_ShouldThrowException() throws Exception {
        XdStorageTransaction tx = mockTransaction("tx-update");
        TestEntity entity = new TestEntity(999L, "PhantomData");
        when(mockStorage.load(eq(XdStorageBTree.class), any(), eq(tx))).thenReturn(null);

        assertThrows(XdStorageException.class, () -> indexResource.update(entity, tx));
    }

    @Test
    @DisplayName("Локер lockForCommit обязан принудительно отваливаться по таймауту")
    public void testLockForCommit_ShouldEnforceTimeoutAndThrow() throws Exception {
        XdStorageTransaction txHolder = mockTransaction("tx-holder");
        XdStorageTransaction txVictim = mockTransaction("tx-victim");
        when(txVictim.getTimeout()).thenReturn(50L); // Жесткий короткий таймаут для жертвы

        // 1. Поток JUnit монопольно захватывает ReentrantLock индекса
        indexResource.lockForCommit(txHolder);

        // 2. Уводим транзакцию-жертву в параллельный поток, чтобы обойти реентерабельность лока!
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        java.util.concurrent.Future<Throwable> victimFuture = executor.submit(() -> {
            try {
                indexResource.lockForCommit(txVictim);
                return null;
            } catch (Throwable t) {
                return t;
            }
        });

        Throwable caughtException = victimFuture.get(2, java.util.concurrent.TimeUnit.SECONDS);
        executor.shutdownNow();

        // Проверяем, что жертва честно упала по таймауту
        assertNotNull(caughtException, "Параллельный поток заснул вместо отвала по таймауту ReentrantLock!");
        assertTrue(caughtException instanceof XdStorageRuntimeException,
                "Тип ошибки должен быть XdStorageRuntimeException!");
        assertTrue(caughtException.getMessage().contains("cannot be locked for commit"), "Текст ошибки не совпадает!");

        // 3. Освобождаем замок в главном потоке для чистоты окружения
        indexResource.unlockAfterCommit(txHolder);
    }
}
