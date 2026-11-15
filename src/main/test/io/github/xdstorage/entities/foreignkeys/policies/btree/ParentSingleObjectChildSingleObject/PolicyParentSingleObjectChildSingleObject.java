package io.github.xdstorage.entities.foreignkeys.policies.btree.ParentSingleObjectChildSingleObject;

import io.github.xdstorage.XdStoragePolicy;
import io.github.xdstorage.annotations.XdStorageAutoSave;
import io.github.xdstorage.annotations.XdStorageObjectId;
import io.github.xdstorage.annotations.XdStorageObjectPolicy;

import java.util.ArrayList;
import java.util.Collection;

@XdStorageObjectPolicy(policy = XdStoragePolicy.StoreAsSingleObject)
public class PolicyParentSingleObjectChildSingleObject {

    @XdStorageObjectId
    private Long id;

    @XdStorageAutoSave
    private Collection<PolicyChildSingleObject> children = new ArrayList<>();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Collection<PolicyChildSingleObject> getChildren() { return children; }
    public void addChild(PolicyChildSingleObject child) { this.children.add(child); }
    public void setChildren(Collection<PolicyChildSingleObject> children) { this.children = children; }
}
