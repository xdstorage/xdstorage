package io.github.xdstorage.foreignkey;

import io.github.xdstorage.IXdFileStorage;
import io.github.xdstorage.XdStorageProvider;
import io.github.xdstorage.entities.foreignkeys.Child;
import io.github.xdstorage.entities.foreignkeys.Parent;
import io.github.xdstorage.exceptions.XdStorageException;
import io.github.xdstorage.exceptions.XdStorageRuntimeException;
import io.github.xdstorage.serialization.FilesFormat;
import io.github.xdstorage.transaction.IXdStorageTransaction;
import org.junit.jupiter.api.*;

import java.io.File;
import java.util.Collection;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("API Инварианты: Вставка и создание Foreign Keys")
public class XdStorageForeignKeyInsertionTest {

    private static final String STORAGE_PATH = "./target/test_fk_insertion_db";
    private IXdFileStorage storage;

    @BeforeEach
    public void setUp() {
        if (storage != null) storage.shutdown();
        deleteDir(new File(STORAGE_PATH));
        storage = XdStorageProvider.newOrGetFileStorage("fk_insert_test_1", STORAGE_PATH, 128, FilesFormat.YAML);
    }

    @AfterEach
    public void tearDown() {
        if (storage != null) {
            storage.shutdown();
        }
        deleteDir(new File(STORAGE_PATH));
    }

    private static void deleteDir(File file) {
        File[] contents = file.listFiles();
        if (contents != null) {
            for (File f : contents) deleteDir(f);
        }
        file.delete();
    }

    @Test
    @DisplayName("Инвариант: Сохранение только дочернего объекта с @XdStorageParentObject без родителя должно " +
            "выбрасывать исключение")
    public void testFK_SaveChildAlone_ShouldThrowException() throws Exception {
        IXdStorageTransaction tx = storage.beginTransaction();

        // Child с parent-ссылкой на не сохранённый Parent → deferred-ID, parent не сохранён → veto
        Child isolatedChild = new Child();
        isolatedChild.setId("isolated-child-id");
        Parent unsavedParent = new Parent();
        unsavedParent.setId(null); // manual-ID: ID не сгенерирован, parent не сохранён
        isolatedChild.setParent(unsavedParent);

        // ТРЕБОВАНИЕ: Одиночное сохранение дочернего объекта с обратной ссылкой на
        // не сохранённый родительский объект должно порождать крах коммита (veto).
        assertThrows(XdStorageRuntimeException.class, () -> {
            storage.save(isolatedChild, tx);
            tx.commit();
        }, "🚨 СУБД пропустила сохранение 'оторванного' дочернего объекта без родительского контекста!");
    }

    @Test
    @DisplayName("Инвариант: Повторное сохранение той же пары Parent-Child идемпотентно — дубликата пары нет")
    public void testFK_DuplicatePair_ShouldBeIdempotent() throws Exception {
        IXdStorageTransaction tx = storage.beginTransaction();

        Parent parent = new Parent();
        parent.setId("parent-root");
        Child child = new Child();
        child.setId("child-leaf");

        parent.addChild(child);
        child.setParent(parent);

        storage.save(parent, tx);

        // CANON (2026-11-20): повторная регистрация той же пары идемпотентна —
        // registerForeignKey поглощает дубликат (front.find().contains → no-op),
        // tree.insert абсорбирует "is exists". Ни исключения, ни второй пары.
        assertDoesNotThrow(() -> {
            storage.save(child, tx); // Повторный проход для того же графа в рамках одной TX
            tx.commit();
        }, "🚨 Повторная вставка существующей пары не должна ни дублировать её, ни падать!");

        // Верификация целостности: ровно одна пара, ребёнок не задвоен
        IXdStorageTransaction verifyTx = storage.beginTransaction();
        Parent loaded = storage.load(Parent.class, "parent-root", verifyTx);
        assertNotNull(loaded);
        Collection<?> children = loaded.getChildren();
        assertEquals(1, children.size(), "Expected exactly 1 pair (idempotent), got " + children.size());
        assertTrue(children.stream().anyMatch(c -> "child-leaf".equals(String.valueOf(((Child) c).getId()))),
                "child-leaf должен остаться единственным ребёнком");
        verifyTx.commit();
    }
}
