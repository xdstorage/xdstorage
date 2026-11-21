package io.github.xdstorage.performance.entities;

import io.github.xdstorage.XdStoragePolicy;
import io.github.xdstorage.annotations.XdStorageObjectId;
import io.github.xdstorage.annotations.XdStorageObjectPolicy;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * FKBENCH (docs/tasks/2.1.0/50): the CONTROL arm — the SAME graph shape as
 * {@link FkBenchParent}/{@link FkBenchChild} but with NO FK/AutoSave annotations:
 * no cascade (the children saved explicitly by the harness), no FK relation entries.
 * The delta fk_off − plain isolates the @XdStorageAutoSave cascade cost from the
 * FK-engine cost (fk_on − fk_off).
 */
@XdStorageObjectPolicy(policy = XdStoragePolicy.StoreAsClassObjects)
public class FkPlainParent {

    @XdStorageObjectId
    private String id;

    private String label;

    private Collection<FkPlainChild> children = new ArrayList<>();

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

    public Collection<FkPlainChild> getChildren() {
        return children;
    }

    public void setChildren(Collection<FkPlainChild> children) {
        this.children = (children instanceof List) ? (List<FkPlainChild>) children : new ArrayList<>(children);
    }

    public void addChild(FkPlainChild child) {
        this.children.add(child);
    }
}
