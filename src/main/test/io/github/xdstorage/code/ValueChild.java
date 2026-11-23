package io.github.xdstorage.code;

import io.github.xdstorage.XdStoragePolicy;
import io.github.xdstorage.annotations.XdStorageObjectPolicy;
import io.github.xdstorage.annotations.XdStorageObjectId;

/**
 * Fixture for the wrapper JavaBeans-contract tests: a plain child bean
 * with identity equals (no equals/hashCode override) like the domain entities.
 * The class-objects policy lets the same bean act as a persisted child in the
 * real-storage lazy-load tests (docs/tasks/2.2.0/35).
 */
@XdStorageObjectPolicy(policy = XdStoragePolicy.StoreAsClassObjects)
public class ValueChild {

    @XdStorageObjectId
    private Long id;
    private String label;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }

    @Override
    public String toString() {
        return "ValueChild{id=" + id + ", label='" + label + "'}";
    }
}
