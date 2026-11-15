package io.github.xdstorage.entities.foreignkeys.policies.btree.ParentClassChildSingleObject;

import io.github.xdstorage.XdStoragePolicy;
import io.github.xdstorage.annotations.XdStorageObjectId;
import io.github.xdstorage.annotations.XdStorageObjectPolicy;
import io.github.xdstorage.annotations.XdStorageParentObject;

@XdStorageObjectPolicy(policy = XdStoragePolicy.StoreAsSingleObject)
public class PolicyChildSingleObject {

    @XdStorageObjectId
    private Long id;

    @XdStorageParentObject
    private PolicyParentClassChildSingleObject parent;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public PolicyParentClassChildSingleObject getParent() { return parent; }
    public void setParent(PolicyParentClassChildSingleObject parent) { this.parent = parent; }
}
