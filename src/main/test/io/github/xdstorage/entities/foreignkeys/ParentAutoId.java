package io.github.xdstorage.entities.foreignkeys;

import io.github.xdstorage.XdStoragePolicy;
import io.github.xdstorage.annotations.XdStorageAutoSave;
import io.github.xdstorage.annotations.XdStorageObjectId;
import io.github.xdstorage.annotations.XdStorageObjectFieldProperties;
import io.github.xdstorage.annotations.XdStorageObjectPolicy;
import io.github.xdstorage.idgeneration.XdStorageIdGeneratorType;

import java.util.ArrayList;
import java.util.Collection;

@XdStorageObjectPolicy(policy = XdStoragePolicy.StoreAsClassObjects)
public class ParentAutoId {

    @XdStorageObjectId
    @XdStorageObjectFieldProperties(idGeneratorType = XdStorageIdGeneratorType.DATABASE_GENERATOR)
    private Long id;

    @XdStorageAutoSave
    private Collection<ChildAutoId> children = new ArrayList<>();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Collection<ChildAutoId> getChildren() { return children; }
    public void addChild(ChildAutoId child) { this.children.add(child); }
    public void setChildren(Collection<ChildAutoId> children) { this.children = children; }
}
