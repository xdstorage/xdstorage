package io.github.xdstorage.entities.foreignkeys.index.btree.manual;

import io.github.xdstorage.XdStoragePolicy;
import io.github.xdstorage.annotations.XdStorageObjectId;
import io.github.xdstorage.annotations.XdStorageObjectPolicy;
import io.github.xdstorage.annotations.XdStorageParentObject;

@XdStorageObjectPolicy(policy = XdStoragePolicy.StoreAsClassObjects)
public class BTreeIndexChildManualId {
    @XdStorageObjectId
    private Long id;

    @XdStorageParentObject
    private BTreeIndexParentManualId parent;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public BTreeIndexParentManualId getParent() {
        return parent;
    }

    public void setParent(BTreeIndexParentManualId parent) {
        this.parent = parent;
    }
}
