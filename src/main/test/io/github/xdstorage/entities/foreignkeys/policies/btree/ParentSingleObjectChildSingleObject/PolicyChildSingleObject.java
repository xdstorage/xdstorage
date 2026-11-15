package io.github.xdstorage.entities.foreignkeys.policies.btree.ParentSingleObjectChildSingleObject;

import io.github.xdstorage.XdStoragePolicy;
import io.github.xdstorage.annotations.XdStorageObjectId;
import io.github.xdstorage.annotations.XdStorageObjectPolicy;
import io.github.xdstorage.annotations.XdStorageParentObject;

@XdStorageObjectPolicy(policy = XdStoragePolicy.StoreAsSingleObject)
public class PolicyChildSingleObject {

    @XdStorageObjectId
    private Long id;

    @XdStorageParentObject
    private PolicyParentSingleObjectChildSingleObject parent;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public PolicyParentSingleObjectChildSingleObject getParent() { return parent; }
    public void setParent(PolicyParentSingleObjectChildSingleObject parent) { this.parent = parent; }
}
