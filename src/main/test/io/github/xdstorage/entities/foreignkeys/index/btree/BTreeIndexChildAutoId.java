package io.github.xdstorage.entities.foreignkeys.index.btree;

import io.github.xdstorage.XdStoragePolicy;
import io.github.xdstorage.annotations.*;
import io.github.xdstorage.idgeneration.XdStorageIdGeneratorType;
import io.github.xdstorage.index.XdStorageIndexType;

@XdStorageObjectPolicy(policy = XdStoragePolicy.StoreAsClassObjects)
@XdStorageObjectIdIndexType(indexType = XdStorageIndexType.BTree, t = 64)
public class BTreeIndexChildAutoId {

    @XdStorageObjectId
    @XdStorageObjectFieldProperties(idGeneratorType = XdStorageIdGeneratorType.DATABASE_GENERATOR)
    private Long id;

    @XdStorageParentObject
    private BTreeIndexParentAutoId parent;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public BTreeIndexParentAutoId getParent() { return parent; }
    public void setParent(BTreeIndexParentAutoId parent) { this.parent = parent; }
}
