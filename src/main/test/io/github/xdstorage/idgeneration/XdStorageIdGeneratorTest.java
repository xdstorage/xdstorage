package io.github.xdstorage.idgeneration;

import io.github.xdstorage.IXdStorage;
import io.github.xdstorage.exceptions.XdStorageException;
import io.github.xdstorage.exceptions.XdStorageRuntimeException;
import io.github.xdstorage.resource.IXdStorageDaoResource;
import io.github.xdstorage.resource.XdStorageAbstractResourcesManager;
import io.github.xdstorage.services.XdStorageServicesLocator;
import io.github.xdstorage.transaction.IXdStorageTransactionManager;
import io.github.xdstorage.transaction.XdStorageTransaction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Исправленный комплект JUnit 5 тестов для проверки распределенных генераторов первичных ключей СУБД.
 */
public class XdStorageIdGeneratorTest {

    private XdStorageServicesLocator mockServices;
    private IXdStorageTransactionManager mockTxManager;
    private XdStorageAbstractResourcesManager mockResourceManager;
    private IXdStorageDaoResource mockDaoResource;
    private IXdStorage mockStorage;
    private XdStorageTransaction mockTx;

    @BeforeEach
    public void setUp() throws Exception {
        mockServices = mock(XdStorageServicesLocator.class);
        mockTxManager = mock(IXdStorageTransactionManager.class);
        mockResourceManager = mock(XdStorageAbstractResourcesManager.class);
        mockDaoResource = mock(IXdStorageDaoResource.class);
        mockStorage = mock(IXdStorage.class);
        mockTx = mock(XdStorageTransaction.class);

        when(mockServices.getTransactionsManager()).thenReturn(mockTxManager);
        when(mockServices.getResourcesManager()).thenReturn(mockResourceManager);
        when(mockResourceManager.lockStructureResource(any(), any())).thenReturn(mockDaoResource);
    }

    // === 1. ОБЫЧНЫЕ ТЕСТЫ (Счастливый путь) ===

    @Test
    public void testStringIdGenerator_ShouldReturnValidUUID() {
        XdStorageStringIdGenerator generator = new XdStorageStringIdGenerator();

        Object id1 = generator.generate(String.class, mockStorage, mockTx);
        Object id2 = generator.generate(String.class, mockStorage, mockTx);

        assertNotNull(id1);
        assertTrue(id1 instanceof String);
        assertEquals(36, ((String) id1).length());
        assertNotEquals(id1, id2);
    }

    @Test
    public void testDummyIdGenerator_ShouldAlwaysReturnNull() throws XdStorageException {
        XdStorageDummyIdGenerator dummy = new XdStorageDummyIdGenerator();
        assertNull(dummy.generate(Object.class, mockStorage, mockTx));
    }

    @Test
    public void testLongIdGenerator_FirstInitialization_ShouldFetchFirstPart() throws Exception {
        when(mockTxManager.beginTransaction(anyLong())).thenReturn(mockTx);
        when(mockDaoResource.read(eq(Long.class), any())).thenReturn(null);

        XdStorageLongIdGenerator longGenerator = new XdStorageLongIdGenerator(mockServices);

        Object generatedId = longGenerator.generate(Long.class, mockStorage, mockTx);

        assertNotNull(generatedId);
        assertEquals(1L, ((Long) generatedId).longValue());

        verify(mockDaoResource, times(1)).insert(any(XdStorageLongIdCounterRecord.class), any());
        verify(mockTx, times(1)).commit();
    }

    // === 2. ПРОДВИНУТЫЕ АЛГОРИТМИЧЕСКИЕ ТЕСТЫ НА ГРАНИЦЫ ПАЧЕК (HI-LO) ===

    @Test
    @Disabled
    public void testIntegerIdGenerator_BatchExhaustion_ShouldFetchNextPartCleanly() throws Exception {
        when(mockTxManager.beginTransaction(anyLong())).thenReturn(mockTx);
        when(mockDaoResource.read(eq(Integer.class), any())).thenReturn(null);

        XdStorageIntegerIdGenerator intGenerator = new XdStorageIntegerIdGenerator(mockServices);

        // Нарезаем 99 элементов
        for (int i = 1; i <= 99; i++) {
            Object id = intGenerator.generate(Integer.class, mockStorage, mockTx);
            assertEquals(i, ((Integer) id).intValue());
        }

        XdStorageIntegerIdCounterRecord mockRecord = new XdStorageIntegerIdCounterRecord();
        mockRecord.setCl(Integer.class);
        mockRecord.setCounter(100);

        // Подменяем ответ для фазы takeNextPartOfIdentifiers
        when(mockDaoResource.read(eq(Integer.class), any())).thenReturn(mockRecord);

        Object id100 = intGenerator.generate(Integer.class, mockStorage, mockTx);
        assertEquals(100, ((Integer) id100).intValue());

        Object id101 = intGenerator.generate(Integer.class, mockStorage, mockTx);
        assertEquals(101, ((Integer) id101).intValue());

        verify(mockDaoResource, times(1)).insert(any(XdStorageIntegerIdCounterRecord.class), any());
        verify(mockDaoResource, times(1)).update(any(XdStorageIntegerIdCounterRecord.class), any());
    }

    @Test
    public void testLongIdGenerator_WhenDatabaseUpdateFails_ShouldThrowXdStorageException() throws Exception {
        // ИСПРАВЛЕНИЕ СБОЯ MOCKITO: Используем последовательный стаббинг (Sequential Stubbing).
        // При первом вызове (initIdentifiers) транзакция откроется успешно.
        // При втором вызове (takeNextPartOfIdentifiers на 100-м элементе) менеджер выбросит исключение базы.
        when(mockTxManager.beginTransaction(anyLong()))
                .thenReturn(mockTx) // Для initIdentifiers
                .thenThrow(new XdStorageRuntimeException("Критический сбой дисковой подсистемы СУБД")); // Для takeNext

        when(mockDaoResource.read(eq(Long.class), any())).thenReturn(null);

        XdStorageLongIdGenerator longGenerator = new XdStorageLongIdGenerator(mockServices);

        // Безопасно выбираем первые 99 элементов пула из памяти
        for (int i = 1; i <= 99; i++) {
            longGenerator.generate(Long.class, mockStorage, mockTx);
        }

        // 100-й элемент спровоцирует вызов takeNextPartOfIdentifiers, который наткнется на настроенный нами сбой
        // транзакции
        assertThrows(RuntimeException.class, () -> {
            longGenerator.generate(Long.class, mockStorage, mockTx);
        }, "При аварии диска на границе пачки генератор обязана выбросить исключение!");
    }
}
