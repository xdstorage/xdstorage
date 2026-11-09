package io.github.xdstorage.entities.foreignkeys.index.hash;

import io.github.xdstorage.XdStoragePolicy;
import io.github.xdstorage.annotations.XdStorageAutoSave;
import io.github.xdstorage.annotations.XdStorageObjectId;
import io.github.xdstorage.annotations.XdStorageObjectFieldProperties;
import io.github.xdstorage.annotations.XdStorageObjectPolicy;
import io.github.xdstorage.idgeneration.XdStorageIdGeneratorType;

import java.util.ArrayList;
import java.util.Collection;

@XdStorageObjectPolicy(policy = XdStoragePolicy.StoreAsClassObjects)
public class HashIndexParentAutoId {

    @XdStorageObjectId
    @XdStorageObjectFieldProperties(idGeneratorType = XdStorageIdGeneratorType.DATABASE_GENERATOR)
    private Long id;

    @XdStorageAutoSave
    private Collection<HashIndexChildAutoId> children = new ArrayList<>();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Collection<HashIndexChildAutoId> getChildren() { return children; }
    public void addChild(HashIndexChildAutoId child) { this.children.add(child); }
    public void setChildren(Collection<HashIndexChildAutoId> children) { this.children = children; }
}
