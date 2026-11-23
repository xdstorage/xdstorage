package io.github.xdstorage.code;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/**
 * JUnit 5 тесты для проверки строковых генераторов сигнатур в XdStorageAbstractClassCodeGenerator.
 */
public class XdStorageAbstractClassCodeGeneratorTest {

    // Локальная минимальная реализация для тестирования строковых хелперов базового класса
    private static class ConcreteTestGenerator extends XdStorageAbstractClassCodeGenerator {
        @Override
        public Map<String, String> generate(String classesPackage, Class<?> cl, Map<String, String> code) {
            return code;
        }
        @Override
        protected String buildClassName(String classesPackage, Class<?> cl) {
            return cl.getSimpleName() + "TestWrapper";
        }
    }

    // === 1. ОБЫЧНЫЕ ТЕСТЫ (Счастливый путь) ===

    @Test
    public void testBuildMethodCalling_HappyPath_ShouldFormatCorrectly() {
        ConcreteTestGenerator generator = new ConcreteTestGenerator();
        Class<?>[] paramTypes = new Class<?>[]{String.class, int.class};

        // Проверяем генерацию вызова проксируемого метода: метод(var0, var1)
        String callString = generator.buildMethodCalling("executeUpdate", paramTypes);

        assertEquals("executeUpdate(var0, var1)", callString);
    }

    @Test
    public void testBuildMethodDefinition_SimpleMethod_ShouldGenerateValidJavaCode() {
        ConcreteTestGenerator generator = new ConcreteTestGenerator();
        Class<?>[] paramTypes = new Class<?>[]{long.class};
        Class<?>[] exceptions = new Class<?>[]{Exception.class};

        // Строим определение простого метода без generic-параметров
        String definition = generator.buildMethodDefinition(
                1, "setId", null, void.class, void.class, paramTypes, null, exceptions
        );

        assertNotNull(definition);
        assertTrue(definition.contains("public void setId(long var0)"));
        assertTrue(definition.contains("throws java.lang.Exception"));
    }

    // === 2. ТЕСТЫ НА ГРАНИЧНЫЕ УСЛОВИЯ ===

    @Test
    public void testBuildMethodCalling_WithNoArguments_ShouldReturnEmptyParentheses() {
        ConcreteTestGenerator generator = new ConcreteTestGenerator();

        // Граничное условие: Метод без аргументов (например, дефолтный геттер)
        String callString = generator.buildMethodCalling("getName", new Class<?>[0]);

        assertEquals("getName()", callString);
    }
}
