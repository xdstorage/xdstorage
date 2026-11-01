package io.github.xdstorage.foreignkey;

import io.github.xdstorage.utils.XdStorageAutoSaveFieldInfo;
import io.github.xdstorage.utils.XdStorageClassInfo;
import io.github.xdstorage.utils.XdStorageObjectField;
import io.github.xdstorage.utils.XdStorageObjectUtils;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class XdStorageForeignKeyRegistry {

    public static final XdStorageObjectField REVERSE_RELATION_FIELD = new XdStorageObjectField(
            null, null, null, null
    );

    private final Map<Class<?>, Map<XdStorageObjectField, XdStorageForeignKeyRelation>> relations = new ConcurrentHashMap<>();

    /** FNKEY: classes whose OWN registration ran (an entry-map can exist without it —
     * the other side's isParent branch creates it via computeIfAbsent). */
    private final java.util.Set<Class<?>> registeredClasses = ConcurrentHashMap.newKeySet();

    public void registerRelations(XdStorageClassInfo classInfo) {
        if (!registeredClasses.add(classInfo.getClazz())) {
            return;
        }

        classInfo.getAutoSaveFields().forEach(field -> {
            Class<?> childClass = field.getAccessor().getFieldInfo().getValueClass();
            if (childClass != null) {
                XdStorageClassInfo childClassInfo = XdStorageObjectUtils.getClassInfo(childClass);

                // FNKEY (docs/tasks/2.1.0/42): ЕДИНСТВЕННАЯ not-reverse связь на пару
                // (родитель, ребенок). Если ребенок был зарегистрирован РАНЬШЕ и его
                // @XdStorageParentObject создал child-anchored связь (Child -> Parent),
                // она снимается — канонический владелец связи: AutoSave-сторона родителя.
                final Map<XdStorageObjectField, XdStorageForeignKeyRelation> childRelations =
                        relations.get(childClass);
                if (childRelations != null) {
                    childRelations.entrySet().removeIf(entry -> !entry.getValue().isReverseRelation()
                            && entry.getValue().getClassTo() == classInfo);
                }

                relations.computeIfAbsent(classInfo.getClazz(), cl -> new ConcurrentHashMap<>())
                        .putIfAbsent(field.getAccessor(), new XdStorageForeignKeyRelation(
                                classInfo, childClassInfo, false, true, field.getAccessor()
                        ));

                // РEВEРС СВЯЗИ: Для ребенка (childClass) эта связь является входящей от родителя (classInfo)
                relations.computeIfAbsent(childClass, cl -> new ConcurrentHashMap<>())
                        .putIfAbsent(field.getAccessor(), new XdStorageForeignKeyRelation(
                                childClassInfo, classInfo, true, true, REVERSE_RELATION_FIELD
                        ));
            }
        });

        classInfo.getFields().forEach((fieldName, field) -> {
            if (field.isParent()) {
                Class<?> parentClass = field.getFieldInfo().getValueClass();
                if (parentClass != null) {
                    XdStorageClassInfo parentClazz = XdStorageObjectUtils.getClassInfo(parentClass);

                    // FNKEY (docs/tasks/2.1.0/42): каноническая not-reverse связь
                    // ПРИНАДЛЕЖИТ AutoSave-стороне родителя. Ребенок может зарегистрироваться
                    // ПЕРВЫМ (каскад сохраняет детей ДО FK-обработки родителя) — тогда
                    // каноническая связь регистрируется здесь, НА БЕХАЛЬФЕ родителя, по его
                    // AutoSave-полю; child-side дубликат не создается никогда (он плодил
                    // перевернутые дубли-записи FK).
                    final XdStorageAutoSaveFieldInfo parentAutosaveField = parentClazz.getAutoSaveFields().stream()
                            .filter(autosaveField -> {
                                final Class<?> autosaveChildClass = autosaveField.getAccessor().getFieldInfo().getValueClass();
                                return autosaveChildClass != null && autosaveChildClass == classInfo.getClazz();
                            })
                            .findFirst().orElse(null);
                    final boolean parentHasAutoSaveBack = parentAutosaveField != null;

                    if (parentHasAutoSaveBack) {
                        relations.computeIfAbsent(parentClass, cl -> new ConcurrentHashMap<>())
                                .putIfAbsent(parentAutosaveField.getAccessor(),
                                        new XdStorageForeignKeyRelation(parentClazz, classInfo, false, true, parentAutosaveField.getAccessor()));
                    } else {
                        relations.computeIfAbsent(classInfo.getClazz(), cl -> new ConcurrentHashMap<>())
                                .putIfAbsent(field, new XdStorageForeignKeyRelation(
                                        classInfo, parentClazz, false, false, field
                                ));
                    }

                    // СУБД-ФИКС ТОПОЛОГИИ: Выравниваем вектор From/To для обратных ссылок!
                    // Для родителя (parentClass) эта связь является входящей от ребенка (classInfo)
                    relations.computeIfAbsent(parentClass, cl -> new ConcurrentHashMap<>())
                            .putIfAbsent(REVERSE_RELATION_FIELD, new XdStorageForeignKeyRelation(
                                    parentClazz, classInfo, true, !parentHasAutoSaveBack, field
                            ));
                }
            }
        });
    }

    public Collection<XdStorageForeignKeyRelation> getOutcomeRelations(XdStorageClassInfo classInfo) {
        registerRelations(classInfo);

        return relations.entrySet().stream()
                .filter(entry -> entry.getKey().equals(classInfo.getClazz()))
                .map(Map.Entry::getValue).map(Map::values)
                .flatMap(Collection::stream)
                .collect(Collectors.toList());
    }

    public Collection<XdStorageForeignKeyRelation> getIncomeRelations(XdStorageClassInfo classInfo) {
        registerRelations(classInfo);

        return relations.values().stream().map(Map::values)
                .flatMap(Collection::stream)
                // FNKEY: compare by CLASS, not by ClassInfo identity — ClassInfo instances
                // are built per lookup path (direct class vs wrapper-derived entity class),
                // and the identity filter silently dropped canonical relations
                .filter(relation -> relation.getClassTo().getClazz() == classInfo.getClazz())
                .collect(Collectors.toList());
    }

}
