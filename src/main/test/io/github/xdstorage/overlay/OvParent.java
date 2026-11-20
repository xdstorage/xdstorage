package io.github.xdstorage.overlay;

import io.github.xdstorage.XdStoragePolicy;
import io.github.xdstorage.annotations.XdStorageAutoSave;
import io.github.xdstorage.annotations.XdStorageObjectId;
import io.github.xdstorage.annotations.XdStorageObjectIdIndexType;
import io.github.xdstorage.annotations.XdStorageObjectPolicy;
import io.github.xdstorage.index.XdStorageIndexType;

import java.util.ArrayList;
import java.util.List;

/**
 * The TREEOVERLAY test entities (task 53) — TOP-LEVEL per the house rule (the
 * runtime wrapper generation cannot compile nested test classes: "wrapper
 * generation degraded to dummy"). Both btree id-indexed (t=4 — the small t
 * forces the splits under the concurrent load).
 */
@XdStorageObjectPolicy(policy = XdStoragePolicy.StoreAsClassObjects)
@XdStorageObjectIdIndexType(indexType = XdStorageIndexType.BTree, t = 4)
public class OvParent {

    @XdStorageObjectId
    public String id;

    @XdStorageAutoSave
    public List<OvChild> children = new ArrayList<>();

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public List<OvChild> getChildren() { return children; }
    public void addChild(OvChild c) { children.add(c); }
}
