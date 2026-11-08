package io.github.xdstorage.observing;

import io.github.xdstorage.utils.XdStorageObjectUtils;

import java.util.Collections;
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

    // WRAPLEAK (docs/tasks/2.1.0/46): the segments hold WEAK REFERENCE VALUES. The
    // historical WeakHashMap<Object, wrapper> leaked: the map's VALUE (the wrapper)
    // strongly references the map's KEY (the wrapped domain object — the generated
    // wrapper holds its target), so the key never became weakly-reachable and no entry
    // was ever evicted — every (class, object) registration without a landing id leaked
    // the wrapper, the object and its observer forever (surviving storage.shutdown()).
    // The value is now a WeakReference: the wrapper lives exactly as long as somebody
    // holds it (the owning transaction), and a discarded registration is reclaimed by
    // the GC; the dead entries are cleaned eagerly by the lookup.
    private static final Map<Class<?>, Map<Object, java.lang.ref.WeakReference<IXdStorageIdObservableWrapper>>> wrappers = new ConcurrentHashMap<>();

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
        final Map<Object, java.lang.ref.WeakReference<IXdStorageIdObservableWrapper>> clWrappers = wrappers.computeIfAbsent(cl,
                k -> Collections.synchronizedMap(new WeakHashMap<>()));

        // SILENTFAIL (docs/tasks/2.1.0/35): проверка-генерация-вставка — КОМПОЗИТНОЕ
        // действие. Быстрый путь (обертка уже есть) — без блокировки: горячий контур
        // записи не должен оплачивать сегментную блокировку на каждый вызов. Промах
        // берет сегментную блокировку и повторно проверяет внутри — гонка
        // check-then-act (два потока генерировали две обертки, побеждала последняя,
        // наблюдатели дублировались) закрыта для контура создания.
        // WRAPLEAK: a dead weak reference (the wrapper reclaimed — its id never landed and
        // nobody holds it) is removed eagerly here.
        IXdStorageIdObservableWrapper wrapper = null;
        final java.lang.ref.WeakReference<IXdStorageIdObservableWrapper> cached = clWrappers.get(object);
        if (cached != null) {
            wrapper = cached.get();
            if (wrapper == null) {
                clWrappers.remove(object);
            }
        }
        if (wrapper != null) {
            return wrapper;
        }

        synchronized (clWrappers) {
            final java.lang.ref.WeakReference<IXdStorageIdObservableWrapper> rechecked = clWrappers.get(object);
            wrapper = rechecked != null ? rechecked.get() : null;
            if (wrapper != null) {
                return wrapper;
            }
            if (rechecked != null) {
                clWrappers.remove(object);
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

            clWrappers.put(object, new java.lang.ref.WeakReference<>(wrapper));
            return wrapper;
        }
    }
}
