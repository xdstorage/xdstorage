package io.github.xdstorage.serialization.yaml;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Юнит-тест: Проверка конечного автомата YAML-эмиттера СУБД")
public class XdStorageYamlEmitterTest {

    @Test
    @DisplayName("Проверка геометрии отступов: тест тотально типизированной каскадной вложенности полей СУБД")
    public void testEmitter_CascadeBlocks_ShouldGenerateCorrectTabulations() throws Exception {
        StringWriter stringWriter = new StringWriter();
        XdStorageYamlEmitter emitter = new XdStorageYamlEmitter(stringWriter);

        // Симулируем генерацию нового строгого объектного формата СУБД
        emitter.openBlock("objects");
        emitter.openBlock("- object");

        emitter.writeKey("class");
        emitter.writeScalar("'io.github.xdstorage.entities.XdGalaxy'");

        emitter.openBlock("fields");
        emitter.openBlock("- field");

        emitter.writeKey("name");
        emitter.writeScalar("'id'");
        emitter.writeKey("type");
        emitter.writeScalar("'java.lang.String'");
        emitter.writeKey("value");
        emitter.writeScalar("'galaxy-001'");

        emitter.closeBlock(); // Закрываем "- field" для id

        emitter.openBlock("- field");
        emitter.writeKey("name");
        emitter.writeScalar("'hole'");
        emitter.writeKey("type");
        emitter.writeScalar("'io.github.xdstorage.entities.XdBlackHole'");

        emitter.openBlock("value");
        emitter.openBlock("object");
        emitter.writeKey("class");
        emitter.writeScalar("'io.github.xdstorage.entities.XdBlackHole'");

        // Закрываем каскад вложенности Кнута обратно до корня
        emitter.closeBlock(); // object
        emitter.closeBlock(); // value
        emitter.closeBlock(); // - field для hole

        emitter.closeBlock(); // fields
        emitter.closeBlock(); // - object
        emitter.closeBlock(); // objects

        // Ожидаем идеальный стандартный YAML новой мета-модели на табуляциях с одинарными кавычками
        String expectedYaml =
                "objects:\r\n" +
                        "\t- object:\r\n" +
                        "\t\tclass: 'io.github.xdstorage.entities.XdGalaxy'\r\n" +
                        "\t\tfields:\r\n" +
                        "\t\t\t- field:\r\n" +
                        "\t\t\t\tname: 'id'\r\n" +
                        "\t\t\t\ttype: 'java.lang.String'\r\n" +
                        "\t\t\t\tvalue: 'galaxy-001'\r\n" +
                        "\t\t\t- field:\r\n" +
                        "\t\t\t\tname: 'hole'\r\n" +
                        "\t\t\t\ttype: 'io.github.xdstorage.entities.XdBlackHole'\r\n" +
                        "\t\t\t\tvalue:\r\n" +
                        "\t\t\t\t\tobject:\r\n" +
                        "\t\t\t\t\t\tclass: 'io.github.xdstorage.entities.XdBlackHole'\r\n";

        assertEquals(expectedYaml, stringWriter.toString(),
                "🚨 Конечный автомат нарушил контракт отступов новой мета-модели!");
    }

    @Test
    @DisplayName("Fail-Safe защита: вызовы closeBlock не должны уводить автомат в отрицательные отступы")
    public void testEmitter_NegativeLevelGuard_ShouldStayAtZero() throws Exception {
        StringWriter stringWriter = new StringWriter();
        XdStorageYamlEmitter emitter = new XdStorageYamlEmitter(stringWriter);

        emitter.closeBlock();
        emitter.closeBlock();

        emitter.writeKey("rootKey");
        emitter.writeScalar("100");

        assertEquals("rootKey: 100\r\n", stringWriter.toString());
    }
}
