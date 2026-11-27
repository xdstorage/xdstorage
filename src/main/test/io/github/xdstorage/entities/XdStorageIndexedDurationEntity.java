package io.github.xdstorage.entities;

import io.github.xdstorage.XdStoragePolicy;
import io.github.xdstorage.annotations.XdStorageObjectId;
import io.github.xdstorage.annotations.XdStorageObjectPolicy;
import io.github.xdstorage.annotations.XdStorageObjectSearchIndex;

/**
 * A java.time.Duration field behind a search index — for the pins that an indexed java.time / UUID field
 * is queried by value, not by its text form (task 41).
 */
@XdStorageObjectPolicy(policy = XdStoragePolicy.StoreAsClassObjects)
@XdStorageObjectSearchIndex(indexName = "by_duration", indexFieldNames = {"duration"})
public class XdStorageIndexedDurationEntity {

    @XdStorageObjectId
    private String id;

    private java.time.Duration duration;

    public XdStorageIndexedDurationEntity() {
    }

    public XdStorageIndexedDurationEntity(final String id, final java.time.Duration duration) {
        this.id = id;
        this.duration = duration;
    }

    public String getId() {
        return id;
    }

    public void setId(final String id) {
        this.id = id;
    }

    public java.time.Duration getDuration() {
        return duration;
    }

    public void setDuration(final java.time.Duration duration) {
        this.duration = duration;
    }
}
