package io.github.xdstorage.entities;

import io.github.xdstorage.XdStoragePolicy;
import io.github.xdstorage.annotations.XdStorageObjectId;
import io.github.xdstorage.annotations.XdStorageObjectPolicy;
import io.github.xdstorage.annotations.XdStorageObjectSearchIndex;

/**
 * A java.util.UUID field behind a search index — for the pins that an indexed java.time / UUID field
 * is queried by value, not by its text form (task 41).
 */
@XdStorageObjectPolicy(policy = XdStoragePolicy.StoreAsClassObjects)
@XdStorageObjectSearchIndex(indexName = "by_code", indexFieldNames = {"code"})
public class XdStorageIndexedUuidEntity {

    @XdStorageObjectId
    private String id;

    private java.util.UUID code;

    public XdStorageIndexedUuidEntity() {
    }

    public XdStorageIndexedUuidEntity(final String id, final java.util.UUID code) {
        this.id = id;
        this.code = code;
    }

    public String getId() {
        return id;
    }

    public void setId(final String id) {
        this.id = id;
    }

    public java.util.UUID getCode() {
        return code;
    }

    public void setCode(final java.util.UUID code) {
        this.code = code;
    }
}
