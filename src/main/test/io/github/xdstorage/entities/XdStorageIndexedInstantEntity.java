package io.github.xdstorage.entities;

import io.github.xdstorage.XdStoragePolicy;
import io.github.xdstorage.annotations.XdStorageObjectId;
import io.github.xdstorage.annotations.XdStorageObjectPolicy;
import io.github.xdstorage.annotations.XdStorageObjectSearchIndex;

/**
 * A java.time.Instant field behind a search index — for the pins that an indexed java.time / UUID field
 * is queried by value, not by its text form (task 41).
 */
@XdStorageObjectPolicy(policy = XdStoragePolicy.StoreAsClassObjects)
@XdStorageObjectSearchIndex(indexName = "by_at", indexFieldNames = {"at"})
public class XdStorageIndexedInstantEntity {

    @XdStorageObjectId
    private String id;

    private java.time.Instant at;

    public XdStorageIndexedInstantEntity() {
    }

    public XdStorageIndexedInstantEntity(final String id, final java.time.Instant at) {
        this.id = id;
        this.at = at;
    }

    public String getId() {
        return id;
    }

    public void setId(final String id) {
        this.id = id;
    }

    public java.time.Instant getAt() {
        return at;
    }

    public void setAt(final java.time.Instant at) {
        this.at = at;
    }
}
