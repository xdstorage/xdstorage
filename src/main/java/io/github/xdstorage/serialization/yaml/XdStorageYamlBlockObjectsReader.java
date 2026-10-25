package io.github.xdstorage.serialization.yaml;

import io.github.xdstorage.utils.XdStorageClassMetadataRegistry;
import io.github.xdstorage.helpers.IXdStorageSimpleTypeHelper;
import io.github.xdstorage.object.XdStorageIdentifiableObject;
import io.github.xdstorage.utils.XdStorageClassInfo;
import io.github.xdstorage.utils.XdStorageObjectField;
import io.github.xdstorage.utils.XdStorageObjectIdField;
import io.github.xdstorage.utils.XdStorageObjectUtils;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Isolated reflective parser of JavaBeans entities and ORM references: assembles live
 * objects from the totally typed {@code name}/{@code type}/{@code value} field
 * metamodel written by {@link XdStorageYamlBlockObjectsWriter}, driving the tokenizer
 * with strict one-token look-ahead. Two record flavours are supported: live instances
 * ({@link #readObject}) and storage-neutral {@code XdStorageIdentifiableObject} data
 * records ({@link #readObjectData}).
 *
 * <p>Thread-safety: all methods are static; the {@code propertiesCache} is a
 * {@code ConcurrentHashMap} and safe under concurrent population. Engine-internal —
 * not part of the public API.</p>
 */
public class XdStorageYamlBlockObjectsReader {

    private static final Map<Class<?>, Map<String, XdStorageObjectField>> propertiesCache = new ConcurrentHashMap<>();

    /**
     * Utility class — no instances.
     */
    public XdStorageYamlBlockObjectsReader() {
    }

    /**
     * Reads an {@code object} block: resolves the stored class, instantiates it and fills
     * every {@code - field:} entry (scalars via the simple-type helper, complex values via
     * the container readers) into the matching JavaBeans property. Fields unknown to the
     * class are skipped.
     *
     * @param tokenizer        the token stream positioned at the object's {@code class:} key
     * @param parentLevel      the tab level of the {@code object} block itself; deeper tokens
     *                         belong to the object
     * @param simpleTypeHelper the simple-type SPI used to restore scalar field values
     * @return the restored live instance, or {@code null} when the {@code class:} key is absent
     * @throws Exception when the class cannot be resolved or instantiated, or a field value
     *         cannot be materialized
     */
    public static Object readObject(final XdStorageYamlTokenizer tokenizer, final int parentLevel, final IXdStorageSimpleTypeHelper simpleTypeHelper) throws Exception {
        YamlLineToken token = tokenizer.peekToken();
        if (token == null || !"class".equals(token.key)) {
            return null;
        }

        token = tokenizer.nextToken();
        final Class<?> cl = XdStorageClassMetadataRegistry.resolveOrThrow(token.value);
        final XdStorageClassInfo clInfo = XdStorageObjectUtils.getClassInfo(cl);

        Map<String, XdStorageObjectField> props = propertiesCache.get(cl);
        if (props == null) {
            propertiesCache.put(cl, props = clInfo.getFields());
        }

        final Object result = cl.newInstance();

        token = tokenizer.peekToken();
        if (token != null && "fields".equals(token.key)) {
            tokenizer.nextToken(); // Честно поглощаем 'fields:'

            int fieldsLevel = token.level;
            while ((token = tokenizer.peekToken()) != null) {
                if (token.level < fieldsLevel + 1) {
                    break;
                }

                if (token.isListItem && "field".equals(token.key)) {
                    token = tokenizer.nextToken(); // Честно поглощаем '- field:'
                    parseAndSetField(tokenizer, token.level, result, props, clInfo, simpleTypeHelper);
                } else {
                    tokenizer.nextToken();
                }
            }
        }
        return result;
    }

    private static void parseAndSetField(final XdStorageYamlTokenizer tokenizer, final int fieldLevel, final Object result,
                                         final Map<String, XdStorageObjectField> props, final XdStorageClassInfo clInfo,
                                         final IXdStorageSimpleTypeHelper simpleTypeHelper) throws Exception {
        String fieldName = null;
        String fieldTypeName = null;
        String rawScalarValue = null;
        YamlLineToken complexBlockToken = null;

        YamlLineToken token;
        while ((token = tokenizer.peekToken()) != null) {
            if (token.level <= fieldLevel) {
                break;
            }

            token = tokenizer.nextToken();

            if ("name".equals(token.key)) {
                fieldName = token.value;
            } else if ("type".equals(token.key)) {
                fieldTypeName = token.value;
            } else if ("value".equals(token.key)) {
                if (token.value != null) {
                    rawScalarValue = token.value;
                } else {
                    YamlLineToken next = tokenizer.peekToken();
                    if (next != null && next.level > token.level) {
                        complexBlockToken = next;
                        break;
                    }
                }
            }
        }

        if (fieldName == null) return;

        XdStorageObjectField property = props.get(fieldName);
        if (property == null) {
            for (Map.Entry<String, XdStorageObjectField> entry : props.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(fieldName)) {
                    property = entry.getValue();
                    break;
                }
            }
        }

        final XdStorageObjectIdField idField = clInfo.getIdField();
        Object finalValue = null;

        if (rawScalarValue != null && !"null".equals(rawScalarValue)) {
            if (fieldTypeName != null && !"null".equals(fieldTypeName)) {
                Class<?> targetType = XdStorageClassMetadataRegistry.resolveOrThrow(normalizePrimitiveName(fieldTypeName));
                if (targetType == Object.class) targetType = String.class;
                finalValue = simpleTypeHelper.simpleTypeFromString(targetType, rawScalarValue);
            } else {
                finalValue = rawScalarValue;
            }
        } else if (complexBlockToken != null) {
            if ("enum".equals(complexBlockToken.key)) {
                tokenizer.nextToken();
                finalValue = XdStorageYamlBlockContainersReader.readEnum(tokenizer);
            } else if ("collection".equals(complexBlockToken.key)) {
                tokenizer.nextToken();
                finalValue = XdStorageYamlBlockContainersReader.readCollection(tokenizer, fieldLevel, simpleTypeHelper);
            } else if ("map".equals(complexBlockToken.key)) {
                tokenizer.nextToken();
                // ФИКС СИГНАТУРЫ: Убран лишний параметр simpleTypeHelper!
                finalValue = XdStorageYamlBlockContainersReader.readMap(tokenizer, simpleTypeHelper, fieldLevel);
            } else if ("reference".equals(complexBlockToken.key)) {
                tokenizer.nextToken(); // Честно потребляем 'reference:' перед входом
                finalValue = readReference(tokenizer, complexBlockToken.level, null, simpleTypeHelper);
            } else if ("array".equals(complexBlockToken.key)) {
                tokenizer.nextToken(); // Честно потребляем 'array:' перед входом
                finalValue = XdStorageYamlBlockContainersReader.readArray(tokenizer, complexBlockToken.level, simpleTypeHelper);
            } else if ("object".equals(complexBlockToken.key)) {
                tokenizer.nextToken();
                finalValue = readObject(tokenizer, complexBlockToken.level, simpleTypeHelper);
            }
        }

        // YAMLREAD (docs/tasks/2.1.0/37): a stored null IS a legal value for a known
        // property — the field must reflect the stored state, not silently keep its
        // construction default. Unknown fields keep the pinned ignore-and-continue
        // forward-compat contract (XdStorageYamlBlockObjectsReaderTest); the id field
        // is not in the properties map and keeps its separate id-only branch.
        if (property != null) {
            property.set(result, finalValue);
        } else if (idField != null && fieldName.equals(idField.getName())) {
            if (finalValue != null) {
                idField.set(result, finalValue);
            }
        }
    }

    /**
     * Reads a {@code reference} block: restores a stub instance of the referenced class
     * with only its id populated. The id may be a scalar ({@code objectId:}/{@code
     * dataStorageId:} key), a {@code java.lang.Class} token, or a composite id written as
     * a nested object body — all three forms mirror
     * {@code XdStorageYamlBlockObjectsWriter.writeReferenceData}.
     *
     * @param tokenizer        the token stream positioned inside the reference block
     * @param parentLevel      the tab level of the {@code reference} block itself; deeper
     *                         tokens belong to it
     * @param knownField       the id field to use when it is already known from the caller
     *                         (e.g. batch references reading); {@code null} to resolve it from
     *                         the entity class metadata
     * @param simpleTypeHelper the simple-type SPI used to materialize the scalar id value
     * @return the restored id-carrying stub instance, or {@code null} when the
     *         {@code class:} key or the id value is absent
     * @throws Exception when the entity class cannot be resolved or instantiated, or the id
     *         value cannot be materialized
     */
    public static Object readReference(final XdStorageYamlTokenizer tokenizer, final int parentLevel, final XdStorageObjectIdField knownField, final IXdStorageSimpleTypeHelper simpleTypeHelper) throws Exception {
        String className = null;
        String idValue = null;
        Object idObject = null;

        YamlLineToken token;
        while ((token = tokenizer.peekToken()) != null) {
            if (token.level <= parentLevel) break;
            token = tokenizer.nextToken();
            if ("class".equals(token.key)) {
                className = token.value;
            } else if ("objectId".equals(token.key) || "dataStorageId".equals(token.key)) {
                idValue = token.value;
                if (idValue == null) {
                    // Составной идентификатор (например XdStorageBTreeId) пишется вложенным объектом —
                    // симметрично writeReferenceData; скаляром такое значение невосстановимо
                    YamlLineToken next = tokenizer.peekToken();
                    if (next != null && next.level > token.level && "object".equals(next.key)) {
                        tokenizer.nextToken();
                        idObject = readObject(tokenizer, next.level, simpleTypeHelper);
                    }
                }
            }
        }

        // YAMLREAD: a reference block without a class is corruption, not "no reference".
        if (className == null) {
            throw new io.github.xdstorage.exceptions.XdStorageIOException("a reference block without a class key cannot be resolved (corrupt block)");
        }

        final Class<?> entityClass = XdStorageClassMetadataRegistry.resolveOrThrow(className);
        final Object result = entityClass.newInstance();

        final XdStorageClassInfo clInfo = XdStorageObjectUtils.getClassInfo(entityClass);
        final XdStorageObjectIdField idField = (knownField != null) ? knownField : clInfo.getIdField();

        Object objectId;
        if (idObject != null) {
            objectId = idObject;
        } else {
            // YAMLREAD: a reference block whose id value is absent is corruption —
            // returning null made it indistinguishable from "no reference".
            if (idValue == null) {
                throw new io.github.xdstorage.exceptions.XdStorageIOException("a reference block without an id value cannot be resolved (corrupt block)");
            }
            Class<?> idClass = idField.getFieldInfo().getClazz();
            if (idClass == String.class) {
                objectId = idValue;
            } else {
                objectId = simpleTypeHelper.simpleTypeFromString(normalizePrimitive(idClass), idValue);
            }
        }

        idField.set(result, objectId);
        return result;
    }

    /**
     * Reads an {@code object} block of a data resource into the engine's storage-neutral
     * record form: an {@code XdStorageIdentifiableObject} carrying the entity class, the
     * id and the raw field values — the entity class itself is never instantiated.
     *
     * @param tokenizer        the token stream positioned at the object's {@code class:} key
     *                         (consumed first)
     * @param parentLevel      the tab level of the {@code object} block itself
     * @param simpleTypeHelper the simple-type SPI used to restore scalar field values
     * @return the restored typed data record
     * @throws Exception when the class cannot be resolved, or a field value cannot be
     *         materialized
     */
    public static Object readObjectData(final XdStorageYamlTokenizer tokenizer, final int parentLevel, final IXdStorageSimpleTypeHelper simpleTypeHelper) throws Exception {
        YamlLineToken token = tokenizer.nextToken();
        final Class<?> cl = XdStorageClassMetadataRegistry.resolveOrThrow(token.value);
        final XdStorageClassInfo clInfo = XdStorageObjectUtils.getClassInfo(cl);
        final XdStorageObjectIdField idField = clInfo.getIdField();

        final XdStorageIdentifiableObject result = new XdStorageIdentifiableObject();
        result.setType(cl);

        Map<String, XdStorageObjectField> props = propertiesCache.get(cl);
        if (props == null) {
            propertiesCache.put(cl, props = clInfo.getFields());
        }

        token = tokenizer.peekToken();
        if (token != null && "fields".equals(token.key)) {
            tokenizer.nextToken();
            int fieldsLevel = token.level;
            while ((token = tokenizer.peekToken()) != null) {
                if (token.level < fieldsLevel + 1) break;

                if (token.isListItem && "field".equals(token.key)) {
                    token = tokenizer.nextToken();

                    String fName = null;
                    String fTypeName = null;
                    String rawScalar = null;
                    YamlLineToken complexBlock = null;

                    YamlLineToken subToken;
                    while ((subToken = tokenizer.peekToken()) != null) {
                        if (subToken.level <= token.level) break;

                        subToken = tokenizer.nextToken();
                        if ("name".equals(subToken.key)) {
                            fName = subToken.value;
                        } else if ("type".equals(subToken.key)) {
                            fTypeName = subToken.value;
                        } else if ("value".equals(subToken.key)) {
                            if (subToken.value != null) {
                                rawScalar = subToken.value;
                            } else {
                                YamlLineToken next = tokenizer.peekToken();
                                if (next != null && next.level > subToken.level) {
                                    complexBlock = next;
                                    break;
                                }
                            }
                        }
                    }

                    Object finalValue = null;
                    if (rawScalar != null && !"null".equals(rawScalar)) {
                        if (fTypeName != null) {
                            Class<?> targetType = XdStorageClassMetadataRegistry.resolveOrThrow(normalizePrimitiveName(fTypeName));
                            if (targetType == Object.class) targetType = String.class;
                            finalValue = simpleTypeHelper.simpleTypeFromString(targetType, rawScalar);
                        } else {
                            finalValue = rawScalar;
                        }
                    } else if (complexBlock != null) {
                        if ("enum".equals(complexBlock.key)) {
                            tokenizer.nextToken();
                            finalValue = XdStorageYamlBlockContainersReader.readEnum(tokenizer);
                        } else if ("collection".equals(complexBlock.key)) {
                            tokenizer.nextToken();
                            finalValue = XdStorageYamlBlockContainersReader.readCollection(tokenizer, token.level, simpleTypeHelper);
                        } else if ("map".equals(complexBlock.key)) {
                            tokenizer.nextToken();
// ФИКС СИГНАТУРЫ МЕТАДАННЫХ: Убран лишний параметр simpleTypeHelper!
                            finalValue = XdStorageYamlBlockContainersReader.readMap(tokenizer, simpleTypeHelper, token.level);
                        } else if ("reference".equals(complexBlock.key)) {
                            tokenizer.nextToken(); // Честно потребляем 'reference:' перед входом
                            finalValue = readReference(tokenizer, complexBlock.level, null, simpleTypeHelper);
                        } else if ("array".equals(complexBlock.key)) {
                            tokenizer.nextToken(); // Честно потребляем 'array:' перед входом
                            finalValue = XdStorageYamlBlockContainersReader.readArray(tokenizer, complexBlock.level, simpleTypeHelper);
                        } else if ("object".equals(complexBlock.key)) {
                            tokenizer.nextToken();
                            finalValue = readObject(tokenizer, complexBlock.level, simpleTypeHelper);
                        }
                    }

                    if (fName != null) {
                        if (idField != null && fName.equals(idField.getName())) {
                            result.setId(finalValue);
                        } else {
                            result.setProperty(fName, finalValue);
                        }
                    }
                } else {
                    tokenizer.nextToken();
                }
            }
        }
        return result;
    }

    /**
     * Reads a {@code reference} block of a data resource into the engine's record form: a
     * typed stub {@code XdStorageIdentifiableObject} with only class and id populated. The
     * id field is resolved from the entity class metadata.
     *
     * @param tokenizer   the token stream positioned inside the reference block
     * @param parentLevel the tab level of the {@code reference} block itself
     * @return the restored typed data record with class and id
     * @throws Exception when the entity class cannot be resolved or the id value cannot be
     *         materialized
     */
    public static Object readObjectDataReference(final XdStorageYamlTokenizer tokenizer, final int parentLevel) throws Exception {
        return readReference(tokenizer, parentLevel, null, new io.github.xdstorage.helpers.XdStorageDefaultSimpleTypeHelper());
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

    private static Class<?> normalizePrimitive(Class<?> type) {
        if (!type.isPrimitive()) return type;
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == boolean.class) return Boolean.class;
        if (type == byte.class) return Byte.class;
        if (type == short.class) return Short.class;
        if (type == float.class) return Float.class;
        if (type == double.class) return Double.class;
        if (type == char.class) return Character.class;
        return type;
    }

    /**
     * Restores the canonical Java string from its on-disk scalar form: the YAML
     * single-quote escape pair {@code ''} is folded back into a single {@code '}.
     *
     * @param value the raw scalar read from the file (may be {@code null})
     * @return the decoded string, or {@code null} when {@code value} is {@code null}
     */
    public static String decode(final String value) {
        if (value == null) return null;
        // Восстанавливаем канонические кавычки Java, превращая пары '' обратно в одинарные '
        return value.replace("''", "'");
    }
}