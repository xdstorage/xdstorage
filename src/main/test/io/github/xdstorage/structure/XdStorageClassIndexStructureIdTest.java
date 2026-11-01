package io.github.xdstorage.structure;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * JUnit 5 тесты для проверки базовых инвариантов и контрактов XdStorageClassIndexStructureId.
 */
public class XdStorageClassIndexStructureIdTest {

    // === 1. ОБЫЧНЫЕ ТЕСТЫ (Счастливый путь) ===

    @Test
    public void testGettersAndSetters_HappyPath() {
        XdStorageClassIndexStructureId id = new XdStorageClassIndexStructureId();

        id.setName("idx_by_email");
        id.setType(String.class.getName());

        assertEquals("idx_by_email", id.getName());
        assertEquals(String.class.getName(), id.getType());
        // Проверяем кастомный строковый дескриптор метакаталога
        assertEquals("String:idx_by_email", id.toString());
    }

    @Test
    public void testEqualsAndHashCode_Contract() {
        XdStorageClassIndexStructureId id1 = new XdStorageClassIndexStructureId();
        id1.setName("idx_a");
        id1.setType(Integer.class.getName());

        XdStorageClassIndexStructureId id2 = new XdStorageClassIndexStructureId();
        id2.setName("idx_a");
        id2.setType(Integer.class.getName());

        XdStorageClassIndexStructureId id3 = new XdStorageClassIndexStructureId();
        id3.setName("idx_b");
        id3.setType(Integer.class.getName());

        // Проверяем каноничный контракт равенства полей
        assertEquals(id1, id2);
        assertEquals(id1.hashCode(), id2.hashCode());

        assertNotEquals(id1, id3);
    }

    // === 2. ТЕСТЫ НА ГРАНИЧНЫЕ УСЛОВИЯ ===

    @Test
    public void testEquals_WithNullAndForeignObject_ShouldReturnFalseSafely() {
        XdStorageClassIndexStructureId id = new XdStorageClassIndexStructureId();

        assertNotEquals(null, id);
        assertNotEquals("foreign_string", id);
    }
}
