package io.github.xdstorage.entities.foreignkeys.index.hash;

import io.github.xdstorage.XdStoragePolicy;
import io.github.xdstorage.annotations.XdStorageObjectId;
import io.github.xdstorage.annotations.XdStorageObjectFieldProperties;
import io.github.xdstorage.annotations.XdStorageObjectPolicy;
import io.github.xdstorage.annotations.XdStorageParentObject;
import io.github.xdstorage.idgeneration.XdStorageIdGeneratorType;

@XdStorageObjectPolicy(policy = XdStoragePolicy.StoreAsClassObjects)
public class HashIndexChildAutoId {

    @XdStorageObjectId
    @XdStorageObjectFieldProperties(idGeneratorType = XdStorageIdGeneratorType.DATABASE_GENERATOR)
    private Long id;

    @XdStorageParentObject
    private HashIndexParentAutoId parent;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public HashIndexParentAutoId getParent() { return parent; }
    public void setParent(HashIndexParentAutoId parent) { this.parent = parent; }
}
