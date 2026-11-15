package io.github.xdstorage.entities.foreignkeys.policies.btree.ParentClassChildClass;

import io.github.xdstorage.XdStoragePolicy;
import io.github.xdstorage.annotations.XdStorageObjectId;
import io.github.xdstorage.annotations.XdStorageObjectIdIndexType;
import io.github.xdstorage.annotations.XdStorageObjectFieldProperties;
import io.github.xdstorage.annotations.XdStorageObjectPolicy;
import io.github.xdstorage.annotations.XdStorageParentObject;
import io.github.xdstorage.idgeneration.XdStorageIdGeneratorType;
import io.github.xdstorage.index.XdStorageIndexType;

@XdStorageObjectPolicy(policy = XdStoragePolicy.StoreAsClassObjects)
@XdStorageObjectIdIndexType(indexType = XdStorageIndexType.BTree, t = 64)
public class PolicyChildClass {

    @XdStorageObjectId
    @XdStorageObjectFieldProperties(idGeneratorType = XdStorageIdGeneratorType.DATABASE_GENERATOR)
    private Long id;

    @XdStorageParentObject
    private PolicyParentClassChildClass parent;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public PolicyParentClassChildClass getParent() { return parent; }
    public void setParent(PolicyParentClassChildClass parent) { this.parent = parent; }
}
