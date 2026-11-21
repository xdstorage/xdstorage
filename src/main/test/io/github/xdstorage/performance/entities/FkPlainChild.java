package io.github.xdstorage.performance.entities;

import io.github.xdstorage.XdStoragePolicy;
import io.github.xdstorage.annotations.XdStorageObjectId;
import io.github.xdstorage.annotations.XdStorageObjectPolicy;

/**
 * FKBENCH (docs/tasks/2.1.0/50): the control-arm child — the same fields as
 * {@link FkBenchChild} minus the {@code @XdStorageParentObject} back-reference.
 */
@XdStorageObjectPolicy(policy = XdStoragePolicy.StoreAsClassObjects)
public class FkPlainChild {

    @XdStorageObjectId
    private String id;

    private String label;

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
}
