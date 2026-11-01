package io.github.xdstorage.serialization.yaml;

import io.github.xdstorage.XdStoragePolicy;
import io.github.xdstorage.exceptions.XdStorageException;
import io.github.xdstorage.helpers.IXdStorageSimpleTypeHelper;
import io.github.xdstorage.idgeneration.IXdStorageIdGenerator;
import io.github.xdstorage.idgeneration.XdStorageIdGeneratorType;
import io.github.xdstorage.observing.XdStorageObserverService;
import io.github.xdstorage.services.XdStorageServicesLocator;
import io.github.xdstorage.utils.XdStorageClassInfo;
import io.github.xdstorage.utils.XdStorageObjectField;
import io.github.xdstorage.utils.XdStorageObjectIdField;
import io.github.xdstorage.utils.XdStorageObjectUtils;

import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Isolated reflective marshaller of JavaBeans entities and ORM references: emits every
 * persisted field as a totally typed {@code name}/{@code type}/{@code value} structure
 * (the exact inverse of {@link XdStorageYamlBlockObjectsReader}). It is also the
 * journal's body encoder — commit records embed full object bodies produced by
 * {@link #writeObjectData}. Nested values follow the storage policy: embedded classes
 * are written inline, independent ones as id-only {@code reference} blocks.
 *
 * <p>Thread-safety: all methods are static; the {@code propertiesCache} is a
 * {@code ConcurrentHashMap} and safe under concurrent population. Engine-internal —
 * not part of the public API.</p>
 */
public class XdStorageYamlBlockObjectsWriter {

    private static final Map<Class<?>, Collection<XdStorageObjectField>> propertiesCache = new ConcurrentHashMap<>();

    /**
     * Utility class — no instances.
     */
    public XdStorageYamlBlockObjectsWriter() {
    }

    /**
     * Writes one object body: the {@code class} key plus a {@code fields} block with one
     * typed {@code - field:} entry per non-null persisted property. Engine-managed id
     * fields still holding {@code null} are assigned a generated id while being written.
     * {@code null}-valued properties are skipped entirely.
     *
     * @param object           the entity instance to persist (never {@code null})
     * @param emitter          the YAML output target
     * @param services         the storage services locator (used by id generation)
     * @param simpleTypeHelper the simple-type SPI used to render scalar field values
     * @param idGenerator      the engine id generator applied to engine-managed id fields
     * @throws IOException        when the underlying writer fails
     * @throws XdStorageException when id generation fails
     */
    public static void writeObjectData(final Object object, final XdStorageYamlEmitter emitter,
                                       final XdStorageServicesLocator services,
                                       final IXdStorageSimpleTypeHelper simpleTypeHelper,
                                       final IXdStorageIdGenerator idGenerator) throws IOException, XdStorageException {
        final Class<?> cl = XdStorageObjectUtils.getEntityClass(object.getClass());
        final XdStorageClassInfo clInfo = XdStorageObjectUtils.getClassInfo(cl);

        Collection<XdStorageObjectField> props = propertiesCache.get(cl);
        if (props == null) {
            propertiesCache.put(cl, props = clInfo.getFields().values());
        }

        emitter.writeKey("class");
        emitter.writeScalar("'" + cl.getName() + "'");

        // Открываем единый, строго структурированный узел для всех полей JavaBeans класса
        emitter.openBlock("fields");

        for (final XdStorageObjectField property : props) {
            if (property.isIdField()) {
                final XdStorageObjectIdField idField = clInfo.getIdField();
                if ((idField.getIdGeneratorType() == XdStorageIdGeneratorType.DATABASE_GENERATOR
                        || idField.getIdGeneratorType() == XdStorageIdGeneratorType.CUSTOM_GENERATOR)
                        && idField.get(object) == null) {
                    idField.set(XdStorageObserverService.getObservableWrapper(object), idGenerator.generate(cl, services.getStorage(), null));
                }
            }

            final Object value = property.get(object);
            if (value == null) {
                continue; // Полный пропуск null-свойств согласно инвариантам СУБД
            }

            final Class<?> fieldType = property.getFieldInfo().getClazz();
            final Class<?> c = value.getClass();

            // Открываем элемент списка мета-модели полей
            emitter.openBlock("- field");
            emitter.writeKey("name");
            emitter.writeScalar("'" + property.getName() + "'");
            emitter.writeKey("type");
            emitter.writeScalar("'" + fieldType.getName() + "'");

            // Запись контента уходит строго внутрь узла value:
            if (simpleTypeHelper.isSimpleType(c, value)) {
                emitter.writeKey("value");
                emitter.writeScalar("'" + encode(simpleTypeHelper.simpleTypeToString(value)) + "'");
            } else if (c.isEnum()) {
                emitter.openBlock("value");
                XdStorageYamlBlockContainersWriter.writeEnumInline(c, value, emitter);
                emitter.closeBlock();
            } else if (value instanceof Collection<?>) {
                emitter.openBlock("value");
                XdStorageYamlBlockContainersWriter.writeCollectionInline(c, (Collection<?>) value, emitter, services, simpleTypeHelper, idGenerator);
                emitter.closeBlock();
            } else if (value instanceof Map<?, ?>) {
                emitter.openBlock("value");
                XdStorageYamlBlockContainersWriter.writeMapInline(c, (Map<?, ?>) value, emitter, services, simpleTypeHelper, idGenerator);
                emitter.closeBlock();
            } else if (c.isArray()) {
                emitter.openBlock("value");
                XdStorageYamlBlockContainersWriter.writeArrayInline(c, value, emitter, services, simpleTypeHelper, idGenerator);
                emitter.closeBlock();
            } else {
                emitter.openBlock("value");
                writeSingleObjectOrReferenceInline(c, value, emitter, services, simpleTypeHelper, idGenerator);
                emitter.closeBlock();
            }

            emitter.closeBlock(); // Закрываем "- field"
        }

        emitter.closeBlock(); // Закрываем "fields"
    }

    /**
     * Writes one property value that is neither a simple type, enum, collection, map nor
     * array: an embedded class ({@code StoreWithParentObject} policy or no policy) is
     * written as a full inline {@code object} block, an independent one as an id-only
     * {@code reference} block. Composite (non-scalar) ids are written as a nested object
     * body so the read side can restore them exactly.
     *
     * @param cl               the declared runtime class of the value
     * @param value            the value to persist (never {@code null})
     * @param emitter          the YAML output target
     * @param services         the storage services locator (used by id generation)
     * @param simpleTypeHelper the simple-type SPI used to render scalar id values
     * @param idGenerator      the engine id generator applied to engine-managed id fields
     * @throws IOException        when the underlying writer fails
     * @throws XdStorageException when id generation fails
     */
    public static void writeSingleObjectOrReferenceInline(final Class<?> cl, final Object value,
                                                          final XdStorageYamlEmitter emitter,
                                                          final XdStorageServicesLocator services,
                                                          final IXdStorageSimpleTypeHelper simpleTypeHelper,
                                                          final IXdStorageIdGenerator idGenerator) throws IOException, XdStorageException {
        final Class<?> entityClass = XdStorageObjectUtils.getEntityClass(cl);
        final XdStorageClassInfo targetClInfo = XdStorageObjectUtils.getClassInfo(entityClass);
        final XdStoragePolicy policy = targetClInfo.getPolicy();

        if (policy == null || policy == XdStoragePolicy.StoreWithParentObject) {
            emitter.openBlock("object");
            writeObjectData(value, emitter, services, simpleTypeHelper, idGenerator);
            emitter.closeBlock();
        } else {
            final XdStorageObjectIdField targetIdField = targetClInfo.getIdField();
            emitter.openBlock("reference");
            emitter.writeKey("class");
            emitter.writeScalar("'" + entityClass.getName() + "'");
            Object targetId = targetIdField.get(value);
            if (targetId != null) {
                final Class<?> idClass = XdStorageObjectUtils.getEntityClass(targetId.getClass());
                if (idClass == Class.class) {
                    emitter.writeKey("objectId");
                    emitter.writeScalar("'" + ((Class<?>) targetId).getName() + "'");
                } else if (XdStorageObjectUtils.isSimpleType(idClass, null)) {
                    emitter.writeKey("objectId");
                    emitter.writeScalar("'" + targetId + "'");
                } else {
                    // Составной идентификатор пишется вложенным объектом — симметрично readReference;
                    // openBlock, а не writeKey: ключ блочного значения обязан занимать свою строку
                    emitter.openBlock("objectId");
                    emitter.openBlock("object");
                    writeObjectData(targetId, emitter, services, simpleTypeHelper, idGenerator);
                    emitter.closeBlock();
                    emitter.closeBlock();
                }
            }
            emitter.closeBlock();
        }
    }

    /**
     * Writes a pure transactional reference body: the referenced entity's {@code class}
     * plus its id. A {@code java.lang.Class} id is written as the class-name scalar (as in
     * the XML format), a simple-type id as its string scalar, and a composite id as a
     * nested object body — mirrored by the read side of {@link XdStorageYamlBlockObjectsReader#readReference}.
     *
     * @param reference        the referencing object whose id field is read
     * @param field            the id field accessor of the reference
     * @param emitter          the YAML output target
     * @param services         the storage services locator (used by nested id generation)
     * @param simpleTypeHelper the simple-type SPI used to render scalar id values
     * @param idGenerator      the engine id generator applied to nested engine-managed id fields
     * @throws IOException        when the underlying writer fails
     * @throws XdStorageException when id generation fails
     */
    public static void writeReferenceData(final Object reference, final XdStorageObjectIdField field, final XdStorageYamlEmitter emitter,
                                          final XdStorageServicesLocator services,
                                          final IXdStorageSimpleTypeHelper simpleTypeHelper,
                                          final IXdStorageIdGenerator idGenerator) throws IOException, XdStorageException {
        final Object value = field.get(reference);
        emitter.writeKey("class");
        emitter.writeScalar("'" + XdStorageObjectUtils.getEntityClass(reference.getClass()).getName() + "'");
        final Class<?> valueClass = XdStorageObjectUtils.getEntityClass(value.getClass());
        if (valueClass == Class.class) {
            // Идентификатор — java.lang.Class: как в XML-формате пишется именем класса скаляром
            emitter.writeKey("objectId");
            emitter.writeScalar("'" + ((Class<?>) value).getName() + "'");
        } else if (XdStorageObjectUtils.isSimpleType(valueClass, null)) {
            emitter.writeKey("objectId");
            emitter.writeScalar("'" + value + "'");
        } else {
            // Составной идентификатор (например XdStorageBTreeId): скалярный toString() невосстановим,
            // пишется вложенным объектом и читается симметричной веткой readReference.
            // openBlock, а не writeKey: ключ блочного значения обязан занимать свою строку
            emitter.openBlock("objectId");
            emitter.openBlock("object");
            writeObjectData(value, emitter, services, simpleTypeHelper, idGenerator);
            emitter.closeBlock();
            emitter.closeBlock();
        }
    }

    /**
     * Encodes a scalar for safe storage between single quotes: each {@code '} becomes the
     * YAML escape pair {@code ''}. The inverse is {@link XdStorageYamlBlockObjectsReader#decode}.
     *
     * @param value the raw scalar (may be {@code null})
     * @return the quoted-form-safe scalar, or {@code null} when {@code value} is {@code null}
     */
    public static String encode(final String value) {
        if (value == null) return null;
        return value.replace("'", "''");
    }
}
