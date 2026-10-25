package io.github.xdstorage;

import io.github.xdstorage.annotations.XdStorageObjectId;
import io.github.xdstorage.annotations.XdStorageObjectPolicy;

/**
 * Top-level entity for the SILENTFAIL multi-class compilation test (docs/tasks/2.1.0/35):
 * the runtime compiler cannot compile wrappers over NESTED classes (the EMBEDDING
 * contract), so the multi-class graph test needs a real top-level entity with a copy
 * constructor the generated wrappers can call.
 */
@XdStorageObjectPolicy(policy = XdStoragePolicy.StoreAsClassObjects)
public class XdStorageSilentFailMultiEntity {

    @XdStorageObjectId
    private Long id;
    private String value;

    public XdStorageSilentFailMultiEntity() {}

    public XdStorageSilentFailMultiEntity(final XdStorageSilentFailMultiEntity other) {
        this.id = other.id;
        this.value = other.value;
    }

    public Long getId() { return id; }
    public void setId(final Long id) { this.id = id; }
    public String getValue() { return value; }
    public void setValue(final String value) { this.value = value; }
}
