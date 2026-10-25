package io.github.xdstorage;

import io.github.xdstorage.annotations.XdStorageObjectId;
import io.github.xdstorage.annotations.XdStorageObjectPolicy;

/**
 * The second class of the SILENTFAIL multi-class compilation test (docs/tasks/2.1.0/35):
 * a real multi-class generation graph compiles wrappers for an entity AND its embedded
 * child in one shot — the shape under which the old entity-keyed result map lost classes.
 */
@XdStorageObjectPolicy(policy = XdStoragePolicy.StoreAsClassObjects)
public class XdStorageSilentFailChildEntity {

    @XdStorageObjectId
    private Long id;
    private String label;

    public XdStorageSilentFailChildEntity() {}

    public XdStorageSilentFailChildEntity(final XdStorageSilentFailChildEntity other) {
        this.id = other.id;
        this.label = other.label;
    }

    public Long getId() { return id; }
    public void setId(final Long id) { this.id = id; }
    public String getLabel() { return label; }
    public void setLabel(final String label) { this.label = label; }
}
