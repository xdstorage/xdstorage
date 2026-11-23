package io.github.xdstorage.search.key;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("XdStorageSearchIndexKey: контракт compareTo ключа Б+Дерева поиска")
public class XdStorageSearchIndexKeyTest {

    private static XdStorageSearchIndexKey key(final Object value, final Object objectId, final
            XdStorageSearchIndexOperationType op) {
        return new XdStorageSearchIndexKey(value, objectId, op);
    }

    @Test
    @DisplayName("Геттеры и сеттеры")
    public void testAccessors() {
        final XdStorageSearchIndexKey k = new XdStorageSearchIndexKey();

        assertNull(k.getValue());
        assertNull(k.getObjectId());

        k.setValue("alpha");
        k.setObjectId(1L);
        k.setOperation(XdStorageSearchIndexOperationType.Insert);

        assertEquals("alpha", k.getValue());
        assertEquals(1L, k.getObjectId());
    }

    @Test
    @DisplayName("Двухаргументный конструктор: ключ без операции")
    public void testTwoArgConstructor() {
        final XdStorageSearchIndexKey k = new XdStorageSearchIndexKey("alpha", 1L);

        assertEquals("alpha", k.getValue());
        assertEquals(1L, k.getObjectId());
    }

    @Test
    @DisplayName("Сравнение по первичному значению: порядок строк")
    public void testValueOrdering() {
        assertTrue(key("alpha", null, null).compareTo(key("beta", null, null)) < 0);
        assertTrue(key("beta", null, null).compareTo(key("alpha", null, null)) > 0);
        assertEquals(0, key("alpha", null, null).compareTo(key("alpha", null, null)));

        // числа сравниваются как Comparable, а не как строки
        assertTrue(key(9, null, null).compareTo(key(10, null, null)) < 0);
        assertTrue(key("9", null, null).compareTo(key("10", null, null)) > 0);
    }

    @Test
    @DisplayName("null-значения упорядочены: null меньше любого значения")
    public void testNullValueOrdering() {
        assertEquals(0, key(null, null, null).compareTo(key(null, null, null)));
        assertTrue(key(null, null, null).compareTo(key("alpha", null, null)) < 0);
        assertTrue(key("alpha", null, null).compareTo(key(null, null, null)) > 0);
    }

    @Test
    @DisplayName("Равные значения уточняются сравнением objectId, если он есть у обеих сторон")
    public void testObjectIdTieBreak() {
        assertTrue(key("alpha", 1L, null).compareTo(key("alpha", 2L, null)) < 0);
        assertTrue(key("alpha", 2L, null).compareTo(key("alpha", 1L, null)) > 0);
        assertEquals(0, key("alpha", 1L, null).compareTo(key("alpha", 1L, null)));
    }

    @Test
    @DisplayName("Search-ключ (objectId = null) равен любой записи с тем же значением")
    public void testSearchKeyMatchesAllRecordsWithSameValue() {
        // objectId с одной стороны null -> уточнение не выполняется
        assertEquals(0, key("alpha", null, XdStorageSearchIndexOperationType.Search)
                .compareTo(key("alpha", 1L, XdStorageSearchIndexOperationType.Insert)));
        assertEquals(0, key("alpha", 1L, XdStorageSearchIndexOperationType.Insert)
                .compareTo(key("alpha", null, XdStorageSearchIndexOperationType.Search)));
        // разные значения по-прежнему различаются
        assertNotEquals(0, key("alpha", null, XdStorageSearchIndexOperationType.Search)
                .compareTo(key("beta", 1L, XdStorageSearchIndexOperationType.Insert)));
    }

    @Test
    @DisplayName("Тип операции НЕ влияет на сравнение (симметрия Insert/Search/Update/Delete)")
    public void testOperationTypeIgnored() {
        final XdStorageSearchIndexKey[] variants = {
                key("alpha", 1L, XdStorageSearchIndexOperationType.Insert),
                key("alpha", 1L, XdStorageSearchIndexOperationType.Update),
                key("alpha", 1L, XdStorageSearchIndexOperationType.Delete),
                key("alpha", 1L, XdStorageSearchIndexOperationType.Search),
                key("alpha", 1L, null),
        };

        for (final XdStorageSearchIndexKey a : variants) {
            for (final XdStorageSearchIndexKey b : variants) {
                assertEquals(0, a.compareTo(b),
                        () -> a.getValue() + "/" + a.getObjectId() + " vs " + b.getValue() + "/" + b.getObjectId());
            }
        }
    }

    @Test
    @DisplayName("Антисимметричность: sgn(compare(a,b)) == -sgn(compare(b,a)) на сетке ключей")
    public void testAntisymmetry() {
        final XdStorageSearchIndexKey[] grid = {
                key("alpha", 1L, XdStorageSearchIndexOperationType.Insert),
                key("alpha", 2L, XdStorageSearchIndexOperationType.Insert),
                key("beta", 1L, XdStorageSearchIndexOperationType.Insert),
                key("beta", 2L, XdStorageSearchIndexOperationType.Delete),
                key(null, 1L, null),
                key("alpha", null, XdStorageSearchIndexOperationType.Search),
                key(null, null, null),
        };

        for (final XdStorageSearchIndexKey a : grid) {
            for (final XdStorageSearchIndexKey b : grid) {
                final int ab = a.compareTo(b);
                final int ba = b.compareTo(a);
                assertEquals(Integer.signum(ab), -Integer.signum(ba),
                        () -> "нарушена антисимметрия: " + a.getValue() + "/" + a.getObjectId()
                                + " <-> " + b.getValue() + "/" + b.getObjectId());
            }
        }
    }

    @Test
    @DisplayName("toString возвращает строковое представление objectId")
    public void testToStringReturnsObjectId() {
        assertEquals("42", key("alpha", 42L, null).toString());
    }
}
