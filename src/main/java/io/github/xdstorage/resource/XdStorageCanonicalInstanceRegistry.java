package io.github.xdstorage.resource;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ФАЗА 3B: реестр канонических инстансов инфраструктуры B+Дерева (деревья/узлы),
 * на один storage (держится в XdStorageServicesLocator, чистится при shutdown).
 *
 * Зачем: ресурсprepare() разбирает файлы с диска в НОВЫЕ инстансы, а писатель мутирует
 * СВОЙ живой граф в памяти — читатель получал parse-копию с заглушками root/firstLeaf
 * вместо канонического графа (двусмысленность инстанций). Реестр делает инстанцию
 * канонической: parse-копия при fillCache УСТУПАЕТ место живому инстансу, если тот
 * зарегистрирован; живой инстанс регистрируется при insert/update (materializeForCache).
 *
 * Удаление записей: только clear() (shutdown ресурса) — удаленные ноды остаются в
 * реестре до конца жизни storage (осознанный компромисс против воскрешения нод при
 * откате удаления и последующем reparse; утечка ограничена числом созданных нод).
 */
public class XdStorageCanonicalInstanceRegistry {

    private final Map<String, Object> instances = new ConcurrentHashMap<>();

    /** Creates an empty registry (one per storage, cleared at shutdown). */
    public XdStorageCanonicalInstanceRegistry() {
    }

    /**
     * Returns the canonical instance registered for the class/id pair, registering the
     * candidate as canonical when none is registered yet.
     *
     * @param clazz     the infrastructure class (tree or node)
     * @param id        the object id of the instance
     * @param candidate the parsed candidate instance
     * @return the canonical instance — the registered one, or {@code candidate} when it
     *         won the registration (also returned as-is for {@code null} arguments)
     */
    public Object adoptOrRegister(final Class<?> clazz, final Object id, final Object candidate) {
        if (clazz == null || id == null || candidate == null) {
            return candidate;
        }
        return instances.computeIfAbsent(clazz.getName() + "#" + id, k -> candidate);
    }

    /**
     * The canonical instance registered for the class/id pair, if any.
     *
     * @param clazz the infrastructure class (tree or node)
     * @param id    the object id of the instance
     * @return the canonical instance, or {@code null} when none is registered
     */
    public Object get(final Class<?> clazz, final Object id) {
        if (clazz == null || id == null) {
            return null;
        }
        return instances.get(clazz.getName() + "#" + id);
    }

    /**
     * TREEFILE (docs/tasks/2.1.0/41): force-replaces the canonical instance of the class/id
     * pair. The rollback reload of an infrastructure resource re-parses the last-committed
     * file and the fresh parse MUST win over the pre-replace registration — the registered
     * instance carries the rolled-back transaction's un-restorable in-place mutations
     * (prevObject aliases the live instance, so record rollback cannot restore it).
     *
     * @param clazz    the infrastructure class (tree or node)
     * @param id       the object id of the instance
     * @param instance the new canonical instance
     * @return the registered instance (as-is for {@code null} arguments)
     */
    public Object register(final Class<?> clazz, final Object id, final Object instance) {
        if (clazz == null || id == null || instance == null) {
            return instance;
        }
        instances.put(clazz.getName() + "#" + id, instance);
        return instance;
    }

    /**
     * Discards all registrations — called at storage shutdown (entries are otherwise kept
     * for the storage's whole lifetime by design).
     */
    public void clear() {
        instances.clear();
    }
}
