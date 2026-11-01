package io.github.xdstorage.serialization;

import org.slf4j.LoggerFactory;
import org.slf4j.Logger;

import io.github.xdstorage.XdStoragePolicy;
import io.github.xdstorage.exceptions.XdStorageException;
import io.github.xdstorage.exceptions.XdStorageIOException;
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
import java.io.Writer;
import java.lang.reflect.Array;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The XML implementation of {@link IXdStorageObjectsWriter}: walks the object graph of
 * every given aggregate and streams it as indented XML (nested {@code object}, {@code
 * array}, {@code primitive}, {@code enum}, {@code collection}, {@code map} elements and
 * id-only {@code reference} entries per the storage policy). It is also the single id
 * generation gate of the write path: engine-managed id fields still holding
 * {@code null} are assigned a generated id while being marshalled.
 *
 * <p>Thread-safety: instances are confined to one worker thread (handed out per thread
 * by {@link XdStorageDefaultIOFactory}); the {@code properties} reflection cache is a
 * {@code ConcurrentHashMap} and safe under concurrent population. Engine-internal —
 * not part of the public API.</p>
 */
public class XdStorageDefaultObjectsWriter implements IXdStorageObjectsWriter {

    private static final Logger log = LoggerFactory.getLogger(XdStorageDefaultObjectsWriter.class);

    private final XdStorageServicesLocator services;

    private final IXdStorageSimpleTypeHelper simpleTypeHelper;

    private final IXdStorageIdGenerator idGenerator;

    private final Map<Class<?>, Collection<XdStorageObjectField>> properties = new ConcurrentHashMap<>();

    /**
     * Creates the XML writer.
     *
     * @param services         the storage services locator (used by id generation)
     * @param simpleTypeHelper the simple-type SPI used to render scalar attribute values
     * @param idGenerator      the engine id generator applied to engine-managed id fields
     */
    public XdStorageDefaultObjectsWriter(final XdStorageServicesLocator services, final IXdStorageSimpleTypeHelper simpleTypeHelper, final IXdStorageIdGenerator idGenerator) {
        this.services = services;
        this.simpleTypeHelper = simpleTypeHelper;
        this.idGenerator = idGenerator;
    }

    /**
     * Класс для атрибута class="/имени типа при записи: у обернутых инстансов
     * (Observable/Simple/Unmodifiable wrapper'ы сгенерированных классов) пишем
     * ИМЯ СУЩНОСТИ, а не сгенерированного класса — иначе перечитание ловит
     * ClassNotFoundException по классам, которых нет в компилируемом classpath.
     */
    private static Class<?> entityClassOf(final Object value) {
        return XdStorageObjectUtils.getEntityClass(value.getClass());
    }

    @Override
    public void writeReferences(final Writer writer, final XdStorageObjectIdField field, final Collection<Object> references) throws XdStorageIOException {
        try {
            writer.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
            writeNewLevelAndTabulations(writer, 0);
            writer.write("<references>");
            for (final Object reference : references) {
                writeReference(reference, writer, field, 1);
            }
            writeNewLevelAndTabulations(writer, 0);
            writer.write("</references>");
        } catch (final Throwable cause) {
            throw new XdStorageIOException(cause);
        }
    }

    @Override
    public void writeObjects(final Writer writer, final Collection<Object> objects) throws XdStorageIOException {
        try {
            writer.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
            writeNewLevelAndTabulations(writer, 0);
            writer.write("<objects>");
            for (final Object object : objects) {
                writeObject(object, writer, 1);
            }
            writeNewLevelAndTabulations(writer, 0);
            writer.write("</objects>");
        } catch (final Throwable cause) {
            throw new XdStorageIOException(cause);
        }
    }

    private void writeObject(final Object object, final Writer writer, int level) throws IOException {
        final Class<?> cl = XdStorageObjectUtils.getEntityClass(object.getClass());
        final XdStorageClassInfo clInfo = XdStorageObjectUtils.getClassInfo(cl);
        final XdStoragePolicy policy = clInfo.getPolicy();
        writeObject(cl, policy, object, writer, level);
    }

    private void writeReference(final Object object, final Writer writer, final XdStorageObjectIdField field, final int level) throws IOException {
        writeNewLevelAndTabulations(writer, level);
        writer.append("<reference class=\"");
        writer.append(XdStorageObjectUtils.getEntityClass(object.getClass()).getName());
        writer.append("\" classObjectId=\"");
        writer.append(field.get(object).getClass().getName());
        final Object value = field.get(object);
        final Class<?> cl = entityClassOf(value);
        if (cl == Class.class) {
            writer.append("\" objectId=\"");
            writer.append(((Class)value).getName());
            writer.append("\"/>");
        } else if (XdStorageObjectUtils.isSimpleType(cl, null)) {
            writer.append("\" objectId=\"");
            writer.append(value.toString());
            writer.append("\"/>");
        } else {
            writer.append("\">");
            writeObject(field.getName(), cl, value, writer, level + 1);
            writeNewLevelAndTabulations(writer, level);
            writer.append("</reference>");
        }
    }

    @SuppressWarnings("unchecked")
    private void writeObject(final Class<?> cl, final XdStoragePolicy policy, final Object object, final Writer writer, int level) throws IOException {
        Collection<XdStorageObjectField> props = properties.get(cl);
        final XdStorageClassInfo clInfo = XdStorageObjectUtils.getClassInfo(cl);
        if (props == null) {
            properties.put(cl, props = clInfo.getFields().values());
        }
        writeNewLevelAndTabulations(writer, level);
        writer.append("<object class=\"");
        writer.append(XdStorageObjectUtils.getEntityClass(object.getClass()).getName());
        writer.append("\">");

        for (final XdStorageObjectField property : props) {
            if (property.isIdField()) {
                assignGeneratedIdIfNeeded(cl, object);
            }

            final Object value = property.get(object);
            if (value == null) {
                writeNull(property.getName(), writer, level + 1);
                continue;
            }

            final Class<?> c = entityClassOf(value);
            if (c.isArray()) {
                writeArray(property.getName(), c, value, writer, level + 1);
            } else if (simpleTypeHelper.isSimpleType(c, value)) {
                writeSimpleType(property.getName(), c, value, writer, level + 1);
            } else if (c.isEnum()) {
                writeEnumValue(property.getName(), c, value, writer, level + 1);
            } else if (value instanceof Collection<?>) {
                writeCollection(property.getName(), c, (Collection<?>) value, writer, level + 1);
            } else if (value instanceof Map<?, ?>) {
                writeMap(property.getName(), c, (Map<?, ?>) value, writer, level + 1);
            } else if (property.isParent()) {
                writeReference(property.getName(), value, writer, level + 1);
            } else if (XdStorageObjectUtils.getClassInfo(c).getIdField() != null) {
                writeObject(property.getName(), c, value, writer, level + 1);
            } else {
                writeObject(property.getName(), c, value, writer, level + 1);
            }
        }
        writeNewLevelAndTabulations(writer, level);
        writer.append("</object>");
    }

    private void writeNull(final String name, final Writer writer, int level) throws IOException {
        writeNewLevelAndTabulations(writer, level);
        writer.append("<object name=\"").append(name).append("\"/>");
    }

    private void writeNull(final Writer writer, int level) throws IOException {
        writeNewLevelAndTabulations(writer, level);
        writer.append("<object/>");
    }

    @SuppressWarnings("unchecked")
    private void writeArray(final String name, final Class<?> cl, final Object array, final Writer writer, int level) throws IOException {
        writeNewLevelAndTabulations(writer, level);
        writer.append("<array name=\"").append(name).append("\" class=\"").append(cl.getComponentType().getName()).append("\" length=\"")
                .append(String.valueOf(Array.getLength(array))).append("\">");

        final int length = Array.getLength(array);
        for (int i = 0; i < length; ++i) {
            final Object object = Array.get(array, i);
            final Class<?> c = object != null ? entityClassOf(object) : null;
            if (object == null) {
                writeNull(writer, level + 1);
            } else if (c.isArray()) {
                writeArray(c, object, writer, level + 1);
            } else if (simpleTypeHelper.isSimpleType(c, object)) {
                writeSimpleType(c, object, writer, level + 1);
            } else if (c.isEnum()) {
                writeEnumValue(c, object, writer, level + 1);
            } else if (object instanceof Collection<?>) {
                writeCollection(c, (Collection<?>) object, writer, level + 1);
            } else if (object instanceof Map<?, ?>) {
                writeMap(c, (Map<?, ?>) object, writer, level + 1);
            } else if (XdStorageObjectUtils.getClassInfo(c).getIdField() != null) {
                writeObject(c, object, writer, level + 1);
            } else {
                writeObject(c, object, writer, level + 1);
            }
        }
        writeNewLevelAndTabulations(writer, level);
        writer.append("</array>");
    }

    @SuppressWarnings("unchecked")
    private void writeArray(final Class<?> cl, final Object array, final Writer writer, int level) throws IOException {
        writeNewLevelAndTabulations(writer, level);
        writer.append("<array class=\"").append(cl.getComponentType().getName()).append("\" length=\"").append(String.valueOf(Array.getLength(array))).append("\">");

        final int length = Array.getLength(array);
        for (int i = 0; i < length; ++i) {
            final Object object = Array.get(array, i);
            final Class<?> c = object != null ? entityClassOf(object) : null;
            if (object == null) {
                writeNull(writer, level + 1);
            } else if (c.isArray()) {
                writeArray(c, object, writer, level + 1);
            } else if (simpleTypeHelper.isSimpleType(c, object)) {
                writeSimpleType(c, object, writer, level + 1);
            } else if (c.isEnum()) {
                writeEnumValue(c, object, writer, level + 1);
            } else if (object instanceof Collection<?>) {
                writeCollection(c, (Collection<?>) object, writer, level + 1);
            } else if (object instanceof Map<?, ?>) {
                writeMap(c, (Map<?, ?>) object, writer, level + 1);
            } else if (XdStorageObjectUtils.getClassInfo(c).getIdField() != null) {
                writeObject(c, object, writer, level + 1);
            } else {
                writeObject(c, object, writer, level + 1);
            }
        }
        writeNewLevelAndTabulations(writer, level);
        writer.append("</array>");
    }

    private void writeSimpleType(final String name, final Class<?> cl, final Object object, final Writer writer, int level) throws IOException {
        writeNewLevelAndTabulations(writer, level);
        writer.append("<object name=\"").append(name).append("\" class=\"").append(cl.getName()).append("\" value=\"")
                .append(encode(simpleTypeHelper.simpleTypeToString(object))).append("\"/>");
    }

    private void writeSimpleType(final Class<?> cl, final Object object, final Writer writer, int level) throws IOException {
        writeNewLevelAndTabulations(writer, level);
        writer.append("<object class=\"").append(cl.getName()).append("\" value=\"")
                .append(encode(simpleTypeHelper.simpleTypeToString(object))).append("\"/>");
    }

    private void writeEnumValue(final String name, final Class<?> cl, final Object object, final Writer writer, int level) throws IOException {
        writeNewLevelAndTabulations(writer, level);
        writer.append("<enum name=\"").append(name).append("\" class=\"").append(cl.getName()).append("\" value=\"").append(encode(object.toString())).append("\"/>");
    }

    private void writeEnumValue(final Class<?> cl, final Object object, final Writer writer, int level) throws IOException {
        writeNewLevelAndTabulations(writer, level);
        writer.append("<enum class=\"").append(cl.getName()).append("\" value=\"").append(encode(object.toString())).append("\"/>");
    }

    @SuppressWarnings("unchecked")
    private void writeCollection(final String name, final Class<?> cl, final Collection<?> collection, final Writer writer, int level) throws IOException {
        writeNewLevelAndTabulations(writer, level);
        writer.append("<collection name=\"").append(name).append("\" class=\"").append(cl.getName()).append("\">");
        // R-43: снимок под монитором коллекции — итерация по живой LinkedList/ArrayList
        // рвалась ConcurrentModificationException, пока другой поток мутирует коллекцию.
        final Object[] snapshotItems;
        synchronized (collection) {
            snapshotItems = collection.toArray();
        }
        for (final Object object : snapshotItems) {
            final Class<?> c = object != null ? entityClassOf(object) : null;
            if (object == null) {
                writeNull(writer, level + 1);
            } else if (c.isArray()) {
                writeArray(c, object, writer, level + 1);
            } else if (simpleTypeHelper.isSimpleType(c, object)) {
                writeSimpleType(c, object, writer, level + 1);
            } else if (c.isEnum()) {
                writeEnumValue(c, object, writer, level + 1);
            } else if (object instanceof Collection<?>) {
                writeCollection(c, (Collection<?>) object, writer, level + 1);
            } else if (object instanceof Map<?, ?>) {
                writeMap(c, (Map<?, ?>) object, writer, level + 1);
            } else if (XdStorageObjectUtils.getClassInfo(c).getIdField() != null) {
                writeObject(c, object, writer, level + 1);
            } else {
                writeObject(c, object, writer, level + 1);
            }
        }
        writeNewLevelAndTabulations(writer, level);
        writer.append("</collection>");
    }

    @SuppressWarnings("unchecked")
    private void writeCollection(final Class<?> cl, final Collection<?> collection, final Writer writer, int level) throws IOException {
        writeNewLevelAndTabulations(writer, level);
        writer.append("<collection class=\"").append(cl.getName()).append("\">");
        // R-43: снимок под монитором коллекции (см. writeCollection с именем).
        final Object[] snapshotItems;
        synchronized (collection) {
            snapshotItems = collection.toArray();
        }
        for (final Object object : snapshotItems) {
            final Class<?> c = object != null ? entityClassOf(object) : null;
            if (object == null) {
                writeNull(writer, level + 1);
            } else if (c.isArray()) {
                writeArray(c, object, writer, level + 1);
            } else if (simpleTypeHelper.isSimpleType(c, object)) {
                writeSimpleType(c, object, writer, level + 1);
            } else if (c.isEnum()) {
                writeEnumValue(c, object, writer, level + 1);
            } else if (object instanceof Collection<?>) {
                writeCollection(c, (Collection<?>) object, writer, level + 1);
            } else if (object instanceof Map<?, ?>) {
                writeMap(c, (Map<?, ?>) object, writer, level + 1);
            } else if (XdStorageObjectUtils.getClassInfo(c).getIdField() != null) {
                writeObject(c, object, writer, level + 1);
            } else {
                writeObject(c, object, writer, level + 1);
            }
        }
        writeNewLevelAndTabulations(writer, level);
        writer.append("</collection>");
    }

    @SuppressWarnings("unchecked")
    private void writeMap(final String name, final Class<?> cl, final Map<?, ?> map, final Writer writer, int level) throws IOException {
        writeNewLevelAndTabulations(writer, level);
        writer.append("<map name=\"").append(name).append("\" class=\"").append(cl.getName()).append("\">");
        // R-43: снимок под монитором карты — защита от ConcurrentModificationException
        // при параллельной мутации (см. зеркальный комментарий в writeCollection).
        final Map.Entry<?, ?>[] snapshotEntries;
        synchronized (map) {
            snapshotEntries = map.entrySet().toArray(new Map.Entry<?, ?>[0]);
        }
        for (final Map.Entry<?, ?> pair : snapshotEntries) {
            writeNewLevelAndTabulations(writer, level + 1);
            writer.append("<entry>");
            final Object key = pair.getKey();
            Class<?> c = key != null ? entityClassOf(key) : null;
            if (key == null) {
                writeNull(writer, level + 2);
            } else if (c.isArray()) {
                writeArray(c, key, writer, level + 2);
            } else if (simpleTypeHelper.isSimpleType(c, key)) {
                writeSimpleType(c, key, writer, level + 2);
            } else if (c.isEnum()) {
                writeEnumValue(c, key, writer, level + 2);
            } else if (key instanceof Collection<?>) {
                writeCollection(c, (Collection<?>) key, writer, level + 2);
            } else if (key instanceof Map<?, ?>) {
                writeMap(c, (Map<?, ?>) key, writer, level + 2);
            } else if (XdStorageObjectUtils.getClassInfo(c).getIdField() != null) {
                writeObject(c, key, writer, level + 2);
            } else {
                writeObject(c, key, writer, level + 2);
            }

            final Object object = pair.getValue();
            c = object != null ? entityClassOf(object) : null;
            if (object == null) {
                writeNull(writer, level + 2);
            } else if (c.isArray()) {
                writeArray(c, object, writer, level + 2);
            } else if (simpleTypeHelper.isSimpleType(c, object)) {
                writeSimpleType(c, object, writer, level + 2);
            } else if (c.isEnum()) {
                writeEnumValue(c, object, writer, level + 2);
            } else if (object instanceof Collection<?>) {
                writeCollection(c, (Collection<?>) object, writer, level + 2);
            } else if (object instanceof Map<?, ?>) {
                writeMap(c, (Map<?, ?>) object, writer, level + 2);
            } else if (XdStorageObjectUtils.getClassInfo(c).getIdField() != null) {
                writeObject(c, object, writer, level + 2);
            } else {
                writeObject(c, object, writer, level + 2);
            }
            writeNewLevelAndTabulations(writer, level + 1);
            writer.append("</entry>");
        }
        writeNewLevelAndTabulations(writer, level);
        writer.append("</map>");
    }

    @SuppressWarnings("unchecked")
    private void writeMap(final Class<?> cl, final Map<?, ?> map, final Writer writer, int level) throws IOException {
        writeNewLevelAndTabulations(writer, level);
        writer.append("<map class=\"").append(cl.getName()).append("\">");
        // R-43: снимок под монитором карты (см. writeCollection).
        final Map.Entry<?, ?>[] snapshotEntries;
        synchronized (map) {
            snapshotEntries = map.entrySet().toArray(new Map.Entry<?, ?>[0]);
        }
        for (final Map.Entry<?, ?> pair : snapshotEntries) {
            writeNewLevelAndTabulations(writer, level + 1);
            writer.append("<entry>");
            final Object key = pair.getKey();
            Class<?> c = key != null ? entityClassOf(key) : null;
            if (key == null) {
                writeNull(writer, level + 2);
            } else if (c.isArray()) {
                writeArray(c, key, writer, level + 2);
            } else if (simpleTypeHelper.isSimpleType(c, key)) {
                writeSimpleType(c, key, writer, level + 2);
            } else if (c.isEnum()) {
                writeEnumValue(c, key, writer, level + 2);
            } else if (key instanceof Collection<?>) {
                writeCollection(c, (Collection<?>) key, writer, level + 2);
            } else if (key instanceof Map<?, ?>) {
                writeMap(c, (Map<?, ?>) key, writer, level + 2);
            } else if (XdStorageObjectUtils.getClassInfo(c).getIdField() != null) {
                writeObject(c, key, writer, level + 2);
            } else {
                writeObject(c, key, writer, level + 2);
            }

            final Object object = pair.getValue();
            c = object != null ? entityClassOf(object) : null;
            if (object == null) {
                writeNull(writer, level + 2);
            } else if (c.isArray()) {
                writeArray(c, object, writer, level + 2);
            } else if (simpleTypeHelper.isSimpleType(c, object)) {
                writeSimpleType(c, object, writer, level + 2);
            } else if (c.isEnum()) {
                writeEnumValue(c, object, writer, level + 2);
            } else if (object instanceof Collection<?>) {
                writeCollection(c, (Collection<?>) object, writer, level + 2);
            } else if (object instanceof Map<?, ?>) {
                writeMap(c, (Map<?, ?>) object, writer, level + 2);
            } else if (XdStorageObjectUtils.getClassInfo(c).getIdField() != null) {
                writeObject(c, object, writer, level + 2);
            } else {
                writeObject(c, object, writer, level + 2);
            }
            writeNewLevelAndTabulations(writer, level + 1);
            writer.append("</entry>");
        }
        writeNewLevelAndTabulations(writer, level);
        writer.append("</map>");
    }

    private void writeObject(final String name, final Class<?> cl, final Object object, final Writer writer, int level) throws IOException {
        final XdStorageClassInfo clInfo = XdStorageObjectUtils.getClassInfo(cl);
        final XdStoragePolicy policy = clInfo.getPolicy();
        if (policy == null || policy == XdStoragePolicy.StoreWithParentObject) {
            writeObjectData(name, cl, object, writer, level);
        } else {
            writeReference(name, object, writer, level);
        }
    }

    private void writeObject(final Class<?> cl, final Object object, final Writer writer, int level) throws IOException {
        final XdStorageClassInfo clInfo = XdStorageObjectUtils.getClassInfo(cl);
        final XdStoragePolicy policy = clInfo.getPolicy();
        if (policy == null || policy == XdStoragePolicy.StoreWithParentObject) {
            writeObjectData(cl, object, writer, level);
        } else {
            writeReference(object, writer, clInfo.getIdField(), level);
        }
    }

    @SuppressWarnings("unchecked")
    private void writeObjectData(final String name, final Class<?> cl, final Object object, final Writer writer, int level) throws IOException {
        Collection<XdStorageObjectField> props = properties.get(cl);
        if (props == null) {
            properties.put(cl, props = XdStorageObjectUtils.getClassInfo(cl).getFields().values());
        }

        writeNewLevelAndTabulations(writer, level);
        writer.append("<object name=\"").append(name).append("\" class=\"").append(cl.getName()).append("\">");
        for (final XdStorageObjectField property : props) {
            if (property.isIdField()) {
                assignGeneratedIdIfNeeded(cl, object);
            }

            final Object value = property.get(object);
            final Class<?> c = value != null ? value.getClass() : null;
            if (value == null) {
                writeNull(property.getName(), writer, level + 1);
            } else if (c.isArray()) {
                writeArray(property.getName(), c, value, writer, level + 1);
            } else if (simpleTypeHelper.isSimpleType(c, value)) {
                writeSimpleType(property.getName(), c, value, writer, level + 1);
            } else if (c.isEnum()) {
                writeEnumValue(property.getName(), c, value, writer, level + 1);
            } else if (value instanceof Collection<?>) {
                writeCollection(property.getName(), c, (Collection<?>) value, writer, level + 1);
            } else if (value instanceof Map<?, ?>) {
                writeMap(property.getName(), c, (Map<?, ?>) value, writer, level + 1);
            } else if (property.isParent()) {
                writeReference(property.getName(), value, writer, level + 1);
            } else if (XdStorageObjectUtils.getClassInfo(c).getIdField() != null) {
                writeObject(property.getName(), c, value, writer, level + 1);
            } else {
                writeObject(property.getName(), c, value, writer, level + 1);
            }
        }
        writeNewLevelAndTabulations(writer, level);
        writer.append("</object>");
    }

    @SuppressWarnings("unchecked")
    private void writeObjectData(final Class<?> cl, final Object object, final Writer writer, int level) throws IOException {
        Collection<XdStorageObjectField> props = properties.get(cl);
        if (props == null) {
            properties.put(cl, props = XdStorageObjectUtils.getClassInfo(cl).getFields().values());
        }

        writeNewLevelAndTabulations(writer, level);
        writer.append("<object class=\"").append(cl.getName()).append("\">");
        for (final XdStorageObjectField property : props) {
            if (property.isIdField()) {
                assignGeneratedIdIfNeeded(cl, object);
            }

            final Object value = property.get(object);
            final Class<?> c = value != null ? value.getClass() : null;
            if (value == null) {
                writeNull(property.getName(), writer, level + 1);
            } else if (c.isArray()) {
                writeArray(property.getName(), c, value, writer, level + 1);
            } else if (simpleTypeHelper.isSimpleType(c, value)) {
                writeSimpleType(property.getName(), c, value, writer, level + 1);
            } else if (c.isEnum()) {
                writeEnumValue(property.getName(), c, value, writer, level + 1);
            } else if (value instanceof Collection<?>) {
                writeCollection(property.getName(), c, (Collection<?>) value, writer, level + 1);
            } else if (value instanceof Map<?, ?>) {
                writeMap(property.getName(), c, (Map<?, ?>) value, writer, level + 1);
            } else if (property.isParent()) {
                writeReference(property.getName(), value, writer, level + 1);
            } else if (XdStorageObjectUtils.getClassInfo(c).getIdField() != null) {
                writeObject(property.getName(), c, value, writer, level + 1);
            } else {
                writeObject(property.getName(), c, value, writer, level + 1);
            }
        }
        writeNewLevelAndTabulations(writer, level);
        writer.append("</object>");
    }

    private void writeReference(final String name, final Object object, final Writer writer, int level) throws IOException {
        final XdStorageObjectIdField field = XdStorageObjectUtils.getClassInfo(object.getClass()).getIdField();
        writeNewLevelAndTabulations(writer, level);
        writer.append("<reference name=\"").append(name).append("\" class=\"");
        Class<?> entityClass = XdStorageObjectUtils.getEntityClass(object.getClass());
        writer.append(entityClass.getName());
        writer.append("\" classObjectId=\"");
        if (field.get(object) == null) {
            assignGeneratedIdIfNeeded(entityClass, object);
        }
        writer.append(field.get(object).getClass().getName());
        final Object value = field.get(object);
        final Class<?> cl = entityClassOf(value);
        if (cl == Class.class) {
            writer.append("\" objectId=\"");
            writer.append(((Class)value).getName());
            writer.append("\"/>");
        } else if (XdStorageObjectUtils.isSimpleType(cl, null)) {
            writer.append("\" objectId=\"");
            writer.append(value.toString());
            writer.append("\"/>");
        } else {
            writer.append("\">");
            writeObject(field.getName(), cl, value, writer, level + 1);
            writeNewLevelAndTabulations(writer, level);
            writer.append("</reference>");
        }
    }

    private String encode(final String value) {
        if (value == null) {
            return null;
        }
        boolean anyCharactersProtected = false;

        StringBuffer stringBuffer = new StringBuffer();
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);

            boolean controlCharacter = ch < 32;
            boolean unicodeButNotAscii = ch > 126;
            boolean characterWithSpecialMeaningInXML = ch == '<' || ch == '&' || ch == '>' || ch == '\"' || ch == '\'';

            if (characterWithSpecialMeaningInXML || unicodeButNotAscii || controlCharacter) {
                stringBuffer.append("&#" + (int) ch + ";");
                anyCharactersProtected = true;
            } else {
                stringBuffer.append(ch);
            }
        }

        return anyCharactersProtected ? stringBuffer.toString() : value;
    }

    private void writeNewLevelAndTabulations(final Writer writer, int level) throws IOException {
        writer.append("\r\n");
        for (int i = 0; i < level; ++i)
            writer.append('\t');
    }


    /**
     * BUG-D (docs/tasks/2.1.0/14): the single id-generation gate of every write path. An id
     * is engine-assigned here when it is still null and its field asks for engine
     * generation — DATABASE_GENERATOR (Hi-Lo/UUID by id type), UUID_GENERATOR (the same
     * mux, validated to String ids), or CUSTOM_GENERATOR (the mux resolves the SPI class).
     * Fields without an engine generator type keep whatever the application set.
     */
    private void assignGeneratedIdIfNeeded(final Class<?> cl, final Object object) throws XdStorageIOException {
        final XdStorageClassInfo clInfo = XdStorageObjectUtils.getClassInfo(cl);
        final XdStorageObjectIdField idField = clInfo.getIdField();
        final XdStorageIdGeneratorType generatorType = idField.getIdGeneratorType();
        final boolean engineGenerates = generatorType == XdStorageIdGeneratorType.DATABASE_GENERATOR
                || generatorType == XdStorageIdGeneratorType.UUID_GENERATOR
                || generatorType == XdStorageIdGeneratorType.CUSTOM_GENERATOR;
        if (engineGenerates && idField.get(object) == null) {
            try {
                idField.set(XdStorageObserverService.getObservableWrapper(object), idGenerator.generate(cl, services.getStorage(), null));
            } catch (final XdStorageException e) {
                throw new XdStorageIOException(e);
            }
        }
    }

}
