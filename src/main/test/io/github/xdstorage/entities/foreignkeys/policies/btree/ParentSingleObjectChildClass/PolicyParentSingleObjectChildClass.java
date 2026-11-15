package io.github.xdstorage.entities.foreignkeys.policies.btree.ParentSingleObjectChildClass;

import io.github.xdstorage.XdStoragePolicy;
import io.github.xdstorage.annotations.XdStorageAutoSave;
import io.github.xdstorage.annotations.XdStorageObjectId;
import io.github.xdstorage.annotations.XdStorageObjectPolicy;

import java.util.ArrayList;
import java.util.Collection;

@XdStorageObjectPolicy(policy = XdStoragePolicy.StoreAsSingleObject)
public class PolicyParentSingleObjectChildClass {

    @XdStorageObjectId
    private Long id;

    @XdStorageAutoSave
    private Collection<PolicyChildClass> children = new ArrayList<>();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Collection<PolicyChildClass> getChildren() { return children; }
    public void addChild(PolicyChildClass child) { this.children.add(child); }
    public void setChildren(Collection<PolicyChildClass> children) { this.children = children; }
}
