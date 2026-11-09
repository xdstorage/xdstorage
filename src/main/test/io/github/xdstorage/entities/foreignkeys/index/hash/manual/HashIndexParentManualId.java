package io.github.xdstorage.entities.foreignkeys.index.hash.manual;

import io.github.xdstorage.XdStoragePolicy;
import io.github.xdstorage.annotations.XdStorageAutoSave;
import io.github.xdstorage.annotations.XdStorageObjectId;
import io.github.xdstorage.annotations.XdStorageObjectIdIndexType;
import io.github.xdstorage.annotations.XdStorageObjectPolicy;
import io.github.xdstorage.index.XdStorageIndexType;

import java.util.ArrayList;
import java.util.Collection;

@XdStorageObjectPolicy(policy = XdStoragePolicy.StoreAsClassObjects)
@XdStorageObjectIdIndexType(indexType = XdStorageIndexType.Hash)
public class HashIndexParentManualId {
    @XdStorageObjectId
    private Long id;

    @XdStorageAutoSave
    private Collection<HashIndexChildManualId> children = new ArrayList<>();

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Collection<HashIndexChildManualId> getChildren() {
        return children;
    }

    public void addChild(HashIndexChildManualId child) {
        this.children.add(child);
    }

    public void setChildren(Collection<HashIndexChildManualId> children) {
        this.children = children;
    }
}
