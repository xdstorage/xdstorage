package io.github.xdstorage.entities.foreignkeys.policies.hash.ParentSingleObjectChildSingleObject;

import io.github.xdstorage.XdStoragePolicy;
import io.github.xdstorage.annotations.XdStorageObjectId;
import io.github.xdstorage.annotations.XdStorageObjectPolicy;
import io.github.xdstorage.annotations.XdStorageParentObject;

@XdStorageObjectPolicy(policy = XdStoragePolicy.StoreAsSingleObject)
public class HashPolicyChildSingleObject {

    @XdStorageObjectId
    private Long id;

    @XdStorageParentObject
    private HashPolicyParentSingleObjectChildSingleObject parent;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public HashPolicyParentSingleObjectChildSingleObject getParent() { return parent; }
    public void setParent(HashPolicyParentSingleObjectChildSingleObject parent) { this.parent = parent; }
}
