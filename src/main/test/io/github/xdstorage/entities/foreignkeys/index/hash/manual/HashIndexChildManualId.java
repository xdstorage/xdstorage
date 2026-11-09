package io.github.xdstorage.entities.foreignkeys.index.hash.manual;

import io.github.xdstorage.XdStoragePolicy;
import io.github.xdstorage.annotations.XdStorageObjectId;
import io.github.xdstorage.annotations.XdStorageObjectIdIndexType;
import io.github.xdstorage.annotations.XdStorageObjectPolicy;
import io.github.xdstorage.annotations.XdStorageParentObject;
import io.github.xdstorage.index.XdStorageIndexType;

@XdStorageObjectPolicy(policy = XdStoragePolicy.StoreAsClassObjects)
@XdStorageObjectIdIndexType(indexType = XdStorageIndexType.Hash)
public class HashIndexChildManualId {
    @XdStorageObjectId
    private Long id;

    @XdStorageParentObject
    private HashIndexParentManualId parent;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public HashIndexParentManualId getParent() {
        return parent;
    }

    public void setParent(HashIndexParentManualId parent) {
        this.parent = parent;
    }
}
