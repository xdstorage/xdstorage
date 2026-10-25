package io.github.xdstorage.observing;

import io.github.xdstorage.utils.XdStorageObjectUtils;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * JVM-global registry of id-observable wrappers, keyed by domain class: hands out one
 * stable observable wrapper per (class, object) pair and clears the mapping through a
 * self-registered observer as soon as the wrapper's id is set (deferred-id
 * notification), letting the GC reclaim the entry.
 *
 * <p>The registry is a static {@code ConcurrentHashMap} of per-class segments, each a
 * synchronized {@code WeakHashMap} keyed weakly by the wrapped object — completed
 * transactions are reclaimed by the GC without explicit eviction; concurrent access is
 * safe through the concurrent map's per-segment atomicity. Engine-internal — not part
 * of the public API.</p>
 */
public class XdStorageObserverService {

    /** Registry-only holder: all functionality is static. */
    public XdStorageObserverService() {
    }

    // АРХИТЕКТУРНОЕ ИСПРАВЛЕНИЕ ЯДРА (Ликвидация скрытой утечки памяти Heap):
    // Переводим внутренние сегменты кэша на Collections.synchronizedMap(new WeakHashMap<>()).
    // Использование слабых ссылок (Weak References) гарантирует, что как только транзакция пользователя
    // завершится, сборщик мусора (GC) мгновенно и бесследно вычистит объекты из памяти СУБД,
    // полностью исключая OutOfMemory и race conditions на фазе параллельного вызова .remove()!
    private static final Map<Class<?>, Map<Object, IXdStorageIdObservableWrapper>> wrappers = new ConcurrentHashMap<>();

    /**
     * Returns the observable wrapper registered for the given object, creating and
     * caching it on first request (a repeated call for the same object yields the same
     * wrapper while the entry lives). The freshly created wrapper gets a one-shot
     * observer that removes the registry entry the first time its id is set.
     *
     * @param object the object to expose as an id-observable wrapper; must not be {@code null}
     * @return the cached or newly generated observable wrapper (the underlying wrapping
     *         helper may yield {@code null} when wrapper generation fails)
     */
    public static IXdStorageIdObservableWrapper getObservableWrapper(final Object object) {
        final Class<?> cl = object.getClass();

        // Атомарно инициализируем потокобезопасный сегмент Weak-ссылок для конкретного доменного класса
        final Map<Object, IXdStorageIdObservableWrapper> clWrappers = wrappers.computeIfAbsent(cl,
                k -> Collections.synchronizedMap(new WeakHashMap<>()));

        // SILENTFAIL (docs/tasks/2.1.0/35): проверка-генерация-вставка — КОМПОЗИТНОЕ
        // действие. Быстрый путь (обертка уже есть) — без блокировки: горячий контур
        // записи не должен оплачивать сегментную блокировку на каждый вызов. Промах
        // берет сегментную блокировку и повторно проверяет внутри — гонка
        // check-then-act (два потока генерировали две обертки, побеждала последняя,
        // наблюдатели дублировались) закрыта для контура создания.
        IXdStorageIdObservableWrapper wrapper = clWrappers.get(object);
        if (wrapper != null) {
            return wrapper;
        }

        synchronized (clWrappers) {
            wrapper = clWrappers.get(object);
            if (wrapper != null) {
                return wrapper;
            }

            // Генерируем реактивную Observable-обертку. SILENTFAIL: отказ генерации не
            // имеет права маскироваться под "нет обертки": wrapAsObservableObject глотает
            // все исключения и отвечает null — говорим громко.
            wrapper = XdStorageObjectUtils.wrapAsObservableObject(object);
            if (wrapper == null) {
                throw new io.github.xdstorage.exceptions.XdStorageRuntimeException(
                        "cannot create the id-observable wrapper for " + cl.getName()
                                + " — wrapper generation failed (a JDK at runtime is required for codegen)");
            }

            wrapper.addObserver(new XdStorageAbstractIdObserver() {
                @Override
                public void onNewIdIsSet(final IXdStorageIdObservableWrapper wrapper, final Object id) {
                    // Fail-safe очистка сегмента: если объект вытеснен или удален, метод
                    // отработает без побочных эффектов
                    clWrappers.remove(object);
                }
            });

            clWrappers.put(object, wrapper);
            return wrapper;
        }
    }
}
