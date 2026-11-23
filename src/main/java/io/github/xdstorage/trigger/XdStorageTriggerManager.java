package io.github.xdstorage.trigger;

import org.slf4j.LoggerFactory;
import org.slf4j.Logger;
import io.github.xdstorage.resource.XdStorageObjectChange;
import io.github.xdstorage.resource.XdStorageObjectOperationType;
import io.github.xdstorage.transaction.XdStorageTransaction;
import io.github.xdstorage.utils.XdStorageObjectUtils;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The registry and dispatcher of persistence triggers: keeps a per-entity-class list of
 * registered triggers and, at commit time, fires the insert/update/delete triggers matching the
 * recorded changes — inside the changing transaction. The lists are copy-on-write, so trigger
 * lookup and execution are lock-free for every engine thread and registration is safe
 * concurrently with execution.
 *
 * <p>A trigger that throws a runtime exception is logged and rethrown, which fails the commit
 * and rolls the transaction back. A trigger that needs the storage must open and finish its own
 * nested transaction.
 *
 * <p>Engine-internal — not part of the public API.
 */
public class XdStorageTriggerManager {

    private static final Logger log = LoggerFactory.getLogger(XdStorageTriggerManager.class);

    // АРХИТЕКТУРНОЕ ИСПРАВЛЕНИЕ ЯДРА: Переводим списки триггеров на CopyOnWriteArrayList.
    // Это открывает дорогу к абсолютно LOCK-FREE параллельному чтению и выполнению триггеров
    // всеми ForkJoin-потоками СУБД одновременно, полностью ликвидируя дедлоки на фазе коммита!
    private final Map<Class<?>, List<IXdStorageTrigger<?>>> triggers;

    /**
     * Creates a manager with an empty trigger registry.
     */
    public XdStorageTriggerManager() {
        this.triggers = new ConcurrentHashMap<>();
    }

    /**
     * Registers a trigger for its entity class; the triggers of one class fire in registration
     * order. A {@code null} trigger or one without an entity class is silently ignored.
     *
     * @param trigger the trigger to register
     * @param <T>     the entity class the trigger observes
     */
    public <T> void registerTrigger(final IXdStorageTrigger<T> trigger) {
        if (trigger == null || trigger.getClazz() == null) return;

        // Атомарно и безопасно инициализируем потокобезопасный CopyOnWriteArrayList
        List<IXdStorageTrigger<?>> list = triggers.computeIfAbsent(trigger.getClazz(),
                k -> new CopyOnWriteArrayList<>());

        list.add(trigger);
    }

    /**
     * Fires the triggers for every recorded change, in change order: an insert change runs the
     * insert triggers, an update change the update triggers, a delete change the delete
     * triggers. The first failing trigger aborts the replay.
     *
     * @param transaction the transaction the changes belong to
     * @param changes     the recorded changes of the commit; must not be {@code null}
     * @throws NullPointerException when {@code changes} is {@code null} (the canonical failure
     *                              for an invalid call context)
     */
    public void performTriggers(final XdStorageTransaction transaction, final Collection<XdStorageObjectChange> changes)
            {
        // АЛГОРИТМИЧЕСКОЕ ВЫРАВНИВАНИЕ КОНТРАКТОВ: Убираем заглушку "if(changes == null) return;".
        // Прямой вызов stream() на коллекции изменений гарантирует естественный и каноничный
        // выброс NullPointerException при передаче некорректного null-контекста, что полностью
        // удовлетворяет требованиям JUnit 5 тестов спецификации триггерного контура!
        changes.stream().forEach(change -> {
            performTriggers(transaction, change.type, change.oldObject, change.newObject);
        });
    }

    private <T> void performTriggers(final XdStorageTransaction transaction, final XdStorageObjectOperationType type,
            final T oldObject, final T newObject) {
        if (type == XdStorageObjectOperationType.Insert || type == XdStorageObjectOperationType.INSERT) {
            performInsertTriggers(transaction, newObject);
        } else if (type == XdStorageObjectOperationType.Update || type == XdStorageObjectOperationType.UPDATE) {
            performUpdateTriggers(transaction, oldObject, newObject);
        } else if (type == XdStorageObjectOperationType.Delete || type == XdStorageObjectOperationType.DELETE) {
            performDeleteTriggers(transaction, oldObject);
        }
    }

    @SuppressWarnings("unchecked")
    private <T> void performInsertTriggers(final XdStorageTransaction transaction, final T newObject) {
        if (newObject == null) return;
        final List<IXdStorageTrigger<?>> list = triggers.get(XdStorageObjectUtils.getEntityClass(newObject.getClass()));

        // LOCK-FREE ИТЕРИРОВАНИЕ: Никаких synchronized(list) барьеров! Потоки читают Snapshot списка атомарно.
        if (list != null) {
            for (final IXdStorageTrigger<?> trigger : list) {
                if (trigger.getType() == XdStorageObjectOperationType.Insert || trigger.getType() ==
                        XdStorageObjectOperationType.INSERT) {
                    try {
                        ((IXdStorageTrigger<T>) trigger)
                                .perform(null, XdStorageObjectUtils.getWrappedObjectOrSameObject(newObject),
                                        transaction);
                    } catch (final RuntimeException e) {
                        log.error("Trigger {} failed on INSERT of {} — rethrowing (the transaction will roll back)",
                                trigger.getClass().getName(), newObject.getClass().getName(), e);
                        throw e;
                    }
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private <T> void performUpdateTriggers(final XdStorageTransaction transaction, final T oldObject, final T newObject)
            {
        if (oldObject == null) return;
        final List<IXdStorageTrigger<?>> list = triggers.get(XdStorageObjectUtils.getEntityClass(oldObject.getClass()));

        if (list != null) {
            for (final IXdStorageTrigger<?> trigger : list) {
                if (trigger.getType() == XdStorageObjectOperationType.Update || trigger.getType() ==
                        XdStorageObjectOperationType.UPDATE) {
                    try {
                        ((IXdStorageTrigger<T>) trigger)
                                .perform(XdStorageObjectUtils.getWrappedObjectOrSameObject(oldObject),
                                        XdStorageObjectUtils.getWrappedObjectOrSameObject(newObject), transaction);
                    } catch (final RuntimeException e) {
                        log.error("Trigger {} failed on UPDATE of {} — rethrowing (the transaction will roll back)",
                                trigger.getClass().getName(), newObject.getClass().getName(), e);
                        throw e;
                    }
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private <T> void performDeleteTriggers(final XdStorageTransaction transaction, final T oldObject) {
        if (oldObject == null) return;
        final List<IXdStorageTrigger<?>> list = triggers.get(XdStorageObjectUtils.getEntityClass(oldObject.getClass()));

        if (list != null) {
            for (final IXdStorageTrigger<?> trigger : list) {
                if (trigger.getType() == XdStorageObjectOperationType.Delete || trigger.getType() ==
                        XdStorageObjectOperationType.DELETE) {
                    try {
                        ((IXdStorageTrigger<T>) trigger).perform(XdStorageObjectUtils
                                .getWrappedObjectOrSameObject(oldObject), null, transaction);
                    } catch (final RuntimeException e) {
                        log.error("Trigger {} failed on DELETE of {} — rethrowing (the transaction will roll back)",
                                trigger.getClass().getName(), oldObject.getClass().getName(), e);
                        throw e;
                    }
                }
            }
        }
    }
}
