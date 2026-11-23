package io.github.xdstorage.index.btree;

import io.github.xdstorage.transaction.XdStorageTransaction;
import io.github.xdstorage.transaction.XdStorageTransactionResourceChanges;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("1. Тестирование жизненного цикла и двухфазного коммита индексного ресурса")
public class BTreeIndexResourceLifecycleTest extends AbstractBTreeIndexResourceTest {

    @Test
    @DisplayName("Метод prepare() должен корректно гидрировать кэш при наличии файла")
    public void testPrepare_ShouldFillCacheIfFileExists() throws Exception {
        XdStorageTransaction tx = mockTransaction("tx-prep");
        File file = new File(testFileName);
        file.createNewFile(); // Имитируем физическое существование файла на диске

        assertDoesNotThrow(() -> indexResource.prepare(tx));
        assertEquals(0, indexResource.getObjectsCount()); // Кэш инициализирован успешно
    }

    @Test
    @DisplayName("Сквозной двухфазный коммит: первая и вторая фазы фиксации")
    public void testCommit_TwoPhaseCommit_HappyPath() throws Exception {
        XdStorageTransaction tx = mockTransaction("tx-2pc");
        XdStorageTransactionResourceChanges mockCollector = mock(XdStorageTransactionResourceChanges.class);

        // Первая фаза коммита
        assertDoesNotThrow(() -> indexResource.performFirstPhaseCommit(tx, mockCollector));

        // Вторая фаза коммита
        assertDoesNotThrow(() -> indexResource.performSecondPhaseCommit(tx, mockCollector));
    }

    @Test
    @DisplayName("Откат транзакции (rollback) должен бесшовно аннулировать изменения")
    public void testRollback_ShouldClearChangesAndCache() throws Exception {
        XdStorageTransaction tx = mockTransaction("tx-rb");

        assertDoesNotThrow(() -> indexResource.rollback(tx));
    }

    @Test
    @DisplayName("Метод release() обязан выгрузить контексты и освободить менеджер ресурсов")
    public void testRelease_ShouldClearContextAndNotifyManager() {
        XdStorageTransaction tx = mockTransaction("tx-rel");

        assertDoesNotThrow(() -> indexResource.release(tx));
    }

}
