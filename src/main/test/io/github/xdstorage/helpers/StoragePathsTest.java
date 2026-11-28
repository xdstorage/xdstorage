package io.github.xdstorage.helpers;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The path spellings the tests lean on where the separator differs between CI legs. */
@DisplayName("helpers: StoragePaths — the slash whatever the separator, a whole path name")
public class StoragePathsTest {

    @Test
    @DisplayName("слэши: бэкслеши превращаются в '/' — в том числе в записи, которую Path сделал бы на Windows")
    public void slashesTurnBackslashesIntoSlashes() {
        assertEquals("db/data/Order/Order-1.yaml", StoragePaths.slashes(Paths.get("db/data/Order/Order-1.yaml")));
        // one name that carries backslashes — the shape Path.toString() has on Windows
        assertEquals("C:/db/data/Order", StoragePaths.slashes(Paths.get("C:\\db\\data\\Order")));
    }

    @Test
    @DisplayName("элемент пути: целое имя, а не подстрока — и имя самого файла тоже считается")
    public void hasElementMatchesWholeNamesOnly() {
        final Path journal = Paths.get("/db/data/Order/journal/Order-1-journal.yaml");
        assertTrue(StoragePaths.hasElement(journal, "data"));
        assertTrue(StoragePaths.hasElement(journal, "journal"));
        assertTrue(StoragePaths.hasElement(journal, "Order-1-journal.yaml"), "the file's own name is a name");
        assertFalse(StoragePaths.hasElement(journal, "dat"), "a substring is not a name");
        assertFalse(StoragePaths.hasElement(journal, "Order-1"), "a prefix of the file name is not a name");
        assertFalse(StoragePaths.hasElement(Paths.get("/db/order/data"), "Order"), "names are case-sensitive here");
    }
}
