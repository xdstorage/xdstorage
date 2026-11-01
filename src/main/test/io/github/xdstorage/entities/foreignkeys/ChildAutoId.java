package io.github.xdstorage.entities.foreignkeys;

import io.github.xdstorage.XdStoragePolicy;
import io.github.xdstorage.annotations.XdStorageObjectId;
import io.github.xdstorage.annotations.XdStorageObjectFieldProperties;
import io.github.xdstorage.annotations.XdStorageObjectPolicy;
import io.github.xdstorage.annotations.XdStorageParentObject;
import io.github.xdstorage.idgeneration.XdStorageIdGeneratorType;

@XdStorageObjectPolicy(policy = XdStoragePolicy.StoreAsClassObjects)
public class ChildAutoId {

    @XdStorageObjectId
    @XdStorageObjectFieldProperties(idGeneratorType = XdStorageIdGeneratorType.DATABASE_GENERATOR)
    private Long id;

    @XdStorageParentObject
    private ParentAutoId parent;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public ParentAutoId getParent() { return parent; }
    public void setParent(ParentAutoId parent) { this.parent = parent; }
}
