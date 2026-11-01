package io.github.xdstorage.resource;

import io.github.xdstorage.XdStoragePolicy;
import io.github.xdstorage.exceptions.XdStorageException;

/**
 * Maps engine entities to their on-disk resource names: the relative file path of the data
 * resource holding an entity's records (policy-dependent — class-shared fragments or one file
 * per object), of an index's resource, and of the class's structure record. Implementations
 * must be stateless and thread-safe. Engine-internal — not part of the public API.
 */
public interface IXdStorageResourceNamingService {

    /**
     * The data resource id for the given entity under the given storage policy: the class's
     * fragment file for {@code StoreAsClassObjects}, the object's individual file for
     * {@code StoreAsSingleObject} (resolved from the object's id or, for a new object,
     * from the passed fragment/objectId hint).
     *
     * @param <T>       the entity class
     * @param policy    the storage policy of the entity class
     * @param cl        the entity class
     * @param object    the entity instance whose id names the file, or {@code null} to
     *                  resolve from {@code objectId} alone
     * @param objectId  the object id (or fragment key) to name the file by when
     *                  {@code object} is {@code null}
     * @return the resource id (a relative file path), or {@code null} when the policy has
     *         no data resource of its own
     * @throws XdStorageException when the entity metadata cannot be resolved
     */
    <T> Object getResourceId(XdStoragePolicy policy, Class<?> cl, T object, Object objectId) throws XdStorageException;

    /**
     * The resource id of a named index of the entity class (its dedicated file under the
     * class's data directory).
     *
     * @param indexName the index name
     * @param policy    the storage policy of the entity class
     * @param cl        the entity class
     * @return the index resource id (a relative file path)
     */
    Object getIndexResourceId(String indexName, XdStoragePolicy policy, Class<?> cl);

    /**
     * The resource id of the entity class's structure record (the class metadata file).
     *
     * @param cl the entity class
     * @return the structure resource id (a relative file path)
     */
    Object getStructureResourceId(Class<?> cl);

    Object getForeignKeyResourceId(String suffix, Class<?> parentClass, Class<?> childClass, String parentClassField);
}
