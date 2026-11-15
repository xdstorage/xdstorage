package io.github.xdstorage.entities.foreignkeys.policies.hash.ParentSingleObjectChildClass;

import io.github.xdstorage.XdStoragePolicy;
import io.github.xdstorage.annotations.XdStorageAutoSave;
import io.github.xdstorage.annotations.XdStorageObjectId;
import io.github.xdstorage.annotations.XdStorageObjectPolicy;

import java.util.ArrayList;
import java.util.Collection;

@XdStorageObjectPolicy(policy = XdStoragePolicy.StoreAsSingleObject)
public class HashPolicyParentSingleObjectChildClass {

    @XdStorageObjectId
    private Long id;

    @XdStorageAutoSave
    private Collection<HashPolicyChildClass> children = new ArrayList<>();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Collection<HashPolicyChildClass> getChildren() { return children; }
    public void addChild(HashPolicyChildClass child) { this.children.add(child); }
    public void setChildren(Collection<HashPolicyChildClass> children) { this.children = children; }
}
