package io.github.xdstorage.entities;

import io.github.xdstorage.XdStoragePolicy;
import io.github.xdstorage.annotations.XdStorageObjectId;
import io.github.xdstorage.annotations.XdStorageObjectPolicy;

/** An entity of the one-object-one-file policy, for the migration tool's tests (docs/tasks/2.2.0/17). */
@XdStorageObjectPolicy(policy = XdStoragePolicy.StoreAsSingleObject)
public class XdStorageMigrateNote {

    @XdStorageObjectId
    private String id;
    private String text;

    public XdStorageMigrateNote() {
    }

    public XdStorageMigrateNote(final String id, final String text) {
        this.id = id;
        this.text = text;
    }

    public String getId() {
        return id;
    }

    public void setId(final String id) {
        this.id = id;
    }

    public String getText() {
        return text;
    }

    public void setText(final String text) {
        this.text = text;
    }
}
