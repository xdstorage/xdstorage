package io.github.xdstorage.code;

import io.github.xdstorage.exceptions.XdStorageException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JUnit 5 тесты для верификации сгенерированного кода новыми декомпозированными классами-компонентами (Поинт В).
 */
public class XdStorageSubGeneratorsTest {

    private XdStorageSimpleWrapperClassCodeGenerator simpleGenerator;
    private XdStorageUnmodifiableWrapperClassCodeGenerator unmodifiableGenerator;

    @BeforeEach
    public void setUp() {
        simpleGenerator = new XdStorageSimpleWrapperClassCodeGenerator();
        unmodifiableGenerator = new XdStorageUnmodifiableWrapperClassCodeGenerator();
    }

    // === 1. ТЕСТИРОВАНИЕ XdStorageWrapperBlockGettersGenerator ===

    @Test
    public void testBlockGettersGenerator_ShouldEmitValidJavaSyntax() {
        StringBuilder builder = new StringBuilder();

        // Извлекаем контекст рефлексии для нашей тестовой сущности
        XdStorageAbstractClassCodeGenerator.GenerationContext ctx =
                simpleGenerator.collectMethodsAndClasses(ComplexEntityForGeneration.class);

        // Запускаем генерацию простых и сильных геттеров
        XdStorageWrapperBlockGettersGenerator.generateSimpleGetters(builder, ctx, simpleGenerator);
        XdStorageWrapperBlockGettersGenerator.generateStrongGetters(builder, ctx, simpleGenerator);
        XdStorageWrapperBlockGettersGenerator.generateLoadByGetGetters(builder, ctx, simpleGenerator);

        String generatedCode = builder.toString();

        // Проверяем инварианты структуры сгенерированного Java-кода
        assertNotNull(generatedCode);
        assertTrue(generatedCode.contains("public java.lang.Long getId()"), "Должен сгенерировать public-геттер для ID");
        assertTrue(generatedCode.contains("return object.getId()"), "Должен перенаправлять вызов к оригинальному объекту");
    }

    // === 2. ТЕСТИРОВАНИЕ XdStorageWrapperBlockSettersGenerator ===

    @Test
    public void testBlockSettersGenerator_ShouldEmitValidSetters() {
        StringBuilder builder = new StringBuilder();
        XdStorageAbstractClassCodeGenerator.GenerationContext ctx =
                simpleGenerator.collectMethodsAndClasses(ComplexEntityForGeneration.class);

        // Генерируем сеттеры
        XdStorageWrapperBlockSettersGenerator.generateSettersMethods(builder, ctx, simpleGenerator);
        String generatedCode = builder.toString();

        assertTrue(generatedCode.contains("public void setName(java.lang.String var0)"), "Должен сгенерировать сеттер для Name");
        assertTrue(generatedCode.contains("object.setName(var0)"), "Сеттер должен мутировать оригинальный объект");
    }

    // === 3. ТЕСТИРОВАНИЕ XdStorageUnmodifiableBlockGettersGenerator ===

    @Test
    public void testUnmodifiableBlockGetters_ShouldEmitSecureTransactionLocks() {
        StringBuilder builder = new StringBuilder();
        XdStorageAbstractClassCodeGenerator.GenerationContext ctx =
                unmodifiableGenerator.collectMethodsAndClasses(ComplexEntityForGeneration.class);

        // Генерируем геттеры ленивой загрузки unmodifiable-оберток Snapshot-состояний
        XdStorageUnmodifiableBlockGettersGenerator.generateLoadByGetGetters(builder, ctx, unmodifiableGenerator);
        String generatedCode = builder.toString();

        // Проверяем защитные инварианты: unmodifiable-геттер обязан вызывать защиту транзакции markRollbackOnly при сбоях!
        // RBCAUSE: the mark carries the first-cause site descriptor (field + entity class)
        assertTrue(generatedCode.contains("transaction.markRollbackOnly(\"lazy-load of field '"),
                "Немодифицируемая обертка обязана метить транзакцию на откат при ошибках I/O ленивой загрузки (с дескриптором места)!");
        assertTrue(generatedCode.contains("storage.load"),
                "Должен присутствовать вызов дискового движка хранения");
    }

    // === 4. ТЕСТИРОВАНИЕ XdStorageUnmodifiableBlockSettersGenerator (ЗАЩИТНЫЙ КОНТУР) ===

    @Test
    public void testUnmodifiableBlockSetters_ShouldEmitStrictGuardsAndRollbackMarking() {
        StringBuilder builder = new StringBuilder();

        // Получаем контекст метаданных рефлексии для ComplexEntityForGeneration
        XdStorageAbstractClassCodeGenerator.GenerationContext ctx =
                unmodifiableGenerator.collectMethodsAndClasses(ComplexEntityForGeneration.class);

        // Генерируем блокирующие сеттеры и инфраструктурные методы для немодифицируемой обертки
        XdStorageUnmodifiableBlockSettersGenerator.generateSettersMethods(builder, ctx, unmodifiableGenerator);
        XdStorageUnmodifiableBlockSettersGenerator.generateClosedMethods(builder, ctx, unmodifiableGenerator);

        String generatedCode = builder.toString();

        assertNotNull(generatedCode);

        // ПРОВЕРКА ИНВАРИАНТА БЕЗОПАСНОСТИ SNAPSHOT:
        // Любой сеттер в Unmodifiable-обертке обязан принудительно вызывать markRollbackOnly(),
        // предотвращая порчу консистентности данных в базе.
        // RBCAUSE: the mark carries the first-cause site descriptor (setter + entity class)
        assertTrue(generatedCode.contains("transaction.markRollbackOnly(\"modification of viewable object: setter '"),
                "Сгенерированный сеттер обязан помечать транзакцию на откат (с дескриптором места)!");

        // Проверяем, что генерируется жесткое fail-fast исключение
        assertTrue(generatedCode.contains("throw new io.github.xdstorage.exceptions.XdStorageRuntimeException"),
                "Мутирующий метод обязан выбрасывать XdStorageRuntimeException наружу!");

        assertTrue(generatedCode.contains("\"modification of viewable object is not allowed\""),
                "Исключение должно содержать понятный пользователю текст ошибки");
    }

    // === 5. ТЕСТИРОВАНИЕ СЛУЖЕБНЫХ ИНФРАСТРУКТУРНЫХ МЕТОДОВ SIMPLE WRAPPER ===

    @Test
    public void testBlockSettersGenerator_ShouldEmitClosedMethodsCorrectly() {
        StringBuilder builder = new StringBuilder();

        XdStorageAbstractClassCodeGenerator.GenerationContext ctx =
                simpleGenerator.collectMethodsAndClasses(ComplexEntityForGeneration.class);

        // Генерируем закрытые системные методы для SimpleWrapper
        XdStorageWrapperBlockSettersGenerator.generateClosedMethods(builder, ctx, simpleGenerator);

        String generatedCode = builder.toString();

        assertNotNull(generatedCode);

        // Проверяем, что для простых оберток инфраструктурные методы генерируются как сквозные,
        // а не блокирующие (в отличие от unmodifiable-оберток)
        assertFalse(generatedCode.contains("transaction.markRollbackOnly();"),
                "Инфраструктурные методы SimpleWrapper не должны приводить к откату транзакции");
    }

}