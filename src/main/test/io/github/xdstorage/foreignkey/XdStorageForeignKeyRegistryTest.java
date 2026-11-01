package io.github.xdstorage.foreignkey;

import io.github.xdstorage.entities.foreignkeys.Child;
import io.github.xdstorage.entities.foreignkeys.Parent;
import io.github.xdstorage.utils.XdStorageClassInfo;
import io.github.xdstorage.utils.XdStorageObjectUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collection;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Интеграционный Unit-тест: Верификация обновленной метамодели XdStorageForeignKeyRegistry")
public class XdStorageForeignKeyRegistryTest {

    private XdStorageForeignKeyRegistry registry;
    private XdStorageClassInfo parentClassInfo;
    private XdStorageClassInfo childClassInfo;

    @BeforeEach
    public void setUp() {
        registry = new XdStorageForeignKeyRegistry();
        parentClassInfo = XdStorageObjectUtils.getClassInfo(Parent.class);
        childClassInfo = XdStorageObjectUtils.getClassInfo(Child.class);
    }

    @Test
    @DisplayName("Инвариант: Регистрация реального Parent должна создать Outcome связь и зеркальный Income для Child")
    public void testRegister_WithRealParent_ShouldBuildBidirectionalRelations() {
        registry.registerRelations(parentClassInfo);

        // 1. Проверяем прямые исходящие связи для Parent
        Collection<XdStorageForeignKeyRelation> parentOutcomes = registry.getOutcomeRelations(parentClassInfo);
        assertNotNull(parentOutcomes);
        assertEquals(1, parentOutcomes.size(), "Класс Parent должен иметь одну исходящую связь!");

        XdStorageForeignKeyRelation outcome = parentOutcomes.iterator().next();
        assertEquals(Parent.class, outcome.getClassFrom().getClazz());
        assertEquals(Child.class, outcome.getClassTo().getClazz());

        // 2. Проверяем входящие связи для Child (она должна быть реверсивной от Parent)
        Collection<XdStorageForeignKeyRelation> childIncomes = registry.getIncomeRelations(childClassInfo);
        assertNotNull(childIncomes);
        assertEquals(1, childIncomes.size(), "Дочерний класс Child обязан получить входящую связь!");

        XdStorageForeignKeyRelation income = childIncomes.iterator().next();
        assertEquals(Parent.class, income.getClassFrom().getClazz(), "Источник связи-реверса для ребенка — это класс Parent!");
        assertEquals(Child.class, income.getClassTo().getClazz(), "Цель связи-реверса для ребенка — это класс Child!");
        assertFalse(income.isReverseRelation());
    }

    @Test
    @DisplayName("Инвариант: Регистрация реального Child должна правильно определить прямую связь к Parent")
    public void testRegister_WithRealChild_ShouldBuildCorrectHierarchy() {
        registry.registerRelations(childClassInfo);

        // Проверяем исходящую связь от Child к Parent
        Collection<XdStorageForeignKeyRelation> childOutcomes = registry.getOutcomeRelations(childClassInfo);
        assertNotNull(childOutcomes);
        assertEquals(1, childOutcomes.size(), "Класс Child должен иметь исходящую связь к своему родителю!");

        XdStorageForeignKeyRelation outcome = childOutcomes.iterator().next();
        assertEquals(Child.class, outcome.getClassFrom().getClazz());
        assertEquals(Parent.class, outcome.getClassTo().getClazz());
        assertFalse(outcome.isReverseRelation());

        // Проверяем входящую связь для Parent со стороны Child
        Collection<XdStorageForeignKeyRelation> parentIncomes = registry.getIncomeRelations(parentClassInfo);
        assertNotNull(parentIncomes);
        assertEquals(1, parentIncomes.size(), "Родительский класс должен получить входящую связь от Child!");

        XdStorageForeignKeyRelation income = parentIncomes.iterator().next();
        assertEquals(Child.class, income.getClassFrom().getClazz());
        assertEquals(Parent.class, income.getClassTo().getClazz());
        assertFalse(income.isReverseRelation());
    }
}
