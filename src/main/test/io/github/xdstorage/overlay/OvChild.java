package io.github.xdstorage.overlay;

import io.github.xdstorage.XdStoragePolicy;
import io.github.xdstorage.annotations.XdStorageObjectId;
import io.github.xdstorage.annotations.XdStorageObjectIdIndexType;
import io.github.xdstorage.annotations.XdStorageObjectPolicy;
import io.github.xdstorage.index.XdStorageIndexType;

/** The TREEOVERLAY test child entity — TOP-LEVEL (see OvParent). */
@XdStorageObjectPolicy(policy = XdStoragePolicy.StoreAsClassObjects)
@XdStorageObjectIdIndexType(indexType = XdStorageIndexType.BTree, t = 4)
public class OvChild {

    @XdStorageObjectId
    public String id;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
}
