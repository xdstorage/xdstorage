package io.github.xdstorage.entities.foreignkeys.index.btree;

import io.github.xdstorage.XdStoragePolicy;
import io.github.xdstorage.annotations.*;
import io.github.xdstorage.idgeneration.XdStorageIdGeneratorType;
import io.github.xdstorage.index.XdStorageIndexType;

import java.util.ArrayList;
import java.util.Collection;

@XdStorageObjectPolicy(policy = XdStoragePolicy.StoreAsClassObjects)
@XdStorageObjectIdIndexType(indexType = XdStorageIndexType.BTree, t = 64)
public class BTreeIndexParentAutoId {

    @XdStorageObjectId
    @XdStorageObjectFieldProperties(idGeneratorType = XdStorageIdGeneratorType.DATABASE_GENERATOR)
    private Long id;

    @XdStorageAutoSave
    private Collection<BTreeIndexChildAutoId> children = new ArrayList<>();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Collection<BTreeIndexChildAutoId> getChildren() { return children; }
    public void addChild(BTreeIndexChildAutoId child) { this.children.add(child); }
    public void setChildren(Collection<BTreeIndexChildAutoId> children) { this.children = children; }
}
