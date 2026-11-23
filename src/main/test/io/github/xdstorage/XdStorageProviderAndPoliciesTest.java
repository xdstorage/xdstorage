package io.github.xdstorage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("3. Спецификации XdStoragePolicy, фабрики XdStorageProvider и обновления метаструктур")
public class XdStorageProviderAndPoliciesTest extends AbstractXdStorageTest {

    @BeforeEach
    public void localSetUp() throws Exception {
        baseSetUp();
    }

    @Test
    @DisplayName("Верификация предикатов XdStoragePolicy: независимые ресурсы и встроенные типы")
    public void testPolicies_PredicatesContract() {
        // Проверяем политику StoreAsSingleObject
        assertTrue(XdStoragePolicy.StoreAsSingleObject.isIndependentResource(),
                "Политика обязана требовать выделенного дискового ресурса DAO!");
        assertFalse(XdStoragePolicy.StoreAsSingleObject.isEmbedded());

        // Проверяем политику StoreWithParentObject
        assertFalse(XdStoragePolicy.StoreWithParentObject.isIndependentResource());
        assertTrue(XdStoragePolicy.StoreWithParentObject.isEmbedded(),
                "Политика обязана идентифицироваться как встроенный тип!");
    }

    @Test
    @DisplayName("Проверка XdStorageProvider: потокобезопасный синглтон-пул обязан возвращать один и тот же инстанс " +
            "хранилища")
    public void testProvider_SingletonPool_ShouldBeIdempotent() {
        String storageName = "unique-file-db";
        String folderPath = "./target/provider_test_db";

        IXdFileStorage storage1 = XdStorageProvider.newOrGetFileStorage(storageName, folderPath, 128);
        IXdFileStorage storage2 = XdStorageProvider.newOrGetFileStorage(storageName, folderPath, 128);

        assertNotNull(storage1);
        assertSame(storage1, storage2,
                "Фабрика XdStorageProvider нарушила паттерн Singleton и расплодила дубликаты хранилищ в " +
                "ConcurrentHashMap!");

        // Корректно закрываем и разрегистрируем тестовое хранилище
        storage1.shutdown();
    }

    @Test
    @DisplayName("Метод executeStructureUpdate() обязан транслировать вызов миграции схемы в StructureManager")
    public void testStructureUpdate_ShouldForwardToStructureManager() throws Exception {
        assertDoesNotThrow(() -> storage.executeStructureUpdate(ClassPolicyEntity.class));
        verify(mockStructureManager, times(1)).checkAndUpdateStructureIfNeed(eq(ClassPolicyEntity.class), eq(mockTx));
    }
}
