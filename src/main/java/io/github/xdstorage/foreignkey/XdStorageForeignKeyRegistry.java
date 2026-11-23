package io.github.xdstorage.foreignkey;

import io.github.xdstorage.utils.XdStorageAutoSaveFieldInfo;
import io.github.xdstorage.utils.XdStorageClassInfo;
import io.github.xdstorage.utils.XdStorageObjectField;
import io.github.xdstorage.utils.XdStorageObjectUtils;
import io.github.xdstorage.transaction.XdStorageTransaction;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The foreign-key relation registry: scans each entity class's {@code @XdStorageAutoSave}
 * and {@code @XdStorageParentObject} fields once, storing the canonical and mirrored
 * (reverse) relations per class, and serves the engine's outcome/income relation lookups.
 * Also bridges the engine's orphan detection to the resource's phase-1 validation via the
 * per-transaction orphaned-parent sets.
 */
public class XdStorageForeignKeyRegistry {

    private static final Logger log = LoggerFactory.getLogger(XdStorageForeignKeyRegistry.class);

    /** Creates an empty relation registry; relations are scanned lazily per class. */
    public XdStorageForeignKeyRegistry() {
    }

    /**
     * The placeholder field marking reverse (mirrored) relations — they are declared by
     * the counterpart's field, not by an own annotated field.
     */
    public static final XdStorageObjectField REVERSE_RELATION_FIELD = new XdStorageObjectField(
            null, null, null, null
    );

    private final Map<Class<?>, Map<XdStorageObjectField, XdStorageForeignKeyRelation>> relations = new
            ConcurrentHashMap<>();

    /** FNKEY: classes whose OWN registration ran (an entry-map can exist without it —
     * the other side's isParent branch creates it via computeIfAbsent). */
    private final java.util.Set<Class<?>> registeredClasses = ConcurrentHashMap.newKeySet();

    // FNKEY: Tracks parent IDs that have orphaned children (in FK tree but not in parent's
    // collection) for commit-time validation. Key = transactionId, Value = set of parent IDs.
    // This bridges the FK engine's update-diff (which detects orphaned children) with
    // the FK resource's first-phase commit validation (which throws at commit time).
    private final Map<String, Set<Comparable<?>>> orphanedChildrenByParent = new ConcurrentHashMap<>();

    /**
     * Scans the class's {@code @XdStorageAutoSave} and {@code @XdStorageParentObject}
     * fields once (idempotent per class) and registers the canonical relations plus the
     * mirrored reverse relations for both sides.
     *
     * @param classInfo the class whose declared relations to register
     */
    public void registerRelations(XdStorageClassInfo classInfo) {
        if (!registeredClasses.add(classInfo.getClazz())) {
            return;
        }

        classInfo.getAutoSaveFields().forEach(field -> {
            Class<?> childClass = field.getAccessor().getFieldInfo().getValueClass();
            if (childClass != null && !childClass.isPrimitive()) {
                XdStorageClassInfo childClassInfo = XdStorageObjectUtils.getClassInfo(childClass);

                relations.computeIfAbsent(classInfo.getClazz(), cl -> new ConcurrentHashMap<>())
                        .putIfAbsent(field.getAccessor(), new XdStorageForeignKeyRelation(
                                classInfo, childClassInfo, false, true, field.getAccessor()
                        ));

                // РEВEРС СВЯЗИ: Для ребенка (childClass) эта связь является входящей от родителя (classInfo)
                relations.computeIfAbsent(childClassInfo.getClazz(), cl -> new ConcurrentHashMap<>())
                        .putIfAbsent(field.getAccessor(), new XdStorageForeignKeyRelation(
                                childClassInfo, classInfo, true, true, REVERSE_RELATION_FIELD
                        ));
            }
        });

        classInfo.getFields().forEach((fieldName, field) -> {
            if (field.isParent()) {
                Class<?> parentClass = field.getFieldInfo().getValueClass();
                if (parentClass != null) {
                    XdStorageClassInfo parentClassInfo = XdStorageObjectUtils.getClassInfo(parentClass);

                    // FNKEY (docs/tasks/2.1.0/42): каноническая not-reverse связь
                    // ПРИНАДЛЕЖИТ AutoSave-стороне родителя. Ребенок может зарегистрироваться
                    // ПЕРВЫМ (каскад сохраняет детей ДО FK-обработки родителя) — тогда
                    // каноническая связь регистрируется здесь, НА БЕХАЛЬФЕ родителя, по его
                    // AutoSave-полю; child-side дубликат не создается никогда (он плодил
                    // перевернутые дубли-записи FK).
                    final XdStorageAutoSaveFieldInfo parentAutosaveField = parentClassInfo.getAutoSaveFields().stream()
                            .filter(autosaveField -> {
                                final Class<?> autosaveChildClass = autosaveField.getAccessor().getFieldInfo()
                                        .getValueClass();
                                return autosaveChildClass != null && autosaveChildClass == classInfo.getClazz();
                            })
                            .findFirst().orElse(null);
                    final boolean parentHasAutoSaveBack = parentAutosaveField != null;

                    // FNKEY (docs/tasks/2.1.0/42): the child-side declaration stays
                    // registered as the child-anchored relation (parentAnchored=false) — the
                    // registry pin requires it among the parent's incoming relations. The
                    // parent-anchored flows (pair registration, diff, waiters) filter on
                    // isParentAnchored(), so this relation mirrors no pairs and holds no
                    // waiters: it is a declaration marker, not a second enforcement owner.
                    relations.computeIfAbsent(classInfo.getClazz(), cl -> new ConcurrentHashMap<>())
                            .putIfAbsent(field, new XdStorageForeignKeyRelation(
                                    classInfo, parentClassInfo, false, false, field
                            ));

                    // СУБД-ФИКС ТОПОЛОГИИ: Выравниваем вектор From/To для обратных ссылок!
                    // Для родителя (parentClass) эта связь является входящей от ребенка (classInfo)
                    relations.computeIfAbsent(parentClassInfo.getClazz(), cl -> new ConcurrentHashMap<>())
                            .putIfAbsent(field, new XdStorageForeignKeyRelation(
                                    parentClassInfo, classInfo, true, !parentHasAutoSaveBack, REVERSE_RELATION_FIELD
                            ));
                }
            }
        });
    }

    /**
     * The relations this class declares as the From (parent/referencing) side — the
     * engine's outcome-relation path (the {@code @XdStorageAutoSave} cascades).
     * Registers the class's relations first if not yet scanned.
     *
     * @param classInfo the class whose outcome relations to look up
     * @return the relations declared from this class; empty when the class declares none
     */
    public Collection<XdStorageForeignKeyRelation> getOutcomeRelations(XdStorageClassInfo classInfo) {
        registerRelations(classInfo);
        final Collection<XdStorageForeignKeyRelation> result = relations.entrySet().stream()
                .filter(entry -> entry.getKey().equals(classInfo.getClazz()))
                .map(Map.Entry::getValue).map(Map::values)
                .flatMap(Collection::stream)
                // FNKEY: compare by CLASS, not by ClassInfo identity — ClassInfo instances
                // are built per lookup path (direct class vs wrapper-derived entity class),
                // and the identity filter silently dropped canonical relations
                .filter(relation -> relation.getClassFrom().getClazz() == classInfo.getClazz())
                .collect(Collectors.toList());
        return result;
    }

    /**
     * The relations in which this class is the To (child/referenced) side — the engine's
     * income-relation path (the {@code @XdStorageParentObject} references). Registers the
     * class's relations first if not yet scanned.
     *
     * @param classInfo the class whose income relations to look up
     * @return the relations targeting this class; empty when none target it
     */
    public Collection<XdStorageForeignKeyRelation> getIncomeRelations(XdStorageClassInfo classInfo) {
        registerRelations(classInfo);
        final Collection<XdStorageForeignKeyRelation> result = relations.values().stream().map(Map::values)
                .flatMap(Collection::stream)
                // FNKEY: compare by CLASS, not by ClassInfo identity — ClassInfo instances
                // are built per lookup path (direct class vs wrapper-derived entity class),
                // and the identity filter silently dropped canonical relations
                .filter(relation -> relation.getClassTo().getClazz() == classInfo.getClazz())
                .collect(Collectors.toList());
        return result;
    }

    /**
     * FNKEY: Registers orphaned children for commit-time validation.
     *
     * @param tx        the owning transaction
     * @param parentIds the ids of the parents whose children turned orphaned by the update
     */
    public void registerOrphanedChildren(XdStorageTransaction tx, Set<Comparable<?>> parentIds) {
        orphanedChildrenByParent.computeIfAbsent(tx.getTransactionId(), k -> ConcurrentHashMap.newKeySet())
                .addAll(parentIds);
    }

    /**
     * FNKEY: Returns orphaned children for the given transaction.
     *
     * @param tx the owning transaction
     * @return the orphaned parents registered for the transaction, or null when none
     */
    public Set<Comparable<?>> getOrphanedChildren(XdStorageTransaction tx) {
        return orphanedChildrenByParent.get(tx.getTransactionId());
    }

    /**
     * FNKEY: Clears orphaned children for the given transaction.
     *
     * @param tx the transaction whose orphaned-parent set to drop
     */
    public void clearOrphanedChildren(XdStorageTransaction tx) {
        orphanedChildrenByParent.remove(tx.getTransactionId());
    }

}
