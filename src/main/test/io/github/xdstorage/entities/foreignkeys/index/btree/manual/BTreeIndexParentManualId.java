package io.github.xdstorage.entities.foreignkeys.index.btree.manual;

import io.github.xdstorage.XdStoragePolicy;
import io.github.xdstorage.annotations.XdStorageAutoSave;
import io.github.xdstorage.annotations.XdStorageObjectId;
import io.github.xdstorage.annotations.XdStorageObjectIdIndexType;
import io.github.xdstorage.annotations.XdStorageObjectPolicy;
import io.github.xdstorage.index.XdStorageIndexType;

import java.util.ArrayList;
import java.util.Collection;

@XdStorageObjectPolicy(policy = XdStoragePolicy.StoreAsClassObjects)
@XdStorageObjectIdIndexType(indexType = XdStorageIndexType.BTree)
public class BTreeIndexParentManualId {
    @XdStorageObjectId
    private Long id;

    @XdStorageAutoSave
    private Collection<BTreeIndexChildManualId> children = new ArrayList<>();

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Collection<BTreeIndexChildManualId> getChildren() {
        return children;
    }

    public void addChild(BTreeIndexChildManualId child) {
        this.children.add(child);
    }

    public void setChildren(Collection<BTreeIndexChildManualId> children) {
        this.children = children;
    }
}
