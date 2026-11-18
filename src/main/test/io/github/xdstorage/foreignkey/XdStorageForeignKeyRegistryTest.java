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

        // 2. FNKEY (rule 4): income = @XdStorageParentObject forward only (not @XdStorageAutoSave).
        // Child's income: one relation (@XdStorageParentObject forward: Child→Parent).
        Collection<XdStorageForeignKeyRelation> childIncomes = registry.getIncomeRelations(childClassInfo);
        assertNotNull(childIncomes);
        assertEquals(1, childIncomes.size(), "Child имеет одну income-связь (@XdStorageParentObject forward)!");

        final long canonical = childIncomes.stream().filter(r -> !r.isReverseRelation()).count();
        assertEquals(1, canonical, "Ровно одна каноническая (не-реверс) входящая связь у Child!");
        final XdStorageForeignKeyRelation income = childIncomes.stream()
                .filter(r -> !r.isReverseRelation()).iterator().next();
        // income = @XdStorageParentObject forward: classFrom=Child, classTo=Parent
        assertEquals(Child.class, income.getClassFrom().getClazz(), "Источник income-связи для ребенка — это Child!");
        assertEquals(Parent.class, income.getClassTo().getClazz(), "Цель income-связи для ребенка — это Parent!");
        assertFalse(income.isParentAnchored(), "income-связь не принадлежит AutoSave-стороне!");
    }

    @Test
    @DisplayName("FNKEY: Child-first регистрация не создает дубликат связи — канонической остается AutoSave-сторона родителя")
    public void testRegister_WithRealChild_ShouldBuildCorrectHierarchy() {
        registry.registerRelations(childClassInfo);

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

        // Входящие связи для Parent: income = @XdStorageParentObject forward only.
        // Parent has NO @XdStorageParentObject forward, so income is empty.
        Collection<XdStorageForeignKeyRelation> parentIncomes = registry.getIncomeRelations(parentClassInfo);
        assertEquals(0, parentIncomes.size(), "Parent не имеет income-связей (нет @XdStorageParentObject forward)!");
    }
}
