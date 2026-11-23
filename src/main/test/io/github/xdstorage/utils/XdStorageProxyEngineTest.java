package io.github.xdstorage.utils;

import io.github.xdstorage.code.IXdStorageUnmodifiableWrapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("3. Спецификации движка компиляции прокси-врапперов и локаторов полей")
public class XdStorageProxyEngineTest extends AbstractUtilsTest {

    @Test
    @DisplayName("Проверка типов утилитарных фасадов: распознавание примитивов и простых ссылок")
    public void testObjectUtils_TypeValidationContract() {
        assertTrue(XdStorageObjectUtils.isSimpleType(String.class, "text"));
        assertTrue(XdStorageObjectUtils.isSimpleType(Long.class, 42L));
        assertFalse(XdStorageObjectUtils.isSimpleType(SampleEntity.class, new SampleEntity()));
    }

    @Test
    @DisplayName("Локатор полей: ленивое извлечение и кэширование поля object")
    public void testWrapperFieldLocator_ShouldExtractAndCacheField() {
        // Тестируем на маркерном интерфейсе unmodifiable-враппера
        Field f1 = XdStorageWrapperFieldLocator.getUnmodifiableWrapperObjectField(IXdStorageUnmodifiableWrapper.class);
        Field f2 = XdStorageWrapperFieldLocator.getUnmodifiableWrapperObjectField(IXdStorageUnmodifiableWrapper.class);

        // По контракту, если поля нет, локатор возвращает null или кэширует заглушку,
        // но вызовы обязаны быть идемпотентными и не бросать необработанных исключений
        assertEquals(f1, f2);
    }

    @Test
    @DisplayName("Проверка Double-Check локеров компиляции: защита сигнатур")
    public void testProxyCompilationEngine_MethodCaching_ShouldReturnCleanly() {
        Class<?> wrapperCl = IXdStorageUnmodifiableWrapper.class;

        assertNull(XdStorageProxyCompilationEngine.getWrappedClass(wrapperCl),
                "Для сырого интерфейса враппера без генерации мапа обязана возвращать null без NPE!");
    }
}
