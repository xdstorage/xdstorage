package io.github.xdstorage.entities.foreignkeys;

import io.github.xdstorage.XdStoragePolicy;
import io.github.xdstorage.annotations.XdStorageAutoSave;
import io.github.xdstorage.annotations.XdStorageObjectId;
import io.github.xdstorage.annotations.XdStorageObjectPolicy;

import java.util.ArrayList;
import java.util.Collection;

@XdStorageObjectPolicy(policy = XdStoragePolicy.StoreAsClassObjects)
public class Parent {
    @XdStorageObjectId
    private String id;

    @XdStorageAutoSave
    private Collection<Child> children = new ArrayList<>();

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public Collection<Child> getChildren() {
        return children;
    }

    public void addChild(Child child) {
        this.children.add(child);
    }

    public void setChildren(Collection<Child> children) {
        this.children = children;
    }
}
