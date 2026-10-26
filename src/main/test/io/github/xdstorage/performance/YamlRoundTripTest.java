package io.github.xdstorage.performance;

import io.github.xdstorage.serialization.yaml.XdStorageYamlObjectsReader;
import io.github.xdstorage.serialization.yaml.XdStorageYamlObjectsWriter;
import io.github.xdstorage.utils.XdStorageObjectIdField;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Collection;

/**
 * Диагностика движка YAML: прямой round-trip реального файла хранилища.
 * Проверяет, на какой поколении записи/чтения появляются NULL-элементы коллекций.
 */
@Disabled
public class YamlRoundTripTest {

    @Test
    public void roundTripRealFile() throws Exception {
        final File dir = new File("teststorage/data/XdUniverse");
        final File[] files = dir.listFiles((d, n) -> n.endsWith(".yaml") && !n.equals("index.yaml"));
        if (files == null || files.length == 0) {
            System.out.println("### no universe yaml files — run the storage test first");
            return;
        }

        final XdStorageYamlObjectsReader reader = new XdStorageYamlObjectsReader(
                new io.github.xdstorage.helpers.XdStorageDefaultSimpleTypeHelper());

        for (final File f : files) {
            System.out.println("### FILE: " + f.getName());
            final String original = new String(Files.readAllBytes(Paths.get(f.toURI())));
            final Collection<Object> parsed1 = reader.read(new StringReader(original));
            System.out.println("### gen1 parsed=" + parsed1.size());
            int n1 = 0;
            for (final Object o : parsed1) {
                final Collection<?> gal = (Collection<?>) o.getClass().getMethod("getGalaxies").invoke(o);
                int nulls = 0;
                for (final Object x : gal) if (x == null) nulls++;
                if (nulls > 0) {
                    System.out.println("### gen1 NULLS in galaxies: " + nulls + "/" + gal.size()
                        + " id=" + o.getClass().getMethod("getId").invoke(o));
                    n1++;
                }
            }
            if (n1 == 0) System.out.println("### gen1: no null galaxies");

            // Повторная запись прочитанного и повторное чтение (второе поколение)
            final XdStorageYamlObjectsWriter writer = new XdStorageYamlObjectsWriter(null, null, null);
            final StringWriter out = new StringWriter();
            writer.writeObjects(out, parsed1);
            final Collection<Object> parsed2 = reader.read(new StringReader(out.toString()));
            System.out.println("### gen2 parsed=" + parsed2.size());
            for (final Object o : parsed2) {
                final Collection<?> gal = (Collection<?>) o.getClass().getMethod("getGalaxies").invoke(o);
                int nulls = 0;
                for (final Object x : gal) if (x == null) nulls++;
                if (nulls > 0) System.out.println("### gen2 NULLS in galaxies: " + nulls + "/" + gal.size());
            }
        }
    }
}
