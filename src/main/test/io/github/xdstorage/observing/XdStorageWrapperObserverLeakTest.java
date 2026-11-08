package io.github.xdstorage.observing;

import io.github.xdstorage.observing.IXdStorageIdObservableWrapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.ref.WeakReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WRAPLEAK (docs/tasks/2.1.0/46): реестр id-observable оберток не имеет права удерживать
 * обертку (и завернутый доменный объект) после того, как транзакция завершилась без
 * назначения идентификатора. Историческая утечка: WeakHashMap, чье ЗНАЧЕНИЕ (обертка)
 * сильно ссылается на КЛЮЧ (завернутый объект) — запись никогда не выдыхается, реестр
 * растет пропорционально неудачным/отброшенным регистрациям и переживает shutdown.
 *
 * <p>Проверяемые контуры (аудит задачи): (a) успех — id назначен, запись снята
 * one-shot-наблюдателем; (b) id не назначен, ссылки отброшены — обертка досягаема только
 * через реестр → после GC реестр обязан ее отпустить; (c) повторный запрос того же
 * объекта ДО назначения id возвращает ту же обертку (контракт стабильности).</p>
 */
@DisplayName("WRAPLEAK: реестр observable-оберток отпускает обертки без идентификаторов")
class XdStorageWrapperObserverLeakTest {

    /** A minimal domain object: the registry keys by the object identity. */
    static class UnidentifiedEntity {
    }

    /** An entity WITH an identifier: the success path fires the generated id setter. */
    static class IdentifiedEntity {
        @io.github.xdstorage.annotations.XdStorageObjectId
        private Long id;

        public Long getId() { return id; }
        public void setId(final Long id) { this.id = id; }
    }

    @Test
    @DisplayName("Обертка без назначенного id освобождается GC после отброса ссылок (репродукция утечки)")
    void wrapperWithoutIdIsReclaimable() throws Exception {
        io.github.xdstorage.entities.foreignkeys.ChildAutoId object = new io.github.xdstorage.entities.foreignkeys.ChildAutoId();
        IXdStorageIdObservableWrapper wrapper = XdStorageObserverService.getObservableWrapper(object);
        assertNotNull(wrapper);

        final WeakReference<IXdStorageIdObservableWrapper> wrapperRef = new WeakReference<>(wrapper);
        final WeakReference<io.github.xdstorage.entities.foreignkeys.ChildAutoId> objectRef = new WeakReference<>(object);

        // The application discards the object mid-registration: the id never lands.
        wrapper = null;
        object = null;
        forceGc();

        assertNull(wrapperRef.get(),
                "обертка, чей id так и не был назначен, обязана стать досягаемой только для GC — "
                        + "реестр удерживает ее сильно (значение WeakHashMap сильно ссылается на ключ)");
        assertNull(objectRef.get(),
                "завернутый доменный объект удерживается оберткой из реестра — утечка пары (класс, объект)");
    }

    @Test
    @DisplayName("Успех: назначение id снимает запись реестра — новый запрос создает новую обертку")
    void idSetRemovesRegistryEntry() throws Exception {
        io.github.xdstorage.entities.foreignkeys.ChildAutoId object = new io.github.xdstorage.entities.foreignkeys.ChildAutoId();
        IXdStorageIdObservableWrapper first = XdStorageObserverService.getObservableWrapper(object);
        assertNotNull(first);

        // The deferred-id lands through the generated id setter: the one-shot observer
        // removes the registry entry.
        first.getClass().getMethod("setId", Long.class).invoke(first, 42L);

        final WeakReference<IXdStorageIdObservableWrapper> firstRef = new WeakReference<>(first);
        first = null;
        forceGc();
        assertNull(firstRef.get(), "после снятия записи реестра обертка обязана освободиться");

        // The next registration for the same object is a FRESH wrapper (the old entry gone).
        IXdStorageIdObservableWrapper second = XdStorageObserverService.getObservableWrapper(object);
        assertNotNull(second);
        assertNotSame(firstRef.get(), second);
    }

    @Test
    @DisplayName("Стабильность: повторный запрос до назначения id возвращает ту же обертку")
    void repeatedRequestYieldsSameWrapperWhileEntryLives() {
        io.github.xdstorage.entities.foreignkeys.ChildAutoId object = new io.github.xdstorage.entities.foreignkeys.ChildAutoId();
        IXdStorageIdObservableWrapper first = XdStorageObserverService.getObservableWrapper(object);
        IXdStorageIdObservableWrapper second = XdStorageObserverService.getObservableWrapper(object);
        assertTrue(first == second, "реестр hands out одну стабильную обертку на (класс, объект)");
    }

    private static void forceGc() throws Exception {
        // A few GC rounds with allocation pressure: the WeakReference clearing is not
        // instantaneous; the loop makes the assertion deterministic in practice.
        for (int round = 0; round < 5; ++round) {
            System.gc();
            Thread.sleep(50);
            @SuppressWarnings("unused")
            byte[] pressure = new byte[64 * 1024];
        }
    }

    private static void assertNotNull(final Object value) {
        org.junit.jupiter.api.Assertions.assertNotNull(value);
    }
}
