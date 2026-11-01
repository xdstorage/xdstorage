package io.github.xdstorage.performance.entities;

import io.github.xdstorage.XdStoragePolicy;
import io.github.xdstorage.annotations.XdStorageObjectId;
import io.github.xdstorage.annotations.XdStorageObjectPolicy;
import io.github.xdstorage.annotations.XdStorageObjectSearchIndex;
import io.github.xdstorage.annotations.XdStorageParentObject;

@XdStorageObjectPolicy(policy = XdStoragePolicy.StoreWithParentObject)
@XdStorageObjectSearchIndex(indexName = "test_star_index", indexFieldNames = {"name"}, t = 50)
public class XdStarAutoSave {

    @XdStorageObjectId
    private String id;

    private String name;

    @XdStorageParentObject
    private XdStarSystemAutoSave parent;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public XdStarSystemAutoSave getParent() {
        return parent;
    }

    public void setParent(XdStarSystemAutoSave parent) {
        this.parent = parent;
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder("XdStar { ");
        sb.append(" id: ").append(id);
        sb.append(", name: ").append(name);
        sb.append(" }");
        return sb.toString();
    }
}
