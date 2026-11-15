package io.github.xdstorage.entities.foreignkeys.policies.hash.ParentClassChildSingleObject;

import io.github.xdstorage.XdStoragePolicy;
import io.github.xdstorage.annotations.XdStorageObjectId;
import io.github.xdstorage.annotations.XdStorageObjectPolicy;
import io.github.xdstorage.annotations.XdStorageParentObject;

@XdStorageObjectPolicy(policy = XdStoragePolicy.StoreAsSingleObject)
public class HashPolicyChildSingleObject {

    @XdStorageObjectId
    private Long id;

    @XdStorageParentObject
    private HashPolicyParentClassChildSingleObject parent;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public HashPolicyParentClassChildSingleObject getParent() { return parent; }
    public void setParent(HashPolicyParentClassChildSingleObject parent) { this.parent = parent; }
}
