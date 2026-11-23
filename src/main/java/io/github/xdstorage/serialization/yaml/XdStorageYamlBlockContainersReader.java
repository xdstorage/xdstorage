package io.github.xdstorage.serialization.yaml;

import io.github.xdstorage.utils.XdStorageClassMetadataRegistry;
import io.github.xdstorage.helpers.IXdStorageSimpleTypeHelper;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Dedicated stateless component for demarshalling Java collections, maps and enums
 * from the token stream. Elements are extracted strictly from the typed cell structure
 * written by {@link XdStorageYamlBlockContainersWriter} — {@code - item:} list cells
 * and {@code - entry:} cells with {@code key:}/{@code value:} pairs, each carrying an
 * explicit {@code type:} and a scalar or nested {@code value:} block.
 *
 * <p>Thread-safety: all methods are static and stateless, so the class is thread-safe.
 * Engine-internal — not part of the public API.</p>
 */
public class XdStorageYamlBlockContainersReader {

    /**
     * Utility class — no instances.
     */
    public XdStorageYamlBlockContainersReader() {
    }

    /**
     * Reads an {@code enum} block: consumes the {@code class:} and {@code value:} keys at
     * the current level and restores the constant through {@code Enum.valueOf}.
     *
     * @param tokenizer the token stream positioned at the enum's {@code class:}/{@code value:} keys
     * @return the restored enum constant, or {@code null} when the class or value key is absent
     * @throws Exception when the enum class cannot be resolved or has no constant with the stored name
     */
    public static Object readEnum(final XdStorageYamlTokenizer tokenizer) throws Exception {
        String className = null;
        String enumValue = null;

        YamlLineToken token;
        while ((token = tokenizer.peekToken()) != null) {
            if (!"class".equals(token.key) && !"value".equals(token.key)) {
                break;
            }
            token = tokenizer.nextToken();
            if ("class".equals(token.key)) {
                className = token.value;
            } else if ("value".equals(token.key)) {
                enumValue = token.value;
            }
        }

        if (className == null || enumValue == null) return null;

        final Class<?> cl = XdStorageClassMetadataRegistry.resolveOrThrow(className);
        final Method m = cl.getMethod("valueOf", String.class);
        return m.invoke(cl, enumValue);
    }

    /**
     * Reads a {@code collection} block: instantiates the stored collection class (falling
     * back to {@code LinkedList} for non-instantiable wrappers) and restores every
     * {@code - item:} cell below {@code parentLevel} as a typed value.
     *
     * @param tokenizer        the token stream positioned at the collection's {@code class:} key
     * @param parentLevel      the tab level of the {@code collection} block itself; deeper
     *                         tokens belong to the collection
     * @param simpleTypeHelper the simple-type SPI used to restore scalar item values
     * @return the restored collection, or {@code null} when the {@code class:} key is absent
     * @throws Exception when the collection class cannot be resolved or instantiated, or an
     *         item value cannot be materialized
     */
    @SuppressWarnings("unchecked")
    public static Object readCollection(final XdStorageYamlTokenizer tokenizer, final int parentLevel, final
            IXdStorageSimpleTypeHelper simpleTypeHelper) throws Exception {
        YamlLineToken classToken = tokenizer.nextToken();
        if (classToken == null || !"class".equals(classToken.key)) {
            return null;
        }

        Class<?> cl = XdStorageClassMetadataRegistry.resolveOrThrow(classToken.value);
        Collection<Object> collection;
        try {
            collection = (Collection<Object>) cl.newInstance();
        } catch (final Exception e) {
            // Неинстанцируемый класс коллекции (обертка java.util.Collections$SynchronizedList и т.п.) —
            // канонический фолбэк LinkedList; владелец-нода сама оборачивает списки
            // в synchronized-обертки в setKeys/setObjects/setChildren.
            collection = new java.util.LinkedList<>();
        }

        YamlLineToken token;
        while ((token = tokenizer.peekToken()) != null) {
            if (token.level <= parentLevel) break;

            if (token.isListItem && "item".equals(token.key)) {
                token = tokenizer.nextToken(); // Потребляем токен '- item:'
                Object itemValue = readTypedNode(tokenizer, token.level, simpleTypeHelper);
                collection.add(itemValue);
            } else {
                tokenizer.nextToken();
            }
        }
        return collection;
    }

    /**
     * Читает блок array: тип компонента из 'class:' и элементы '- item'.
     * Восстанавливает массив через java.lang.reflect.Array.newInstance.
     *
     * @param tokenizer        the token stream positioned at the array's {@code class:} key
     * @param parentLevel      the tab level of the {@code array} block itself; deeper tokens
     *                         belong to the array
     * @param simpleTypeHelper the simple-type SPI used to restore scalar item values
     * @return the restored array (via {@code java.lang.reflect.Array.newInstance}), or
     *         {@code null} when the {@code class:} key is absent
     * @throws Exception when the component type cannot be resolved or an item value cannot
     *         be materialized
     */
    public static Object readArray(final XdStorageYamlTokenizer tokenizer, final int parentLevel,
                                   final IXdStorageSimpleTypeHelper simpleTypeHelper) throws Exception {
        YamlLineToken classToken = tokenizer.nextToken();
        if (classToken == null || !"class".equals(classToken.key)) {
            return null;
        }

        // PROPTEST (docs/tasks/2.2.0/03): the writer stores the array's component type as
        // the RAW name — for a primitive-typed array that is 'int'/'long'/..., which
        // resolveOrThrow cannot resolve (ClassNotFoundException: int → the resource
        // unreadable after the reopen). Resolve the primitive Class object directly.
        final Class<?> component;
        final Class<?> primitive = primitiveClassOrNull(classToken.value);
        if (primitive != null) {
            component = primitive;
        } else {
            component = XdStorageClassMetadataRegistry.resolveOrThrow(normalizePrimitiveName(classToken.value));
        }
        final List<Object> items = new ArrayList<>();

        YamlLineToken token;
        while ((token = tokenizer.peekToken()) != null) {
            if (token.level <= parentLevel) break;

            if (token.isListItem && "item".equals(token.key)) {
                token = tokenizer.nextToken(); // Потребляем '- item:'
                items.add(readTypedNode(tokenizer, token.level, simpleTypeHelper));
            } else {
                tokenizer.nextToken();
            }
        }

        final Object array = java.lang.reflect.Array.newInstance(component, items.size());
        for (int i = 0; i < items.size(); ++i) {
            java.lang.reflect.Array.set(array, i, items.get(i));
        }
        return array;
    }

    /**
     * Reads a {@code map} block: instantiates the stored map class (falling back to
     * {@code HashMap} for non-instantiable wrappers) and restores each {@code - entry:}
     * cell's {@code key:}/{@code value:} pair as a typed value. Entries whose restored key
     * is {@code null} are skipped.
     *
     * @param tokenizer        the token stream positioned at the map's {@code class:} key
     * @param simpleTypeHelper the simple-type SPI used to restore scalar key/value values
     * @param parentLevel      the tab level of the {@code map} block itself; deeper tokens
     *                         belong to the map
     * @return the restored map, or {@code null} when the {@code class:} key is absent
     * @throws Exception when the map class cannot be resolved or instantiated, or a
     *         key/value cannot be materialized
     */
    @SuppressWarnings("unchecked")
    public static Object readMap(final XdStorageYamlTokenizer tokenizer, IXdStorageSimpleTypeHelper simpleTypeHelper,
                                 final int parentLevel) throws Exception {
        YamlLineToken classToken = tokenizer.nextToken();
        if (classToken == null || !"class".equals(classToken.key)) {
            return null;
        }

        Class<?> cl = XdStorageClassMetadataRegistry.resolveOrThrow(classToken.value);
        Map<Object, Object> map;
        try {
            map = (Map<Object, Object>) cl.newInstance();
        } catch (final Exception e) {
            // Неинстанцируемый класс карты (обертка Collections$SynchronizedMap и т.п.)
            map = new java.util.HashMap<>();
        }

        YamlLineToken token;
        while ((token = tokenizer.peekToken()) != null) {
            if (token.level <= parentLevel) break;

            if (token.isListItem && "entry".equals(token.key)) {
                token = tokenizer.nextToken(); // Потребляем токен '- entry:'

                int entryLevel = token.level;
                Object keyObj = null;
                Object valObj = null;

                YamlLineToken sub;
                while ((sub = tokenizer.peekToken()) != null) {
                    if (sub.level <= entryLevel) break;

                    if ("key".equals(sub.key)) {
                        tokenizer.nextToken(); // Потребляем 'key:'
                        keyObj = readTypedNode(tokenizer, sub.level, simpleTypeHelper);
                    } else if ("value".equals(sub.key)) {
                        tokenizer.nextToken(); // Потребляем 'value:'
                        valObj = readTypedNode(tokenizer, sub.level, simpleTypeHelper);
                    } else {
                        tokenizer.nextToken();
                    }
                }

                if (keyObj != null) {
                    map.put(keyObj, valObj);
                }
            } else {
                tokenizer.nextToken();
            }
        }
        return map;
    }

    private static Object readTypedNode(final XdStorageYamlTokenizer tokenizer, final int parentLevel,
                                        final IXdStorageSimpleTypeHelper simpleTypeHelper) throws Exception {
        String typeName = null;
        String rawScalar = null;
        YamlLineToken complexBlock = null;

        YamlLineToken token;
        while ((token = tokenizer.peekToken()) != null) {
            if (token.level <= parentLevel) break;

            // Ровно один честный вызов nextToken на итерацию цикла свойств!
            token = tokenizer.nextToken();

            if ("type".equals(token.key)) {
                typeName = token.value;
            } else if ("value".equals(token.key)) {
                if (token.value != null) {
                    // Случай А: Значение лежит на той же строчке (скаляр, например value: null)
                    rawScalar = token.value;
                } else {
                    // Случай Б: Значение разворачивается на следующей строке (сложный блок)
                    YamlLineToken next = tokenizer.peekToken();
                    if (next != null && next.level > token.level) {
                        complexBlock = next;
                        // =========================================================================
                        // СНАЙПЕРСКИЙ БАРЬЕР: Немедленно прерываем цикл сбора свойств элемента списка!
                        // Оставляем каретку токенизатора нетронутой прямо перед сложным блоком!
                        // =========================================================================
                        break;
                    }
                }
            }
        }

        // Выполняем проверку строго ПОСЛЕ того, как цикл полностью освободил поток
        if ("null".equals(typeName)) {
            return null;
        }

        // YAMLSTR (docs/tasks/2.2.0/40): the null CELLS returned above (their type is 'null'); a
        // token value equal to "null" below is the four-character string — dropping it here made
        // a list element and a map value vanish, and a map KEY take its whole entry with it
        if (rawScalar != null) {
            if (typeName != null) {
                Class<?> targetType = XdStorageClassMetadataRegistry.resolveOrThrow(normalizePrimitiveName(typeName));
                if (targetType == Object.class) targetType = String.class;
                return simpleTypeHelper.simpleTypeFromString(targetType, rawScalar);
            }
            return rawScalar;
        }

        if (complexBlock != null) {
            if ("enum".equals(complexBlock.key)) {
                tokenizer.nextToken(); // Потребляем 'enum:' перед входом
                return readEnum(tokenizer);
            } else if ("object".equals(complexBlock.key)) {
                tokenizer.nextToken(); // Потребляем 'object:' перед входом
                return XdStorageYamlBlockObjectsReader.readObject(tokenizer, complexBlock.level, simpleTypeHelper);
            } else if ("reference".equals(complexBlock.key)) {
                tokenizer.nextToken(); // Честно потребляем 'reference:' перед входом — иначе
                // readReference видит блок-токен с level == parentLevel и мгновенно выходит с null!
                return XdStorageYamlBlockObjectsReader.readReference(tokenizer, complexBlock.level, null,
                        simpleTypeHelper);
            } else if ("array".equals(complexBlock.key)) {
                tokenizer.nextToken(); // Честно потребляем 'array:' перед входом
                return readArray(tokenizer, complexBlock.level, simpleTypeHelper);
            } else if ("collection".equals(complexBlock.key)) {
                // PROPTEST (docs/tasks/2.2.0/03): a NESTED container inside a value node —
                // the writer now emits collection/map blocks for the container items (the
                // write side recurses), so the read side recurses too; without these branches
                // a nested container read as null (a silent loss).
                tokenizer.nextToken(); // Потребляем 'collection:' перед входом
                return readCollection(tokenizer, complexBlock.level, simpleTypeHelper);
            } else if ("map".equals(complexBlock.key)) {
                tokenizer.nextToken(); // Потребляем 'map:' перед входом
                return readMap(tokenizer, simpleTypeHelper, complexBlock.level);
            }
        }

        return null;
    }

    /**
     * Reads a collection cell inside a typed data record; an alias of
     * {@link #readCollection} kept for symmetry with the record-oriented API.
     *
     * @param tokenizer        the token stream positioned at the collection's {@code class:} key
     * @param parentLevel      the tab level of the {@code collection} block itself
     * @param simpleTypeHelper the simple-type SPI used to restore scalar item values
     * @return the restored collection, or {@code null} when the {@code class:} key is absent
     * @throws Exception when the collection class cannot be resolved or instantiated, or an
     *         item value cannot be materialized
     */
    @SuppressWarnings("unchecked")
    public static Object readObjectDataCollection(final XdStorageYamlTokenizer tokenizer, final int parentLevel, final
            IXdStorageSimpleTypeHelper simpleTypeHelper) throws Exception {
        return readCollection(tokenizer, parentLevel, simpleTypeHelper);
    }

    /**
     * Reads a map cell inside a typed data record; an alias of {@link #readMap} kept for
     * symmetry with the record-oriented API.
     *
     * @param tokenizer        the token stream positioned at the map's {@code class:} key
     * @param simpleTypeHelper the simple-type SPI used to restore scalar key/value values
     * @param parentLevel      the tab level of the {@code map} block itself
     * @return the restored map, or {@code null} when the {@code class:} key is absent
     * @throws Exception when the map class cannot be resolved or instantiated, or a
     *         key/value cannot be materialized
     */
    @SuppressWarnings("unchecked")
    public static Object readObjectDataMap(final XdStorageYamlTokenizer tokenizer, IXdStorageSimpleTypeHelper
            simpleTypeHelper,
                                           final int parentLevel) throws Exception {
        return readMap(tokenizer, simpleTypeHelper, parentLevel);
    }

    /** The primitive Class for a primitive type name, or null for anything else. */
    private static Class<?> primitiveClassOrNull(final String name) {
        if ("int".equals(name)) return int.class;
        if ("long".equals(name)) return long.class;
        if ("boolean".equals(name)) return boolean.class;
        if ("double".equals(name)) return double.class;
        if ("float".equals(name)) return float.class;
        if ("short".equals(name)) return short.class;
        if ("byte".equals(name)) return byte.class;
        if ("char".equals(name)) return char.class;
        return null;
    }

    private static String normalizePrimitiveName(String name) {
        if ("int".equals(name)) return "java.lang.Integer";
        if ("long".equals(name)) return "java.lang.Long";
        if ("boolean".equals(name)) return "java.lang.Boolean";
        if ("byte".equals(name)) return "java.lang.Byte";
        if ("short".equals(name)) return "java.lang.Short";
        if ("float".equals(name)) return "java.lang.Float";
        if ("double".equals(name)) return "java.lang.Double";
        if ("char".equals(name)) return "java.lang.Character";
        return name;
    }
}
