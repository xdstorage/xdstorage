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

        // 2. FNKEY (docs/tasks/2.1.0/42): проверяем входящие связи для Child —
        // регистрация Child добавляет реверс-маркер, и у Child ровно ДВЕ входящие:
        // каноническая (Parent->Child, не-реверс, parent-anchored) от AutoSave-стороны
        // и реверс-маркер от собственного @XdStorageParentObject.
        Collection<XdStorageForeignKeyRelation> childIncomes = registry.getIncomeRelations(childClassInfo);
        assertNotNull(childIncomes);
        assertEquals(2, childIncomes.size(), "Child имеет каноническую входящую связь и реверс-маркер!");

        final long canonical = childIncomes.stream().filter(r -> !r.isReverseRelation()).count();
        assertEquals(1, canonical, "Ровно одна каноническая (не-реверс) входящая связь у Child!");
        final XdStorageForeignKeyRelation income = childIncomes.stream()
                .filter(r -> !r.isReverseRelation()).iterator().next();
        assertEquals(Parent.class, income.getClassFrom().getClazz(), "Источник канонической связи для ребенка — это класс Parent!");
        assertEquals(Child.class, income.getClassTo().getClazz(), "Цель канонической связи для ребенка — это класс Child!");
        assertTrue(income.isParentAnchored(), "Каноническая связь принадлежит AutoSave-стороне родителя!");
    }

    @Test
    @DisplayName("FNKEY: Child-first регистрация не создает дубликат связи — канонической остается AutoSave-сторона родителя")
    public void testRegister_WithRealChild_ShouldBuildCorrectHierarchy() {
        registry.registerRelations(childClassInfo);

        // FNKEY (docs/tasks/2.1.0/42): когда у родителя ЕСТЬ встречное @XdStorageAutoSave,
        // child-side not-reverse связь НЕ создается (дубликат плодил перевернутые
        // FK-записи) — исходящих не-реверс связей у Child нет до регистрации родителя.
        Collection<XdStorageForeignKeyRelation> childOutcomes = registry.getOutcomeRelations(childClassInfo);
        assertNotNull(childOutcomes);
        assertEquals(0, childOutcomes.size(), "Child-first не создает каноническую связь — владелец AutoSave-сторона родителя!");

        // После регистрации родителя появляется ЕДИНСТВЕННАЯ каноническая связь (P->C)
        registry.registerRelations(parentClassInfo);
        Collection<XdStorageForeignKeyRelation> parentOutcomes = registry.getOutcomeRelations(parentClassInfo);
        final long canonicalOutcomes = parentOutcomes.stream().filter(r -> !r.isReverseRelation()).count();
        assertEquals(1, canonicalOutcomes, "Ровно одна каноническая исходящая связь у Parent!");
        XdStorageForeignKeyRelation outcome = parentOutcomes.stream()
                .filter(r -> !r.isReverseRelation()).iterator().next();
        assertEquals(Parent.class, outcome.getClassFrom().getClazz());
        assertEquals(Child.class, outcome.getClassTo().getClazz());
        assertFalse(outcome.isReverseRelation());
        assertTrue(outcome.isParentAnchored(), "Каноническая связь принадлежит AutoSave-стороне родителя!");

        // Входящая связь для Parent со стороны Child — реверс-маркер @XdStorageParentObject
        Collection<XdStorageForeignKeyRelation> parentIncomes = registry.getIncomeRelations(parentClassInfo);
        assertEquals(1, parentIncomes.size(), "Родительский класс должен получить входящую связь от Child!");
        XdStorageForeignKeyRelation income = parentIncomes.iterator().next();
        assertEquals(Child.class, income.getClassFrom().getClazz());
        assertEquals(Parent.class, income.getClassTo().getClazz());
        assertTrue(income.isReverseRelation(), "Входящая связь Parent-а — реверс-маркер child-а");
    }
}
