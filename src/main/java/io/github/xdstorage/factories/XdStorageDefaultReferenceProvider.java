package io.github.xdstorage.factories;

import io.github.xdstorage.IXdStorage;
import io.github.xdstorage.code.IXdStorageSimpleWrapper;
import io.github.xdstorage.exceptions.XdStorageException;
import io.github.xdstorage.transaction.IXdStorageTransaction;
import io.github.xdstorage.transaction.XdStorageTransaction;
import io.github.xdstorage.utils.XdStorageObjectIdField;
import io.github.xdstorage.utils.XdStorageObjectUtils;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The default {@link IXdStorageReferenceProvider}: a per-transaction, per-class, per-id
 * hierarchy of concurrent maps memoizing the canonical reference wrappers, with
 * double-checked, lock-guarded creation so concurrent snapshot materializations of the
 * same (class, id) pair share one wrapper instance. The whole registry for a transaction
 * is discarded on {@code #release}.
 *
 * <p>Engine-internal — not part of the public API.</p>
 */
public class XdStorageDefaultReferenceProvider implements IXdStorageReferenceProvider {

    private final IXdStorage storage;
    private final Map<String, Map<Class<?>, Map<Object, IXdStorageSimpleWrapper>>> references = new
            ConcurrentHashMap<>();

    /**
     * Creates the provider bound to one storage.
     *
     * @param storage the storage whose transactions this provider serves
     */
    public XdStorageDefaultReferenceProvider(final IXdStorage storage) {
        this.storage = storage;
    }

    /**
     * @param cl          the entity class of the object
     * @param objectId    the object's id
     * @param transaction the transaction scoping the lookup
     * @return the memoized wrapper, or {@code null} when none was registered yet
     */
    @Override
    public IXdStorageSimpleWrapper getReference(final Class<?> cl, final Object objectId, final IXdStorageTransaction
            transaction) {
        final String transactionId = transaction.getTransactionId();
        Map<Class<?>, Map<Object, IXdStorageSimpleWrapper>> transactionReferencesMap = references.get(transactionId);
        if (transactionReferencesMap == null) return null;
        Map<Object, IXdStorageSimpleWrapper> transactionClassReferences = transactionReferencesMap.get(cl);
        if (transactionClassReferences == null) return null;
        return transactionClassReferences.get(objectId);
    }

    /**
     * Creates and memoizes the canonical wrapper for the (class, id) pair — under a
     * per-class lock when a concurrent registration is possible — or returns the
     * already-registered one. A class that resolves to the dummy wrapper (a self-referencing
     * generation on this thread) is an explicit, diagnosable failure.
     *
     * @param cl          the entity class of the object
     * @param idField     the id-field accessor used to stamp the id into the new wrapper
     * @param objectId    the object's id
     * @param storage     the storage the transaction runs against
     * @param transaction the transaction scoping the registration
     * @return the canonical wrapper for the pair (never {@code null})
     * @throws XdStorageException when the wrapper cannot be instantiated, the class resolves
     *         to the dummy wrapper (a self-referencing generation on this thread), or
     *         registration failed
     */
    @Override
    public IXdStorageSimpleWrapper createAndRegisterReference(final Class<?> cl, final XdStorageObjectIdField idField,
            final Object objectId, final IXdStorage storage, final IXdStorageTransaction transaction) throws
            XdStorageException {
        final String transactionId = transaction.getTransactionId();

        Map<Class<?>, Map<Object, IXdStorageSimpleWrapper>> transactionReferencesMap = references
                .computeIfAbsent(transactionId, k -> new ConcurrentHashMap<>());
        Map<Object, IXdStorageSimpleWrapper> transactionClassReferences = transactionReferencesMap.computeIfAbsent(cl, k
                -> new ConcurrentHashMap<>());

        // АЛГОРИТМИЧЕСКОЕ ИСПРАВЛЕНИЕ ЯДРА (Устранение паразитного дублирования MVCC-версий):
        // Сначала выполняем жесткую проверку наличия прокси в реестре сессии. Если ссылка уже
        // существует, мы МГНОВЕННО возвращаем её, категорически предотвращая холостой вызов
        // wrapAsSimpleObject(). Прежняя логика создавала прокси "вслепую", что приводило к регистрации
        // дубликатов-призраков в XdStorageResourceCache и вызывало крах concurrent modification!
        IXdStorageSimpleWrapper result = transactionClassReferences.get(objectId);
        if (result == null) {
            synchronized (transactionClassReferences) { // Блокировка уровня класса для атомарности создания
                result = transactionClassReferences.get(objectId);
                if (result == null) {
                    try {
                        final IXdStorageSimpleWrapper tmp = XdStorageObjectUtils.wrapAsSimpleObject(cl.newInstance(),
                                storage, transaction);
                        // КАНОНИЧЕСКОЕ ИСПРАВЛЕНИЕ (покрыто тестами поискового движка): null-обертка
                        // (обертка класса — dummy: генерация этого класса уже идет на данном потоке,
                        // самоссылающийся граф сущностей) раньше писалась в CHM как null-значение и рвала
                        // чтение NPE внутри ConcurrentHashMap.put. Теперь отказ - явная диагностируемая
                        // ошибка. (До 2.2.0 dummy был и деградацией при отсутствии компилятора — JRE;
                        // с задачей 08 такой деградации нет: движок отказывает по имени.)
                        if (tmp == null) {
                            throw new XdStorageException("cannot create simple wrapper reference for "
                                    + cl.getName() + " (the wrapper class is the DUMMY: the generation of this "
                                    + "class is in progress on this thread — a self-referencing entity graph)");
                        }
                        idField.set(tmp, objectId);
                        transactionClassReferences.put(objectId, tmp);
                        result = tmp;
                    } catch (final Exception e) {
                        transactionClassReferences.remove(objectId);
                        if (e instanceof XdStorageException) throw (XdStorageException) e;
                        throw new XdStorageException(e);
                    }
                }
            }
        }
        return result;
    }

    /**
     * Discards the transaction's whole reference registry (all classes and ids).
     *
     * @param transaction the transaction being released
     */
    @Override
    public void release(final XdStorageTransaction transaction) {
        final String transactionId = transaction.getTransactionId();
        references.remove(transactionId);
    }
}
