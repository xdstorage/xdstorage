package io.github.xdstorage.search;

import io.github.xdstorage.resource.IXdStorageResourceObject;

/** The resource-object face of one persisted search index: the type-safe token the
 * resources manager hands out for a locked index resource. Adds no members of its own —
 * the lifecycle surface comes from the resource-object contract and the operational
 * surface from {@link IXdStorageSearchIndexDaoResource}, the DAO face it exposes via
 * {@code getDao()}.
 *
 * <p>Engine-internal — not part of the public API. Locking and concurrency are owned by
 * the resources manager that issued the resource. */
public interface IXdStorageSearchIndexResourceObject extends IXdStorageResourceObject<IXdStorageSearchIndexDaoResource>
        {

}
