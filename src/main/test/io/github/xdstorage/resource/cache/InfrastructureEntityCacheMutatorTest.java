package io.github.xdstorage.resource.cache;

import io.github.xdstorage.btree.XdStorageBTree;
import io.github.xdstorage.btree.XdStorageBTreeId;
import io.github.xdstorage.btree.XdStorageBTreeNode;
import io.github.xdstorage.exceptions.XdStorageException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Интеграционные тесты мутатора кэша: Инфраструктура B+Tree")
public class InfrastructureEntityCacheMutatorTest extends AbstractCacheMutatorIntegrationTest {

    private XdStorageBTreeNode btreeNode;
    private final Long nodeId = 555L;

    @BeforeEach
    @Override
    void setUp() {
        super.setUp();
        cacheContext.switchToInfrastructureMode(XdStorageBTreeNode.class);

        // Поднимаем настоящие инстансы структурного дерева и ноды
        XdStorageBTreeId treeId = new XdStorageBTreeId(AbstractCacheMutatorIntegrationTest.TestDomainEntity.class, "idx_btree_nodes");
        XdStorageBTree btree = new XdStorageBTree(treeId, false, 3);
        btreeNode = new XdStorageBTreeNode(btree, null);
        btreeNode.setId(nodeId);
    }

    @Test
    @DisplayName("1) Вставка инфраструктурной ноды B+ дерева")
    void testInfraNodeInsert() throws XdStorageException {
        mutator.insertInternal(btreeNode, transaction);

        XdStorageCacheRecord record = cacheContext.records().get(nodeId);
        assertNotNull(record);
        assertEquals(btreeNode, record.getNewObject());

        assertEquals(1, listener.insertCalls.size());
        assertEquals(btreeNode, listener.insertCalls.get(0));
    }

    @Test
    @DisplayName("2) Вставка, а затем обновление ноды (Проверка идентичности инстанса)")
    void testInfraNodeInsertThenUpdate() throws XdStorageException {
        mutator.insertInternal(btreeNode, transaction);

        // Симулируем реальное изменение графа дерева в памяти: добавляем разделитель яруса в LinkedList ноды
        btreeNode.getKeys().add(888);
        mutator.update(btreeNode, transaction);

        XdStorageCacheRecord record = cacheContext.records().get(nodeId);

        // Критический инвариант: в кэше должен лежать ТОТ ЖЕ живой инстанс.
        // Если бы мутатор клонировал объект, добавленный ключ 888 потерялся бы при коммите сессии!
        assertEquals(btreeNode, record.getNewObject());
        assertEquals(1, ((XdStorageBTreeNode) record.getNewObject()).getKeys().size());
    }

    @Test
    @DisplayName("3) Вставка, обновление, а затем удаление инфраструктурной ноды")
    void testInfraNodeInsertUpdateThenDelete() throws XdStorageException {
        mutator.insertInternal(btreeNode, transaction);
        mutator.update(btreeNode, transaction);

        mutator.delete(nodeId, transaction);

        // Согласно правилу R-40 для инфраструктурных кэшей:
        // Запись НЕ удаляется из CHM через remove(), а переводится в маркер markDelete(state=locked, change=delete),
        // чтобы живой инстанс продолжал обслуживать связи структуры дерева!
        XdStorageCacheRecord record = cacheContext.records().get(nodeId);
        assertNotNull(record);
        assertTrue(record.isDeleteChange());
        assertNull(record.getNewObject());

        assertEquals(1, listener.deleteCalls.size());
    }

    @Test
    @DisplayName("4) Механизм Self-Heal (Автозаправка призраков структуры при обновлении)")
    void testInfraNodeGhostSelfHeal() throws XdStorageException {
        // Условия: записи ноды изначально нет в CHM (ситуация холодного выселения или отката параллельного сплита)
        assertFalse(cacheContext.records().containsKey(nodeId));

        // Попытка вызвать update() для структурного объекта без record
        // должна спровоцировать автоматический перехват управления и вызов метода cache.insert()
        mutator.update(btreeNode, transaction);

        // Проверяем, что сработал Self-Heal: нода заправилась в кэш со статусом insert
        XdStorageCacheRecord record = cacheContext.records().get(nodeId);
        assertNotNull(record);
        assertTrue(record.isInsertChange());
        assertEquals(1, listener.insertCalls.size());
    }

    @Test
    @DisplayName("5) Релаксация версионных блокировок (R-42) для нод дерева")
    void testInfraNodeRelaxation() throws XdStorageException {
        XdStorageCacheRecord existingRecord = cacheContext.createReadRecord(btreeNode);
        cacheContext.records().put(nodeId, existingRecord);

        // Симулируем жесткий версионный конфликт: закладываем старый timestamp чтения (ts=500 при текущем 1000)
        workspace.readObjectsOf(transaction).put(nodeId,
                new XdStorageCacheObjectChange(btreeNode, XdStorageCacheChange.read, 500L));

        // Для доменного объекта тут гарантированно вылетел бы XdStorageException ("can not be updated").
        // Но для кэша нод дерева (isStructuralNodeCache=true) проверки расслаблены, мутация должна пройти успешно.
        assertDoesNotThrow(() -> mutator.update(btreeNode, transaction));

        assertTrue(existingRecord.isUpdateChange());
        assertEquals(1, listener.updateCalls.size());
    }

    @Test
    @DisplayName("6) Удаление инфраструктурной ноды без записи в кэше")
    void testInfraNodeDeleteMissingRecord() {
        // В обычном кэше удаление отсутствующего ID кидает исключение "does not exists".
        // Для B+ дерева — это валидный тихий возврат (цель пурджа сироты достигнута, система работает дальше).
        assertDoesNotThrow(() -> mutator.delete(nodeId, transaction));
        assertEquals(0, listener.deleteCalls.size());
    }
}
