package io.github.xdstorage.serialization.yaml;

import org.slf4j.LoggerFactory;
import org.slf4j.Logger;
import io.github.xdstorage.exceptions.XdStorageException;
import io.github.xdstorage.exceptions.XdStorageIOException;
import io.github.xdstorage.helpers.IXdStorageSimpleTypeHelper;
import io.github.xdstorage.object.XdStorageIdentifiableObject;
import io.github.xdstorage.serialization.IXdStorageObjectsReader;
import io.github.xdstorage.transaction.IXdStorageTransaction;
import io.github.xdstorage.utils.XdStorageObjectIdField;

import java.io.Reader;
import java.util.ArrayList;
import java.util.Collection;

/**
 * The YAML implementation of {@link IXdStorageObjectsReader}: the top-level
 * transactional demarshalling dispatcher. Tokenizes the resource file and delegates the
 * assembly of each block to the specialized components
 * ({@link XdStorageYamlBlockObjectsReader}, {@link XdStorageYamlBlockContainersReader}).
 *
 * <p>S-1 (truncation detection): in strict mode ({@code strictEof = true}) a file
 * without the end-of-file sentinel {@link XdStorageYamlTokenizer#EOF_SENTINEL_LINE} is
 * rejected — a truncated resource is never silently served as a partial database. The
 * engine enables strict mode by default (see {@code XdStorageConfig.acceptMissingEofSentinel}
 * for migrating legacy storages). Instances built through the one-argument constructor
 * are non-strict (legacy fixtures without a sentinel keep reading as before).</p>
 *
 * <p>Thread-safety: instances are confined to one worker thread (handed out per thread
 * by {@link XdStorageYamlIOFactory}); the held references are immutable. Engine-internal
 * — not part of the public API.</p>
 */
public class XdStorageYamlObjectsReader implements IXdStorageObjectsReader {

    private static final Logger log = LoggerFactory.getLogger(XdStorageYamlObjectsReader.class);

    private final IXdStorageSimpleTypeHelper simpleTypeHelper;
    private final boolean strictEof;

    /**
     * Creates a non-strict reader: a resource file missing the S-1 EOF sentinel is
     * accepted with a WARN instead of a load failure (legacy compatibility mode).
     *
     * @param simpleTypeHelper the simple-type SPI used to restore scalar attribute values
     */
    public XdStorageYamlObjectsReader(final IXdStorageSimpleTypeHelper simpleTypeHelper) {
        this(simpleTypeHelper, false);
    }

    /**
     * Creates a reader with an explicit S-1 truncation-detection policy.
     *
     * @param simpleTypeHelper the simple-type SPI used to restore scalar attribute values
     * @param strictEof {@code true} to reject a resource file missing the EOF sentinel
     *                  with {@link XdStorageIOException}; {@code false} to accept it with a WARN
     */
    public XdStorageYamlObjectsReader(final IXdStorageSimpleTypeHelper simpleTypeHelper, final boolean strictEof) {
        this.simpleTypeHelper = simpleTypeHelper;
        this.strictEof = strictEof;
    }

    /**
     * Reads an {@code objects} root block: restores each {@code - object} list entry as a
     * live instance of its class. An empty (0-byte) stream yields an empty collection; a
     * non-empty file whose root block is missing or unrecognized is checked for the EOF
     * sentinel per this reader's strictness before an empty result is returned.
     *
     * @param reader the character stream of the resource file
     * @return the restored objects, in file order
     * @throws XdStorageIOException when the stream cannot be parsed, or in strict mode
     *         when the EOF sentinel is missing (truncated file)
     */
    public Collection<Object> readObjects(final Reader reader) throws XdStorageIOException {
        Collection<Object> result = new ArrayList<>();
        try {
            XdStorageYamlTokenizer tokenizer = new XdStorageYamlTokenizer(reader);

            YamlLineToken rootToken = tokenizer.nextToken();
            if (rootToken == null || !"objects".equals(rootToken.key)) {
                // пустой файл (0 байт) = пустой ресурс; корневой блок без содержимого
                // и без сентинела отсекается verifyCleanEof ниже
                if (rootToken == null) {
                    return result;
                }
                verifyCleanEof(tokenizer, 0, "objects");
                return result;
            }

            YamlLineToken token;
            while ((token = tokenizer.peekToken()) != null) {
                if (token.level <= 0) {
                    break;
                }

                token = tokenizer.nextToken();
                if (token.level == 1 && token.isListItem && "object".equals(token.key)) {
                    Object tmp = XdStorageYamlBlockObjectsReader.readObject(tokenizer, token.level, simpleTypeHelper);
                    if (tmp != null) {
                        result.add(tmp);
                    }
                }
            }
            verifyCleanEof(tokenizer, result.size(), "objects");
        } catch (final Throwable cause) {
            throw new XdStorageIOException(cause);
        }
        return result;
    }

    @Override
    public Collection<Object> read(final Reader reader) throws XdStorageIOException, XdStorageException {
        return readObjects(reader);
    }

    @Override
    public Collection<Object> read(final Reader reader, final IXdStorageTransaction transaction) throws
            XdStorageIOException {
        return readObjects(reader);
    }

    @Override
    public Collection<Object> readReferences(final Reader reader, final XdStorageObjectIdField field) throws
            XdStorageIOException {
        Collection<Object> result = new ArrayList<>();
        try {
            XdStorageYamlTokenizer tokenizer = new XdStorageYamlTokenizer(reader);

            YamlLineToken rootToken = tokenizer.nextToken();
            if (rootToken == null || !"references".equals(rootToken.key)) {
                if (rootToken == null) {
                    return result;
                }
                verifyCleanEof(tokenizer, 0, "references");
                return result;
            }

            YamlLineToken token;
            while ((token = tokenizer.peekToken()) != null) {
                if (token.level <= 0) {
                    break;
                }

                token = tokenizer.nextToken();
                if (token.level == 1 && token.isListItem && "reference".equals(token.key)) {
                    Object tmp = XdStorageYamlBlockObjectsReader.readReference(tokenizer, token.level, field,
                            simpleTypeHelper);
                    if (tmp != null) {
                        result.add(tmp);
                    }
                }
            }
            verifyCleanEof(tokenizer, result.size(), "references");
        } catch (final Throwable cause) {
            throw new XdStorageIOException(cause);
        }
        return result;
    }

    @Override
    public Collection<XdStorageIdentifiableObject> readData(final Reader reader, final XdStorageObjectIdField field)
            throws XdStorageIOException, XdStorageException {
        Collection<XdStorageIdentifiableObject> result = new ArrayList<>();
        try {
            XdStorageYamlTokenizer tokenizer = new XdStorageYamlTokenizer(reader);
            YamlLineToken token;
            boolean sentinelSeen = false;

            while ((token = tokenizer.nextToken()) != null) {
                if (XdStorageYamlTokenizer.EOF_SENTINEL_KEY.equals(token.key)) {
                    sentinelSeen = true;
                    break;
                }
                if (token.level == 1) {
                    if ("object".equals(token.key)) {
                        result.add((XdStorageIdentifiableObject) XdStorageYamlBlockObjectsReader
                                .readObjectData(tokenizer, token.level, simpleTypeHelper));
                    } else if ("reference".equals(token.key)) {
                        result.add((XdStorageIdentifiableObject) XdStorageYamlBlockObjectsReader
                                .readObjectDataReference(tokenizer, token.level));
                    }
                }
            }

            if (!sentinelSeen) {
                if (strictEof) {
                    throw new XdStorageIOException(truncationMessage(result.size(), "data"));
                }
                log.warn("resource file has no EOF sentinel after {} data record(s) — legacy file, accepted as " +
                        "complete "
                        + "(enable strictness by rewriting the storage with a current engine version)", result.size());
            }
        } catch (final Throwable cause) {
            throw new XdStorageIOException(cause);
        }
        return result;
    }

    /**
     * S-1: главный цикл readObjects/readReferences останавливается на токене уровня <= 0
     * (который остался в буфере заглядывания) либо на EOF. Потребляем его и требуем сентинел.
     */
    private void verifyCleanEof(final XdStorageYamlTokenizer tokenizer, final int objectsRead, final String root)
            throws XdStorageIOException, java.io.IOException {
        final YamlLineToken trailing = tokenizer.nextToken();
        final boolean clean = trailing != null && XdStorageYamlTokenizer.EOF_SENTINEL_KEY.equals(trailing.key);
        if (!clean) {
            if (strictEof) {
                throw new XdStorageIOException(truncationMessage(objectsRead, root)
                        + (trailing != null ? " (unexpected trailing token: " + trailing + ")" : ""));
            }
            log.warn("resource file has no EOF sentinel after {} object(s) in '{}' block — legacy file, accepted as " +
                    "complete",
                    objectsRead, root);
        }
    }

    private String truncationMessage(final int objectsRead, final String root) {
        return "resource file is TRUNCATED or corrupted: no EOF sentinel after " + objectsRead
                + " record(s) in '" + root + "' block — refusing to serve a partial database "
                + "(the file was likely cut by a crash or an external process)";
    }
}
