package io.github.xdstorage.serialization;

import org.slf4j.LoggerFactory;
import org.slf4j.Logger;

import io.github.xdstorage.exceptions.XdStorageIOException;
import io.github.xdstorage.helpers.IXdStorageSimpleTypeHelper;
import io.github.xdstorage.object.XdStorageIdentifiableObject;
import io.github.xdstorage.transaction.IXdStorageTransaction;
import io.github.xdstorage.utils.XdStorageClassInfo;
import io.github.xdstorage.utils.XdStorageObjectField;
import io.github.xdstorage.utils.XdStorageObjectIdField;
import io.github.xdstorage.utils.XdStorageObjectUtils;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.Reader;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The XML implementation of {@link IXdStorageObjectsReader}: parses the StAX event
 * stream of a resource file and restores live objects (and typed data records) through
 * reflection, using the simple-type helper for scalar attributes. Handles the full
 * element grammar — nested {@code object}, {@code array}, {@code primitive},
 * {@code enum}, {@code collection}, {@code map} and id-only {@code reference} entries.
 *
 * <p>Thread-safety: instances are confined to one worker thread (handed out per thread
 * by {@link XdStorageDefaultIOFactory}); the {@code properties} reflection cache is a
 * {@code ConcurrentHashMap} and safe under concurrent population. Engine-internal —
 * not part of the public API.</p>
 */
public class XdStorageDefaultObjectsReader implements IXdStorageObjectsReader {

    private static final Logger log = LoggerFactory.getLogger(XdStorageDefaultObjectsReader.class);

    private IXdStorageSimpleTypeHelper simpleTypeHelper;

    private final Map<Class<?>, Map<String, XdStorageObjectField>> properties = new ConcurrentHashMap<>();

    /**
     * Creates the XML reader.
     *
     * @param simpleTypeHelper the simple-type SPI used to restore scalar attribute values
     */
    public XdStorageDefaultObjectsReader(final IXdStorageSimpleTypeHelper simpleTypeHelper) {
        this.simpleTypeHelper = simpleTypeHelper;
    }

    @Override
    public Collection<Object> readReferences(final Reader reader, final XdStorageObjectIdField field) throws XdStorageIOException {
        Collection<Object> result = new ArrayList<Object>();
        try {
            XMLStreamReader xmlReader = XMLInputFactory.newInstance().createXMLStreamReader(reader);
            Object tmp = null;
            while (xmlReader.hasNext()) {
                switch (xmlReader.next()) {
                    case XMLStreamConstants.START_ELEMENT:
                        if (xmlReader.getLocalName().equals("object") || xmlReader.getLocalName().equals("reference")) {
                            tmp = readElement(xmlReader, field);
                        }
                        break;
                }
                if (tmp != null) {
                    result.add(tmp);
                    tmp = null;
                }
            }
        } catch (final Throwable cause) { // stupid quick solution
            throw new XdStorageIOException(cause);
        }
        return result;
    }

    @Override
    public Collection<Object> read(final Reader reader) throws XdStorageIOException {
        Collection<Object> result = new ArrayList<Object>();
        try {
            XMLStreamReader xmlReader = XMLInputFactory.newInstance().createXMLStreamReader(reader);
            Object tmp = null;
            while (xmlReader.hasNext()) {
                switch (xmlReader.next()) {
                    case XMLStreamConstants.START_ELEMENT:
                        if (xmlReader.getLocalName().equals("object") || xmlReader.getLocalName().equals("reference")) {
                            tmp = readElement(xmlReader);
                        }
                        break;
                }
                if (tmp != null) {
                    result.add(tmp);
                    tmp = null;
                }
            }
        } catch (final Throwable cause) { // stupid quick solution
            throw new XdStorageIOException(cause);
        }
        return result;
    }

    @Override
    public Collection<Object> read(final Reader r, final IXdStorageTransaction tx) throws XdStorageIOException {
        Collection<Object> res = new ArrayList<>();
        try {
            XMLStreamReader xReader = XMLInputFactory.newInstance().createXMLStreamReader(r);
            while (xReader.hasNext()) {
                if (xReader.next() == XMLStreamConstants.START_ELEMENT && (xReader.getLocalName().equals("object") || xReader.getLocalName().equals("reference"))) {
                    final String[] fn = new String[]{null};
                    Object tmp = xReader.getLocalName().equals("object") ? read(fn, xReader) : readReference(fn, xReader);
                    if (tmp != null) res.add(tmp);
                }
            }
        } catch (Throwable cause) {
            if (cause.getMessage() != null && cause.getMessage().contains("Premature end of file."))
                return Collections.emptyList();
            throw new XdStorageIOException(cause);
        }
        return res;
    }

    private Object readElement(final XMLStreamReader xmlReader) throws Exception {
        Object result = null;
        final String[] fn = new String[]{null};
        final String name = xmlReader.getLocalName();
        // YAMLREAD: the mirror refusal — a `reference` element in a data-object file.
        if (name.equals("reference")) {
            throw new XdStorageIOException("a data resource must contain object elements, got a reference element (corrupt or wrong-kind file)");
        }
        if (name.equals("object")) {
            result = read(fn, xmlReader);
        }
        return result;
    }

    private Object readElement(final XMLStreamReader xmlReader, final XdStorageObjectIdField field) throws Exception {
        Object result = null;
        final String[] fn = new String[]{null};
        final String name = xmlReader.getLocalName();
        // YAMLREAD (docs/tasks/2.1.0/37): an `object` element in a references file is a
        // wrong-format/corrupt file — refuse loudly instead of silently yielding nothing.
        if (name.equals("object")) {
            throw new XdStorageIOException("a references resource must contain reference elements, got an object element (corrupt or wrong-kind file)");
        }
        if (name.equals("reference")) {
            result = readReference(fn, xmlReader, field);
        }
        return result;
    }

    private Object read(final String fieldName[], final XMLStreamReader xmlReader) throws Exception {
        final int count = xmlReader.getAttributeCount();
        String className = null, value = null;
        for (int i = 0; i < count; ++i) {
            final String attName = xmlReader.getAttributeLocalName(i);
            final String attValue = xmlReader.getAttributeValue(i);

            if (attName.equals("name")) {
                fieldName[0] = attValue;
            } else if (attName.equals("class")) {
                className = attValue;
            } else if (attName.equals("value")) {
                value = attValue;
            }
        }
        if (className == null) {
            xmlReader.nextTag();
            return null;
        }

        final Class<?> cl = Class.forName(className);
        if (value != null) {
            xmlReader.nextTag();
            return simpleTypeHelper.simpleTypeFromString(cl, value);
        }

        Map<String, XdStorageObjectField> props = properties.get(cl);
        if (props == null) {
            properties.put(cl, props = XdStorageObjectUtils.getClassInfo(cl).getFields());
        }

        Object result = cl.newInstance(), tmp = null;
        final String fn[] = new String[]{null};
        while (xmlReader.hasNext()) {
            final int type = xmlReader.nextTag();
            if (type == XMLStreamConstants.END_ELEMENT && xmlReader.getLocalName().equals("object"))
                break;
            if (type != XMLStreamConstants.START_ELEMENT)
                continue;

            final String elemName = xmlReader.getLocalName();
            if (elemName.equals("object")) {
                tmp = read(fn, xmlReader);
            } else if (elemName.equals("array")) {
                tmp = readArray(fn, xmlReader);
            } else if (elemName.equals("primitive")) {
                tmp = readPrimitive(fn, xmlReader);
            } else if (elemName.equals("enum")) {
                tmp = readEnum(fn, xmlReader);
            } else if (elemName.equals("collection")) {
                tmp = readCollection(fn, xmlReader);
            } else if (elemName.equals("map")) {
                tmp = readMap(fn, xmlReader);
            } else if (elemName.equals("reference")) {
                tmp = readReference(fn, xmlReader);
            }
            final XdStorageObjectField property = props.get(fn[0]);
            if (property != null) {
                property.set(result, tmp);
            }
        }

        return result;
    }

    private Object readArray(final String[] fieldName, final XMLStreamReader xmlReader) throws Exception {
        final int count = xmlReader.getAttributeCount();
        String className = null, length = null;
        for (int i = 0; i < count; ++i) {
            final String attName = xmlReader.getAttributeLocalName(i);
            final String attValue = xmlReader.getAttributeValue(i);

            if (attName.equals("name")) {
                fieldName[0] = attValue;
            } else if (attName.equals("class")) {
                className = attValue;
            } else if (attName.equals("length")) {
                length = attValue;
            }
        }

        Class<?> componentType;
        if ((componentType = getPrimitiveType(className)) == null) {
            componentType = Class.forName(className);
        }
        final Object array = Array.newInstance(componentType, Integer.parseInt(length));

        Object tmp = null;
        final String fn[] = new String[]{null};
        int i = 0;
        while (xmlReader.hasNext()) {
            final int type = xmlReader.nextTag();
            if (type == XMLStreamConstants.END_ELEMENT && xmlReader.getLocalName().equals("array"))
                break;
            if (type != XMLStreamConstants.START_ELEMENT)
                continue;

            final String elemName = xmlReader.getLocalName();
            if (elemName.equals("object")) {
                tmp = read(fn, xmlReader);
            } else if (elemName.equals("array")) {
                tmp = readArray(fn, xmlReader);
            } else if (elemName.equals("primitive")) {
                tmp = readPrimitive(fn, xmlReader);
            } else if (elemName.equals("enum")) {
                tmp = readEnum(fn, xmlReader);
            } else if (elemName.equals("collection")) {
                tmp = readCollection(fn, xmlReader);
            } else if (elemName.equals("map")) {
                tmp = readMap(fn, xmlReader);
            } else if (elemName.equals("reference")) {
                tmp = readReference(fn, xmlReader);
            }
            Array.set(array, i, tmp);
            ++i;
        }

        return array;
    }

    private Class<?> getPrimitiveType(final String className) {
        if (className.equals(byte.class.getName())) {
            return byte.class;
        }
        if (className.equals(short.class.getName())) {
            return short.class;
        }
        if (className.equals(int.class.getName())) {
            return int.class;
        }
        if (className.equals(long.class.getName())) {
            return long.class;
        }
        if (className.equals(float.class.getName())) {
            return float.class;
        }
        if (className.equals(double.class.getName())) {
            return double.class;
        }
        if (className.equals(boolean.class.getName())) {
            return boolean.class;
        }
        if (className.equals(char.class.getName())) {
            return char.class;
        }
        return null;
    }

    private Object readPrimitive(final String[] fieldName, final XMLStreamReader xmlReader) {
        final int count = xmlReader.getAttributeCount();
        String className = null, value = null;
        for (int i = 0; i < count; ++i) {
            final String attName = xmlReader.getAttributeLocalName(i);
            final String attValue = xmlReader.getAttributeValue(i);

            if (attName.equals("name")) {
                fieldName[0] = attValue;
            } else if (attName.equals("class")) {
                className = attValue;
            } else if (attName.equals("value")) {
                value = attValue;
            }
        }

        Object result = null;
        if (className.equals("byte")) {
            result = Byte.parseByte(value);
        } else if (className.equals("short")) {
            result = Short.parseShort(value);
        } else if (className.equals("int")) {
            result = Integer.parseInt(value);
        } else if (className.equals("long")) {
            result = Long.parseLong(value);
        } else if (className.equals("float")) {
            result = Float.parseFloat(value);
        } else if (className.equals("double")) {
            result = Double.parseDouble(value);
        } else if (className.equals("boolean")) {
            result = Boolean.parseBoolean(value);
        } else if (className.equals("char")) {
            result = value.charAt(0);
        }
        return result;
    }

    private Object readEnum(final String[] fieldName, final XMLStreamReader xmlReader) throws Exception {
        final int count = xmlReader.getAttributeCount();
        String className = null, value = null;
        for (int i = 0; i < count; ++i) {
            final String attName = xmlReader.getAttributeLocalName(i);
            final String attValue = xmlReader.getAttributeValue(i);

            if (attName.equals("name")) {
                fieldName[0] = attValue;
            } else if (attName.equals("class")) {
                className = attValue;
            } else if (attName.equals("value")) {
                value = attValue;
            }
        }

        final Class<?> cl = Class.forName(className);
        final Method m = cl.getMethod("valueOf", String.class);
        return m.invoke(cl, value);
    }

    private Object readMapEnum(final String[] fieldName, final XMLStreamReader xmlReader) throws Exception {
        final int count = xmlReader.getAttributeCount();
        String className = null, value = null;
        for (int i = 0; i < count; ++i) {
            final String attName = xmlReader.getAttributeLocalName(i);
            final String attValue = xmlReader.getAttributeValue(i);

            if (attName.equals("name")) {
                fieldName[0] = attValue;
            } else if (attName.equals("class")) {
                className = attValue;
            } else if (attName.equals("value")) {
                value = attValue;
            }
        }

        final Class<?> cl = Class.forName(className);
        final Method m = cl.getMethod("valueOf", String.class);
        return m.invoke(cl, value);
    }

    @SuppressWarnings("unchecked")
    private Object readCollection(final String[] fieldName, final XMLStreamReader xmlReader) throws Exception {
        final int count = xmlReader.getAttributeCount();
        String className = null;
        for (int i = 0; i < count; ++i) {
            final String attName = xmlReader.getAttributeLocalName(i);
            final String attValue = xmlReader.getAttributeValue(i);

            if (attName.equals("name")) {
                fieldName[0] = attValue;
            } else if (attName.equals("class")) {
                className = attValue;
            }
        }

        Class<?> cl = Class.forName(className);
        Collection<Object> collection;
        try {
            collection = (Collection<Object>) cl.newInstance();
        } catch (final Exception e) {
            // R-43: неинстанцируемый класс коллекции (обертка java.util.Collections$SynchronizedList
            // и т.п.) — канонический фолбэк LinkedList; владелец-нода сама оборачивает списки
            // в synchronized-обертки в setKeys/setObjects/setChildren.
            collection = new java.util.LinkedList<>();
        }
        final String[] fn = new String[]{null};
        while (xmlReader.hasNext()) {
            final int type = xmlReader.nextTag();
            if (type == XMLStreamConstants.END_ELEMENT && xmlReader.getLocalName().equals("collection"))
                break;
            if (type != XMLStreamConstants.START_ELEMENT)
                continue;

            final String elemName = xmlReader.getLocalName();
            Object tmp = null;
            if (elemName.equals("object")) {
                tmp = read(fn, xmlReader);
            } else if (elemName.equals("array")) {
                tmp = readArray(fn, xmlReader);
            } else if (elemName.equals("enum")) {
                tmp = readEnum(fn, xmlReader);
            } else if (elemName.equals("collection")) {
                tmp = readCollection(fn, xmlReader);
            } else if (elemName.equals("map")) {
                tmp = readMap(fn, xmlReader);
            } else if (elemName.equals("reference")) {
                tmp = readReference(fn, xmlReader);
            }
            collection.add(tmp);
        }
        return collection;
    }

    @SuppressWarnings("unchecked")
    private Object readMap(final String[] fieldName, final XMLStreamReader xmlReader) throws Exception {
        final int count = xmlReader.getAttributeCount();
        String className = null;
        for (int i = 0; i < count; ++i) {
            final String attName = xmlReader.getAttributeLocalName(i);
            final String attValue = xmlReader.getAttributeValue(i);

            if (attName.equals("name")) {
                fieldName[0] = attValue;
            } else if (attName.equals("class")) {
                className = attValue;
            }
        }

        Class<?> cl = Class.forName(className);
        Map<Object, Object> map;
        try {
            map = (Map<Object, Object>) cl.newInstance();
        } catch (final Exception e) {
            // R-43: неинстанцируемый класс карты (обертка Collections$SynchronizedMap и т.п.)
            map = new java.util.HashMap<>();
        }
        final String[] fn = new String[]{null};
        while (xmlReader.hasNext()) {
            final int type = xmlReader.nextTag();
            if (type == XMLStreamConstants.END_ELEMENT && xmlReader.getLocalName().equals("map"))
                break;
            if (type != XMLStreamConstants.START_ELEMENT || !xmlReader.getLocalName().equals("entry"))
                continue;

            xmlReader.nextTag();
            String elemName = xmlReader.getLocalName();
            Object key = null;
            if (elemName.equals("object")) {
                key = read(fn, xmlReader);
            } else if (elemName.equals("array")) {
                key = readArray(fn, xmlReader);
            } else if (elemName.equals("enum")) {
                key = readEnum(fn, xmlReader);
            } else if (elemName.equals("collection")) {
                key = readCollection(fn, xmlReader);
            } else if (elemName.equals("map")) {
                key = readMap(fn, xmlReader);
            } else if (elemName.equals("reference")) {
                key = readReference(fn, xmlReader);
            }
            // end of prev element
            xmlReader.next();

            xmlReader.nextTag();
            elemName = xmlReader.getLocalName();
            Object value = null;
            if (elemName.equals("object")) {
                value = read(fn, xmlReader);
            } else if (elemName.equals("array")) {
                value = readArray(fn, xmlReader);
            } else if (elemName.equals("enum")) {
                value = readEnum(fn, xmlReader);
            } else if (elemName.equals("collection")) {
                value = readCollection(fn, xmlReader);
            } else if (elemName.equals("map")) {
                value = readMap(fn, xmlReader);
            } else if (elemName.equals("reference")) {
                value = readReference(fn, xmlReader);
            }
            map.put(key, value);
        }
        return map;
    }

    private Object readReference(final String fieldName[], final XMLStreamReader xmlReader) throws Exception {
        final int count = xmlReader.getAttributeCount();
        String className = null, idValue = null, classObjectId = null;
        for (int i = 0; i < count; ++i) {
            final String attName = xmlReader.getAttributeLocalName(i);
            final String attValue = xmlReader.getAttributeValue(i);
            if (attName.equals("name")) {
                fieldName[0] = attValue;
            } else if (attName.equals("class")) {
                className = attValue;
            } else if (attName.equals("dataStorageId")) {
                idValue = attValue;
            } else if (attName.equals("classObjectId")) {
                classObjectId = attValue;
            } else if (attName.equals("objectId")) {
                idValue = attValue;
            }
        }

        final Object result = Class.forName(className).newInstance();
        final Class<?> idClass = Class.forName(classObjectId);
        Object objectId = null;

        if (idClass == Class.class) {
            objectId = Class.forName(idValue);
        } else if (idValue == null) {
            boolean stop = false;
            while (xmlReader.hasNext()) {
                switch (xmlReader.next()) {
                    case XMLStreamConstants.START_ELEMENT:
                        if (xmlReader.getLocalName().equals("object")) {
                            objectId = readElement(xmlReader);
                            stop = true;
                        }
                        break;
                }
                if (stop) break;
            }
        } else {
            // ИСПРАВЛЕНИЕ СУБД: Заменяем хрупкий вызов idClass.getConstructor(String.class)
            // на канонический пуленепробиваемый фабричный вызов simpleTypeHelper.simpleTypeFromString!
            // Это гарантирует 100% точное восстановление java.lang.Long для прокси-классов ID в Java 9+.
            objectId = simpleTypeHelper.simpleTypeFromString(idClass, idValue);
        }

        final XdStorageObjectIdField field = XdStorageObjectUtils.getClassInfo(result.getClass()).getIdField();
        field.set(result, objectId);
        return result;
    }

    private Object readReference(final String fieldName[], final XMLStreamReader xmlReader, final XdStorageObjectIdField field) throws Exception {
        final int count = xmlReader.getAttributeCount();
        String className = null, classObjectId = null, idValue = null;
        for (int i = 0; i < count; ++i) {
            final String attName = xmlReader.getAttributeLocalName(i);
            final String attValue = xmlReader.getAttributeValue(i);
            if (attName.equals("name")) {
                fieldName[0] = attValue;
            } else if (attName.equals("class")) {
                className = attValue;
            } else if (attName.equals("classObjectId")) {
                classObjectId = attValue;
            } else if (attName.equals("objectId")) {
                idValue = attValue;
            }
        }

        final Class<?> idClass = Class.forName(classObjectId);
        Object objectId = null;

        if (idClass == Class.class) {
            objectId = Class.forName(idValue);
        } else if (idValue == null) {
            boolean stop = false;
            while (xmlReader.hasNext()) {
                switch (xmlReader.next()) {
                    case XMLStreamConstants.START_ELEMENT:
                        if (xmlReader.getLocalName().equals("object")) {
                            objectId = readElement(xmlReader);
                            stop = true;
                        }
                        break;
                }
                if (stop) break;
            }
        } else {
            // ИСПРАВЛЕНИЕ СУБД: Восстановление типов Long/Integer через simpleTypeHelper для пакетного чтения ссылок
            objectId = simpleTypeHelper.simpleTypeFromString(idClass, idValue);
        }

        final Object result = Class.forName(className).newInstance();
        field.set(result, objectId);
        return result;
    }

    @Override
    public Collection<XdStorageIdentifiableObject> readData(final Reader reader, final XdStorageObjectIdField field) throws XdStorageIOException {
        Collection<XdStorageIdentifiableObject> result = new ArrayList<>();
        try {
            XMLStreamReader xmlReader = XMLInputFactory.newInstance().createXMLStreamReader(reader);
            XdStorageIdentifiableObject tmp = null;
            final String[] fieldName = new String[]{null};
            while (xmlReader.hasNext()) {
                switch (xmlReader.next()) {
                    case XMLStreamConstants.START_ELEMENT:
                        if (xmlReader.getLocalName().equals("object")) {
                            tmp = (XdStorageIdentifiableObject) readObjectData(fieldName, xmlReader);
                        } else if (xmlReader.getLocalName().equals("reference")) {
                            tmp = (XdStorageIdentifiableObject) readObjectDataReference(fieldName, xmlReader);
                        }
                        break;
                }
                if (tmp != null)
                    result.add(tmp);
            }
        } catch (final Throwable cause) { // stupid quick solution
            throw new XdStorageIOException(cause);
        }
        return result;
    }

    private Object readObjectData(final String[] fieldName, final XMLStreamReader xmlReader) throws Exception {
        final int count = xmlReader.getAttributeCount();
        String className = null, value = null;
        for (int i = 0; i < count; ++i) {
            final String attName = xmlReader.getAttributeLocalName(i);
            final String attValue = xmlReader.getAttributeValue(i);

            if (attName.equals("name")) {
                fieldName[0] = attValue;
            } else if (attName.equals("class")) {
                className = attValue;
            } else if (attName.equals("value")) {
                value = attValue;
            }
        }
        if (className == null) {
            xmlReader.nextTag();
            return null;
        }

        final Class<?> cl = Class.forName(className);
        if (value != null) {
            xmlReader.nextTag();
            return simpleTypeHelper.simpleTypeFromString(cl, value);
        }

        final XdStorageClassInfo clInfo = XdStorageObjectUtils.getClassInfo(cl);

        Map<String, XdStorageObjectField> props = properties.get(cl);
        if (props == null) {
            properties.put(cl, props = clInfo.getFields());
        }

        final XdStorageObjectIdField idField = clInfo.getIdField();

        final XdStorageIdentifiableObject result = new XdStorageIdentifiableObject();
        result.setType(cl);

        final String fn[] = new String[]{null};
        Object tmp = null;
        while (xmlReader.hasNext()) {
            final int type = xmlReader.nextTag();
            if (type == XMLStreamConstants.END_ELEMENT && xmlReader.getLocalName().equals("object"))
                break;
            if (type != XMLStreamConstants.START_ELEMENT)
                continue;

            final String elemName = xmlReader.getLocalName();
            if (elemName.equals("object")) {
                tmp = readObjectData(fn, xmlReader);
            } else if (elemName.equals("array")) {
                tmp = readObjectDataArray(fn, xmlReader);
            } else if (elemName.equals("primitive")) {
                tmp = readPrimitive(fn, xmlReader);
            } else if (elemName.equals("enum")) {
                tmp = readEnum(fn, xmlReader);
            } else if (elemName.equals("collection")) {
                tmp = readObjectDataCollection(fn, xmlReader);
            } else if (elemName.equals("map")) {
                tmp = readObjectDataMap(fn, xmlReader);
            } else if (elemName.equals("reference")) {
                tmp = readObjectDataReference(fn, xmlReader);
            }
            if (fn[0].equals(idField.getName())) {
                result.setId(tmp);
            } else {
                result.setProperty(fn[0], tmp);
            }
        }

        return result;
    }

    private Object readObjectDataArray(final String[] fieldName, final XMLStreamReader xmlReader) throws Exception {
        final int count = xmlReader.getAttributeCount();
        String className = null, length = null;
        for (int i = 0; i < count; ++i) {
            final String attName = xmlReader.getAttributeLocalName(i);
            final String attValue = xmlReader.getAttributeValue(i);

            if (attName.equals("name")) {
                fieldName[0] = attValue;
            } else if (attName.equals("class")) {
                className = attValue;
            } else if (attName.equals("length")) {
                length = attValue;
            }
        }

        Class<?> componentType;
        if ((componentType = getPrimitiveType(className)) == null) {
            componentType = Class.forName(className);
        }
        final Object array = Array.newInstance(componentType, Integer.parseInt(length));

        Object tmp = null;
        final String fn[] = new String[]{null};
        int i = 0;
        while (xmlReader.hasNext()) {
            final int type = xmlReader.nextTag();
            if (type == XMLStreamConstants.END_ELEMENT && xmlReader.getLocalName().equals("array"))
                break;
            if (type != XMLStreamConstants.START_ELEMENT)
                continue;

            final String elemName = xmlReader.getLocalName();
            if (elemName.equals("object")) {
                tmp = readObjectData(fn, xmlReader);
            } else if (elemName.equals("array")) {
                tmp = readObjectDataArray(fn, xmlReader);
            } else if (elemName.equals("primitive")) {
                tmp = readPrimitive(fn, xmlReader);
            } else if (elemName.equals("enum")) {
                tmp = readEnum(fn, xmlReader);
            } else if (elemName.equals("collection")) {
                tmp = readObjectDataCollection(fn, xmlReader);
            } else if (elemName.equals("map")) {
                tmp = readObjectDataMap(fn, xmlReader);
            } else if (elemName.equals("reference")) {
                tmp = readObjectDataReference(fn, xmlReader);
            }
            Array.set(array, i, tmp);
            ++i;
        }

        return array;
    }

    private Object readObjectDataCollection(final String[] fieldName, final XMLStreamReader xmlReader) throws Exception {
        final int count = xmlReader.getAttributeCount();
        String className = null;
        for (int i = 0; i < count; ++i) {
            final String attName = xmlReader.getAttributeLocalName(i);
            final String attValue = xmlReader.getAttributeValue(i);

            if (attName.equals("name")) {
                fieldName[0] = attValue;
            } else if (attName.equals("class")) {
                className = attValue;
            }
        }

        Class<?> cl = Class.forName(className);
        Collection<Object> collection;
        try {
            collection = (Collection<Object>) cl.newInstance();
        } catch (final Exception e) {
            // R-43: неинстанцируемый класс коллекции (обертка java.util.Collections$SynchronizedList
            // и т.п.) — канонический фолбэк LinkedList; владелец-нода сама оборачивает списки
            // в synchronized-обертки в setKeys/setObjects/setChildren.
            collection = new java.util.LinkedList<>();
        }
        final String[] fn = new String[]{null};
        while (xmlReader.hasNext()) {
            final int type = xmlReader.nextTag();
            if (type == XMLStreamConstants.END_ELEMENT && xmlReader.getLocalName().equals("collection"))
                break;
            if (type != XMLStreamConstants.START_ELEMENT)
                continue;

            final String elemName = xmlReader.getLocalName();
            Object tmp = null;
            if (elemName.equals("object")) {
                tmp = readObjectData(fn, xmlReader);
            } else if (elemName.equals("array")) {
                tmp = readObjectDataArray(fn, xmlReader);
            } else if (elemName.equals("enum")) {
                tmp = readEnum(fn, xmlReader);
            } else if (elemName.equals("collection")) {
                tmp = readObjectDataCollection(fn, xmlReader);
            } else if (elemName.equals("map")) {
                tmp = readObjectDataMap(fn, xmlReader);
            } else if (elemName.equals("reference")) {
                tmp = readObjectDataReference(fn, xmlReader);
            }
            collection.add(tmp);
        }
        return collection;
    }

    private Object readObjectDataMap(final String[] fieldName, final XMLStreamReader xmlReader) throws Exception {
        final int count = xmlReader.getAttributeCount();
        String className = null;
        for (int i = 0; i < count; ++i) {
            final String attName = xmlReader.getAttributeLocalName(i);
            final String attValue = xmlReader.getAttributeValue(i);

            if (attName.equals("name")) {
                fieldName[0] = attValue;
            } else if (attName.equals("class")) {
                className = attValue;
            }
        }

        Class<?> cl = Class.forName(className);
        Map<Object, Object> map;
        try {
            map = (Map<Object, Object>) cl.newInstance();
        } catch (final Exception e) {
            // R-43: неинстанцируемый класс карты (обертка Collections$SynchronizedMap и т.п.)
            map = new java.util.HashMap<>();
        }
        final String[] fn = new String[]{null};
        while (xmlReader.hasNext()) {
            final int type = xmlReader.nextTag();
            if (type == XMLStreamConstants.END_ELEMENT && xmlReader.getLocalName().equals("map"))
                break;
            if (type != XMLStreamConstants.START_ELEMENT || !xmlReader.getLocalName().equals("entry"))
                continue;

            xmlReader.nextTag();
            String elemName = xmlReader.getLocalName();
            Object key = null;
            if (elemName.equals("object")) {
                key = readObjectData(fn, xmlReader);
            } else if (elemName.equals("array")) {
                key = readObjectDataArray(fn, xmlReader);
            } else if (elemName.equals("enum")) {
                key = readEnum(fn, xmlReader);
            } else if (elemName.equals("collection")) {
                key = readObjectDataCollection(fn, xmlReader);
            } else if (elemName.equals("map")) {
                key = readObjectDataMap(fn, xmlReader);
            } else if (elemName.equals("reference")) {
                key = readObjectDataReference(fn, xmlReader);
            }

            xmlReader.nextTag();
            elemName = xmlReader.getLocalName();
            Object value = null;
            if (elemName.equals("object")) {
                value = readObjectData(fn, xmlReader);
            } else if (elemName.equals("array")) {
                value = readObjectDataArray(fn, xmlReader);
            } else if (elemName.equals("enum")) {
                value = readEnum(fn, xmlReader);
            } else if (elemName.equals("collection")) {
                value = readObjectDataCollection(fn, xmlReader);
            } else if (elemName.equals("map")) {
                value = readObjectDataMap(fn, xmlReader);
            } else if (elemName.equals("reference")) {
                value = readObjectDataReference(fn, xmlReader);
            }
            map.put(key, value);
        }
        return map;
    }

    private Object readObjectDataReference(final String fieldName[], final XMLStreamReader xmlReader) throws InstantiationException, IllegalAccessException, ClassNotFoundException,
            NoSuchMethodException, SecurityException, IllegalArgumentException, InvocationTargetException {
        final int count = xmlReader.getAttributeCount();
        String className = null, idValue = null, classObjectId = null;
        for (int i = 0; i < count; ++i) {
            final String attName = xmlReader.getAttributeLocalName(i);
            final String attValue = xmlReader.getAttributeValue(i);
            if (attName.equals("name")) {
                fieldName[0] = attValue;
            } else if (attName.equals("class")) {
                className = attValue;
            } else if (attName.equals("dataStorageId")) {
                idValue = attValue;
            } else if (attName.equals("classObjectId")) {
                classObjectId = attValue;
            } else if (attName.equals("objectId")) {
                idValue = attValue;
            }
        }

        final Class<?> idClass = Class.forName(classObjectId);
        final Constructor<?> constructor = idClass.getConstructor(new Class<?>[]{String.class});
        final Object objectId = constructor.newInstance(new Object[]{idValue});

        final XdStorageIdentifiableObject result = new XdStorageIdentifiableObject();
        result.setType(Class.forName(className));
        result.setId(objectId);

        return result;
    }
}
