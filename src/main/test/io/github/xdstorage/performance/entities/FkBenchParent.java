package io.github.xdstorage.performance.entities;

import io.github.xdstorage.XdStoragePolicy;
import io.github.xdstorage.annotations.XdStorageAutoSave;
import io.github.xdstorage.annotations.XdStorageObjectId;
import io.github.xdstorage.annotations.XdStorageObjectPolicy;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * FKBENCH (docs/tasks/2.1.0/50): the FK-bearing parent entity — the canonical FK shape
 * (the task-13 entity discipline: the JavaBeans contract, the full getter/setter pairs).
 * The {@code @XdStorageAutoSave} collection makes save(parent) cascade the children, and
 * the FK engine maintains the front + reverse relation entries for every child.
 */
@XdStorageObjectPolicy(policy = XdStoragePolicy.StoreAsClassObjects)
public class FkBenchParent {

    @XdStorageObjectId
    private String id;

    private String label;

    @XdStorageAutoSave
    private Collection<FkBenchChild> children = new ArrayList<>();

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

    public Collection<FkBenchChild> getChildren() {
        return children;
    }

    public void setChildren(Collection<FkBenchChild> children) {
        this.children = (children instanceof List) ? (List<FkBenchChild>) children : new ArrayList<>(children);
    }

    public void addChild(FkBenchChild child) {
        this.children.add(child);
    }
}
