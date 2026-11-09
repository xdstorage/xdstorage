package io.github.xdstorage.foreignkey.index.btree;

import io.github.xdstorage.IXdFileStorage;
import io.github.xdstorage.XdStorageProvider;
import io.github.xdstorage.transaction.IXdStorageTransaction;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.UUID;

public abstract class AbstractForeignKeyBaseSpecificationTest {

    protected static IXdFileStorage storage;
    protected static IXdStorageTransaction tx;
    private static Path tempFolder;
    private static String storageName;

    @BeforeAll
    static void initStorage() throws Exception {
        // Гарантируем абсолютную уникальность имени для JVM-wide реестра СУБД
        storageName = "fk_spec_db_" + UUID.randomUUID().toString().replace("-", "");
        tempFolder = Files.createTempDirectory("xdstorage_fk_tests_");

        storage = XdStorageProvider.newOrGetFileStorage(
                storageName,
                tempFolder.toAbsolutePath().toString()
        );
    }

    @AfterAll
    static void destroyStorage() throws Exception {
        if (storage != null) {
            try {
                storage.shutdown(); // Сбрасывает журналы, закрывает файлы
            } catch (Exception ignored) {}
        }

        // Жесткая принудительная зачистка статического реестра СУБД между тестами через рефлексию,
        // если unregisterStorage не отработал до конца из-за ошибок коммита
        try {
            Field storagesField = XdStorageProvider.class.getDeclaredField("storages");
            storagesField.setAccessible(true);
            Map<?, ?> storagesMap = (Map<?, ?>) storagesField.get(null);
            storagesMap.remove(storageName);
        } catch (Exception ignored) {}

        if (tempFolder != null && Files.exists(tempFolder)) {
            Files.walk(tempFolder)
                    .sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
        }
    }
}
