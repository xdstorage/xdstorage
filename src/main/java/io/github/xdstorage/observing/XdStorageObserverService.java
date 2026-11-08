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

    // WRAPLEAK (docs/tasks/2.1.0/46): the values are STRONG by contract — the deferred-id
    // consumers (the search finalization, the FK waiters, the cascade persistence) rely on
    // "one stable wrapper per (class, object) until the id lands": the CI regression
    // 425d7e6 proved a weak-value holder lets the GC collect a wrapper between a
    // deferred-flow registration and the id assignment, silently detaching every observer
    // registered on it. The LEAK (the never-id wrappers) is fixed by the EXPLICIT
    // deregistration on the transaction completion instead: the workspace's contour
    // (finishTransaction — the commit/rollback/rollbackFailedCommit paths and the task-36
    // sweeps through the same rollback) calls deregister(wrapper) for every unidentified
    // wrapper it discards. A registration outside any transaction context lives until its
    // id lands — the caller's lifecycle by contract.
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

        // Атомарно инициализируем потокобезопасный сегмент для конкретного доменного класса
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

    /**
     * WRAPLEAK (docs/tasks/2.1.0/46): the explicit deregistration for the transaction
     * completion paths. Removes the registry entry when the owning transaction finished
     * without assigning the id — the failure-side counterpart of the one-shot success
     * observer. The entry is removed only when it still maps to THIS wrapper (a newer
     * registration for the same object is never dropped).
     *
     * @param wrapper the wrapper being discarded by a completing transaction
     */
    public static void deregister(final IXdStorageIdObservableWrapper wrapper) {
        if (wrapper == null) {
            return;
        }
        try {
            final Object target = io.github.xdstorage.utils.XdStorageObjectUtils.getWrappedObjectOrSameObject(wrapper);
            final Map<Object, IXdStorageIdObservableWrapper> segment = wrappers.get(target.getClass());
            if (segment != null) {
                synchronized (segment) {
                    // the IDENTITY-strict removal: the generated wrappers override equals
                    // by their wrapped target, so remove(key, value) would match a NEWER
                    // registration for the same object — only the exact wrapper's entry
                    // is dropped (a renewed registration survives a stale deregistration).
                    if (segment.get(target) == wrapper) {
                        segment.remove(target);
                    }
                }
            }
        } catch (final Exception ignored) {
            // the deregistration is a cleanup: never mask a completion path with it
        }
    }
}
