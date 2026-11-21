package io.github.xdstorage.performance.entities;

import io.github.xdstorage.XdStoragePolicy;
import io.github.xdstorage.annotations.XdStorageObjectId;
import io.github.xdstorage.annotations.XdStorageObjectPolicy;
import io.github.xdstorage.annotations.XdStorageParentObject;

/**
 * FKBENCH (docs/tasks/2.1.0/50): the FK-bearing child entity — the
 * {@code @XdStorageParentObject} back-reference is what the FK reverse tree serves
 * (the fkLookup shape: load(child) → getParent()).
 */
@XdStorageObjectPolicy(policy = XdStoragePolicy.StoreAsClassObjects)
public class FkBenchChild {

    @XdStorageObjectId
    private String id;

    private String label;

    @XdStorageParentObject
    private FkBenchParent parent;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public FkBenchParent getParent() {
        return parent;
    }

    public void setParent(FkBenchParent parent) {
        this.parent = parent;
    }
}
