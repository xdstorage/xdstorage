package io.github.xdstorage.resource;

import io.github.xdstorage.exceptions.XdStorageException;
import io.github.xdstorage.transaction.XdStorageTransaction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("Unit-Группа 2: Транзакционные мутации (Insert, Update, Delete)")
public class XdStorageResourceCacheMutationTest extends AbstractXdStorageResourceCacheTest {

    @Test
    @DisplayName("insert() для сырого POJO должен создавать запись и захватывать замок транзакции")
    public void testInsert_RawPojo_ShouldLockAndRegisterChange() throws Exception {
        XdStorageTransaction tx = createTx("TX-INSERT", 1000L);
        TestEntity node = new TestEntity(100L, "NewNode");

        resourceCache.insert(node, tx);

        assertTrue(resourceCache.hasObject(100L));
        verify(tx, times(1)).startCriticalSection();
    }

    @Test
    @DisplayName("insert() дубликата одного и того же объекта в рамках одной TX должен вежливо игнорироваться")
    public void testInsert_DuplicateInSameTx_ShouldBeIgnored() throws Exception {
        XdStorageTransaction tx = createTx("TX-DUP-INSERT", 1000L);
        TestEntity node = new TestEntity(100L, "Node");

        resourceCache.insert(node, tx);
        assertDoesNotThrow(() -> resourceCache.insert(node, tx),
                "Повторный вызов insert() для той же ноды в одной TX должен вежливо игнорироваться глобальной " +
                        "защитой!");
    }

    // =========================================================================
    // СУБД TDD-СТАБ: Честная живая обертка для обхода Reflection-проверок ядра
    // =========================================================================
    public static class StubObservableWrapper implements io.github.xdstorage.observing.IXdStorageIdObservableWrapper {
        // Утилитный класс СУБД ищет поле, возвращающее оригинальный объект.
        // Мы объявляем его, полностью удовлетворяя getObservableWrapperObjectField()!
        public TestEntity object;
        private final List<io.github.xdstorage.observing.XdStorageAbstractIdObserver> observers = new ArrayList<>();

        public StubObservableWrapper(TestEntity object) { this.object = object; }
        public Object getId() { return object.getId(); }
        public void addObserver(io.github.xdstorage.observing.XdStorageAbstractIdObserver observer) { observers
                .add(observer); }
        public void removeObserver(io.github.xdstorage.observing.XdStorageAbstractIdObserver observer) { observers
                .remove(observer); }
        public List<io.github.xdstorage.observing.XdStorageAbstractIdObserver> getObservers() { return observers; }
    }

    @Test
    @DisplayName("insert() для IXdStorageIdObservableWrapper должен вешать обсервер и работать через onNewIdIsSet()")
    public void testInsert_ObservableWrapper_ShouldRegisterObserverAndDefer() throws Exception {
        XdStorageTransaction tx = createTx("TX-OBSERVER", 1000L);

        // Создаем честный стаб с реальным доменным объектом внутри
        TestEntity payload = new TestEntity(null, "LazyPlanet");
        StubObservableWrapper stubWrapper = new StubObservableWrapper(payload);

        // Передаем стаб в фасад кэша ресурсов
        resourceCache.insert(stubWrapper, tx);

        assertFalse(stubWrapper.getObservers().isEmpty(),
                "Метод insert() не зарегистрировал транзакционный анонимный обсервер СУБД!");

        // Переводим ID в детерминированное состояние и триггерим обсервер СУБД
        payload.setValue("LazyPlanet_Generated");
        stubWrapper.getObservers().get(0).onNewIdIsSet(stubWrapper, 999L);

        assertTrue(resourceCache.hasObject(999L),
                "СУБД не смогла материализовать отложенный инсерт ноды после выставления ID!");
    }

    @Test
    @DisplayName("update() должен выбрасывать исключение, если объект отсутствует в кэше")
    public void testUpdate_NonExistentNode_ShouldThrowException() {
        XdStorageTransaction tx = createTx("TX-UPDATE-ERR", 1000L);
        TestEntity node = new TestEntity(404L, "Ghost");

        assertThrows(XdStorageException.class, () -> resourceCache.update(node, tx));
    }

    @Test
    @DisplayName("delete() должен успешно помечать стейт ноды на удаление")
    public void testDelete_ExistentNode_ShouldMarkDelete() throws Exception {
        XdStorageTransaction tx = createTx("TX-DELETE", 1000L);
        resourceCache.fillCache(Collections.singletonList(new TestEntity(500L, "ToKill")));

        assertDoesNotThrow(() -> resourceCache.delete(500L, tx));
    }
}
