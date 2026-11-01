package io.github.xdstorage.foreignkey;

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

    public void registerRelations(XdStorageClassInfo classInfo) {
        if (relations.containsKey(classInfo.getClazz())) {
            return;
        }

        classInfo.getAutoSaveFields().forEach(field -> {
            Class<?> childClass = field.getAccessor().getFieldInfo().getValueClass();
            if (childClass != null) {
                XdStorageClassInfo childClassInfo = XdStorageObjectUtils.getClassInfo(childClass);

                relations.computeIfAbsent(classInfo.getClazz(), cl -> new ConcurrentHashMap<>())
                        .putIfAbsent(field.getAccessor(), new XdStorageForeignKeyRelation(
                                classInfo, childClassInfo, false, field.getAccessor()
                        ));

                // РEВEРС СВЯЗИ: Для ребенка (childClass) эта связь является входящей от родителя (classInfo)
                relations.computeIfAbsent(childClass, cl -> new ConcurrentHashMap<>())
                        .putIfAbsent(field.getAccessor(), new XdStorageForeignKeyRelation(
                                childClassInfo, classInfo, true, REVERSE_RELATION_FIELD
                        ));
            }
        });

        classInfo.getFields().forEach((fieldName, field) -> {
            if (field.isParent()) {
                Class<?> parentClass = field.getFieldInfo().getValueClass();
                if (parentClass != null) {
                    XdStorageClassInfo parentClazz = XdStorageObjectUtils.getClassInfo(parentClass);

                    relations.computeIfAbsent(classInfo.getClazz(), cl -> new ConcurrentHashMap<>())
                            .putIfAbsent(field, new XdStorageForeignKeyRelation(
                                    classInfo, parentClazz, false, field
                            ));

                    // СУБД-ФИКС ТОПОЛОГИИ: Выравниваем вектор From/To для обратных ссылок!
                    // Для родителя (parentClass) эта связь является входящей от ребенка (classInfo)
                    relations.computeIfAbsent(parentClass, cl -> new ConcurrentHashMap<>())
                            .putIfAbsent(REVERSE_RELATION_FIELD, new XdStorageForeignKeyRelation(
                                    parentClazz, classInfo, true, field
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
                .filter(relation -> relation.getClassTo() == classInfo)
                .collect(Collectors.toList());
    }

}
