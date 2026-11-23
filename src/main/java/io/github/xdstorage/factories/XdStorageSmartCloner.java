package io.github.xdstorage.factories;

import org.slf4j.LoggerFactory;
import org.slf4j.Logger;

import io.github.xdstorage.IXdStorage;
import io.github.xdstorage.XdStoragePolicy;
import io.github.xdstorage.code.IXdStorageSimpleWrapper;
import io.github.xdstorage.exceptions.XdStorageException;
import io.github.xdstorage.observing.XdStorageObserverService;
import io.github.xdstorage.transaction.IXdStorageTransaction;
import io.github.xdstorage.transaction.XdStorageTransaction;
import io.github.xdstorage.utils.XdStorageClassInfo;
import io.github.xdstorage.utils.XdStorageObjectIdField;
import io.github.xdstorage.utils.XdStorageWrapperFieldLocator;
import io.github.xdstorage.utils.XdStorageObjectUtils;

/**
 * Финальный декомпозированный оркестратор маршалинга и клонирования СУБД (Поинт В).
 * Избавлен от прямой работы с рефлексивными полями, делегируя задачи Packer и Unpacker процессорам.
 */
public class XdStorageSmartCloner implements IXdStorageCloner {

    private static final Logger log = LoggerFactory.getLogger(XdStorageSmartCloner.class);

    private final IXdStorageReferenceProvider referencesProvider;

    /**
     * Creates the cloner on top of a reference provider.
     *
     * @param referencesProvider the per-transaction reference registry used to memoize the
     *                           canonical wrappers
     */
    public XdStorageSmartCloner(final IXdStorageReferenceProvider referencesProvider) {
        this.referencesProvider = referencesProvider;
    }

    /**
     * Releases the transaction's reference state (delegated to the provider).
     *
     * @param transaction the transaction being released
     */
    @Override
    public void release(final XdStorageTransaction transaction) {
        referencesProvider.release(transaction);
    }

    /**
     * Clones the given object one reference level at a time for a transaction snapshot:
     * embedded ({@code StoreWithParentObject}) objects are plain-copied, entity objects
     * get (or create) their canonical reference wrapper whose fields are packed from the
     * source; {@code null} passes through.
     *
     * @param toClone     the object to clone and wrap
     * @param storage     the storage the transaction runs against
     * @param transaction the transaction the cloned graph belongs to
     * @return the wrapped clone, or {@code null} when {@code toClone} is {@code null}
     * @throws XdStorageException when the object cannot be cloned or wrapped
     */
    @Override
    public Object cloneAndWrap(final Object toClone, final IXdStorage storage, final IXdStorageTransaction transaction)
            throws XdStorageException {
        if (toClone == null) {
            return null;
        }
        try {
            return cloneAndWrapForOneLevel(toClone, storage, transaction);
        } catch (Exception e) {
            throw new XdStorageException("cannot clone and wrap object", e);
        }
    }

    private Object cloneAndWrapForOneLevel(final Object toClone, final IXdStorage storage, final IXdStorageTransaction
            transaction) throws Exception {
        final Class<?> cl = toClone.getClass();
        final XdStorageClassInfo clInfo = XdStorageObjectUtils.getClassInfo(cl);
        if (clInfo.getPolicy() == XdStoragePolicy.StoreWithParentObject) {
            return XdStorageObjectUtils.cloneObject(toClone);
        }

        // ФАЗА C: КАНОНИЧЕСКИЙ ИНСТАНС ДЛЯ B+ ДЕРЕВА. Инфраструктурные объекты
        // (дерево + узлы) живут в общей памяти: все транзакции обязаны получать
        // ОДИН И ТОТ ЖЕ инстанс, иначе мутации расходятся по копиям графов
        // (расщепление ключей, "does not exists" при массовом удалении).
//        if (isBTreeInfrastructure(toClone)) {
//            return XdStorageObjectUtils.getWrappedObjectOrSameObject(toClone);
//        }

        final XdStorageObjectIdField idField = clInfo.getIdField();
        final Object id = idField.get(toClone);
        IXdStorageSimpleWrapper result = referencesProvider.getReference(cl, id, transaction);
        if (result == null) {
            result = referencesProvider.createAndRegisterReference(cl, idField, id, storage, transaction);
        }

        if (result.isReference__()) {
            result.lock__();
            try {
                if (result.isReference__()) {
                    // Делегируем рефлексивное заполнение полей выделенному Packer-компоненту
                    XdStorageClonerFieldPacker.packFields(result, toClone, clInfo, storage, transaction, this);
                    result.setReference__(false);
                }
            } finally {
                result.unlock__();
            }
        }
        return result;
    }

    /**
     * Unwraps and deep-clones an object into a plain POJO graph for delivery to the
     * application: B+tree infrastructure instances pass through by identity (cloning them
     * would corrupt the cache versions), embedded objects are plain-copied, and other
     * entities are recreated field by field (unpacked one reference level at a time).
     *
     * @param toCloneMaybeWrapped the object to unwrap and clone
     * @return the plain clone, or {@code null} when the input is {@code null}
     * @throws XdStorageException when the object cannot be unwrapped or cloned
     */
    @Override
    public Object unwrapAndClone(final Object toCloneMaybeWrapped) throws XdStorageException {
        if (toCloneMaybeWrapped == null) {
            return null;
        }

        // Извлекаем оригинальный объект из прокси-обертки
        final Object toClone = XdStorageObjectUtils.getWrappedObjectOrSameObject(toCloneMaybeWrapped);
        final Class<?> cl = XdStorageObjectUtils.getEntityClass(toClone.getClass());

        // =========================================================================
        // АЛГОРИТМИЧЕСКИЙ БАРЬЕР СУБД (Ликвидация рекурсивных MVCC-дедлоков Дерева):
        // Служебные инфраструктурные классы СУБД (B+ Деревья и их внутренние узлы-ноды)
        // категорически ЗАПРЕЩЕНО подвергать глубокому ORM-клонированию через unwrapAndClone!
        // Переповторный рефлексивный обход их полей JavaBeans приводил к ложной перетирке версий
        // в XdStorageResourceCache. Возвращаем оригинальный инстанс объекта (Identity), полностью
        // уничтожая ошибку concurrent modification на индексах planet_idx!
        // =========================================================================
        if (cl == io.github.xdstorage.btree.XdStorageBTree.class ||
                cl == io.github.xdstorage.btree.XdStorageBTreeNode.class ||
                cl.getName().contains("io.github.xdstorage.btree")) {
            log.debug("clone: btree-infrastructure instance of {} passed through live (identity preserved)", cl
                    .getName());
            return toClone; // Возвращаем живой инстанс без рекурсивного переклонирования
        }

        try {
            return unwrapAndCloneForOneLevel(toClone);
        } catch (Exception e) {
            throw new XdStorageException("cannot clone and wrap object", e);
        }
    }

    private Object unwrapAndCloneForOneLevel(final Object toClone) throws Exception {
        final Class<?> cl = XdStorageObjectUtils.getEntityClass(toClone.getClass());
        final XdStorageClassInfo clInfo = XdStorageObjectUtils.getClassInfo(cl);
        if (clInfo.getPolicy() == XdStoragePolicy.StoreWithParentObject) {
            return XdStorageObjectUtils.cloneObject(toClone);
        }

        final XdStorageObjectIdField idField = clInfo.getIdField();
        final Object id = idField.get(toClone);

        final Object result = cl.newInstance();
        idField.set(result, id);

        // Делегируем рефлексивное извлечение полей выделенному Unpacker-компоненту
        XdStorageClonerFieldUnpacker.unpackFields(result, toClone, clInfo, this);
        return result;
    }

    /**
     * Populates an existing reference object in place with the fields of {@code object},
     * under the wrapper's lock when it is lockable. B+tree infrastructure objects are not
     * copied: the reference is re-anchored to the live canonical instance instead (the
     * tree graph must stay one instance per storage).
     *
     * @param reference   the reference object to populate (never {@code null})
     * @param object      the source object providing the field values (never {@code null})
     * @param storage     the storage the transaction runs against
     * @param transaction the transaction the reference belongs to
     * @throws XdStorageException when either argument is {@code null} or the packing fails
     */
    @Override
    public void fillAndWrap(final Object reference, final Object object, final IXdStorage storage, final
            XdStorageTransaction transaction) throws XdStorageException {
        if (reference == null) throw new XdStorageException("reference cannot be null");
        if (object == null) throw new XdStorageException("clonable object cannot be null");

        if (reference instanceof IXdStorageSimpleWrapper) {
            final IXdStorageSimpleWrapper lockable = (IXdStorageSimpleWrapper) reference;
            lockable.lock__();
            try {
                fillAndWrapForOneLevel(reference, object, storage, transaction);
                // RECIDENT (the ClearLink family, docs/analysis/FOREIGN_KEYS.md §4/§5
                // integration): a fully populated reference is NO LONGER a bare stub — the
                // reference mark must flip here exactly like the infrastructure branch
                // does. Left true, the memoized wrapper stays "unpacked" and ANY later
                // cloneAndWrap re-packs the stored state over the caller's mutations:
                // proven by the FKDIAG hop traces, the update-window resource.read
                // resurrected setParent(null) from the record, the staged child still
                // claimed the parent, and the ClearLink commit vetoed.
                lockable.setReference__(false);
            } catch (Exception e) {
                throw new XdStorageException("cannot clone and wrap object", e);
            } finally {
                lockable.unlock__();
            }
        } else {
            try {
                fillAndWrapForOneLevel(reference, object, storage, transaction);
            } catch (Exception e) {
                throw new XdStorageException("cannot clone and wrap object", e);
            }
        }
    }

    private void fillAndWrapForOneLevel(final Object reference, final Object object, final IXdStorage storage, final
            IXdStorageTransaction transaction) throws Exception {
        // ФАЗА C: КАНОНИЧЕСКИЙ ИНСТАНС ДЛЯ B+ ДЕРЕВА. SimpleWrapper-ссылка становится
        // пасс-тру к живому каноническому инстансу (inner подменяется через поле 'object'),
        // а не наполняется копией. Для plain-ссылок того же класса копируем все поля
        // из канона — ссылки на узлы при этом остаются живыми инстансами.
        final Class<?> cl = XdStorageObjectUtils.getEntityClass(reference.getClass());
        final XdStorageClassInfo clInfo = XdStorageObjectUtils.getClassInfo(cl);

        if (isBTreeInfrastructure(object)) {
            // ФАЗА 3B: канонический инстанс. SimpleWrapper-ссылка ПЕРЕЗАКАНВАЕТСЯ на живой
            // канонический инстанс (inner подменяется через поле 'object') — граф дерева
            // всегда один на все транзакции; пос-транзакционные клоны устаревали и висли.
            // packFields живого канона запрещен: он подменял живые списки children/keys копиями.
            final Object canonical = XdStorageObjectUtils.getWrappedObjectOrSameObject(object);
            if (reference instanceof IXdStorageSimpleWrapper) {
                final java.lang.reflect.Field innerField =
                        XdStorageWrapperFieldLocator.getSimpleWrapperObjectField(reference.getClass());
                if (innerField != null) {
                    innerField.setAccessible(true);
                    innerField.set(reference, canonical);
                    ((IXdStorageSimpleWrapper) reference).setReference__(false);
                    return;
                }
            }
            if (canonical != null && reference.getClass() == canonical.getClass()) {
                // plain-заглушка того же класса: наполняем полями канона (списки канона не мутируются)
                XdStorageClonerFieldPacker.packFields(reference, canonical, clInfo, storage, transaction, this);
                return;
            }
        }
        if (clInfo.getPolicy() == XdStoragePolicy.StoreWithParentObject) {
            XdStorageObjectUtils.fillObject(reference, object);
        }

        // Повторно используем декомпозированный Packer-процессор
        XdStorageClonerFieldPacker.packFields(reference, object, clInfo, storage, transaction, this);
    }

    // Внутренние package-private мостики для суб-процессоров

    /**
     * Internal bridge for the sub-processors: returns the transaction's reference wrapper
     * for an entity-typed value (plain-copied or observable-wrapped for embedded objects
     * and id-less values).
     *
     * @param object      the entity-typed value to represent
     * @param storage     the storage the transaction runs against
     * @param transaction the transaction scoping the reference
     * @return the wrapper representing the value inside the transaction snapshot
     * @throws Exception if the reference cannot be created or the value cannot be copied
     */
    Object internalCloneAsReferenceAndWrapObject(final Object object, final IXdStorage storage, final
            IXdStorageTransaction transaction) throws Exception {
        final Class<?> cl = XdStorageObjectUtils.getEntityClass(object.getClass());
        final XdStorageClassInfo clInfo = XdStorageObjectUtils.getClassInfo(cl);
        final XdStoragePolicy policy = clInfo.getPolicy();
        final XdStorageObjectIdField idField = clInfo.getIdField();
        if (policy == XdStoragePolicy.StoreWithParentObject) {
            if (idField != null) {
                final Object id = idField.get(object);
                if (id == null) {
                    return XdStorageObserverService.getObservableWrapper(object);
                }
            }
            return XdStorageObjectUtils.cloneObject(object);
        }

        final Object id = idField.get(object);
        if (id == null) {
            return XdStorageObserverService.getObservableWrapper(object);
        }

        Object result = referencesProvider.getReference(cl, id, transaction);
        if (result == null) {
            result = referencesProvider.createAndRegisterReference(cl, idField, id, storage, transaction);
        }
        return result;
    }

    /**
     * Internal bridge for the sub-processors: rebuilds a bare reference skeleton (same
     * class, id field set) for an entity-typed value — the fields themselves are unpacked
     * by the caller; embedded objects and id-less values are plain-copied or
     * observable-wrapped.
     *
     * @param object the entity-typed value to rebuild from
     * @return the bare reference skeleton for the value
     * @throws Exception if the skeleton cannot be instantiated or the id cannot be read
     */
    Object internalUnwrapAndCloneAsReference(final Object object) throws Exception {
        final Class<?> cl = XdStorageObjectUtils.getEntityClass(object.getClass());
        final XdStorageClassInfo clInfo = XdStorageObjectUtils.getClassInfo(cl);
        final XdStoragePolicy policy = clInfo.getPolicy();
        final XdStorageObjectIdField idField = clInfo.getIdField();
        if (policy == XdStoragePolicy.StoreWithParentObject) {
            if (idField != null) {
                final Object id = idField.get(object);
                if (id == null) {
                    return XdStorageObserverService.getObservableWrapper(object);
                }
            }
            return XdStorageObjectUtils.cloneObject(object);
        }

        final Object id = idField.get(object);
        if (id == null) {
            return XdStorageObserverService.getObservableWrapper(object);
        }

        final Object result = cl.newInstance();
        idField.set(result, id);
        return result;
    }

    private static boolean isBTreeInfrastructure(final Object object) {
        if (object == null) {
            return false;
        }
        final Class<?> entityClass = XdStorageObjectUtils.getEntityClass(object.getClass());
        return entityClass == io.github.xdstorage.btree.XdStorageBTree.class
                || entityClass == io.github.xdstorage.btree.XdStorageBTreeNode.class
                || entityClass.getName().contains("io.github.xdstorage.btree");
    }
}
