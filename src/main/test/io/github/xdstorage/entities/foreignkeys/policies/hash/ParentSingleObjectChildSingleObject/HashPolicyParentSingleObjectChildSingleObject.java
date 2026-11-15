package io.github.xdstorage.entities.foreignkeys.policies.hash.ParentSingleObjectChildSingleObject;

import io.github.xdstorage.XdStoragePolicy;
import io.github.xdstorage.annotations.XdStorageAutoSave;
import io.github.xdstorage.annotations.XdStorageObjectId;
import io.github.xdstorage.annotations.XdStorageObjectPolicy;

import java.util.ArrayList;
import java.util.Collection;

@XdStorageObjectPolicy(policy = XdStoragePolicy.StoreAsSingleObject)
public class HashPolicyParentSingleObjectChildSingleObject {

    @XdStorageObjectId
    private Long id;

    @XdStorageAutoSave
    private Collection<HashPolicyChildSingleObject> children = new ArrayList<>();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Collection<HashPolicyChildSingleObject> getChildren() { return children; }
    public void addChild(HashPolicyChildSingleObject child) { this.children.add(child); }
    public void setChildren(Collection<HashPolicyChildSingleObject> children) { this.children = children; }
}
