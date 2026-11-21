package tools;

import io.github.xdstorage.XdStoragePolicy;
import io.github.xdstorage.annotations.XdStorageObjectId;
import io.github.xdstorage.annotations.XdStorageObjectIdIndexType;
import io.github.xdstorage.annotations.XdStorageObjectPolicy;
import io.github.xdstorage.annotations.XdStorageObjectSearchIndex;
import io.github.xdstorage.index.XdStorageIndexType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Task 51 golden-fixture parent entity (the 2.0.0 API surface): a BTree id index
 * (t=4 — small enough to force node files), a search index over the Cyrillic title
 * (the UTF-8 pin probe), an entity collection saved EXPLICITLY (no AutoSave in 2.0.0).
 */
@XdStorageObjectPolicy(policy = XdStoragePolicy.StoreAsClassObjects)
@XdStorageObjectIdIndexType(indexType = XdStorageIndexType.BTree, t = 4)
@XdStorageObjectSearchIndex(indexName = "titleIdx", indexFieldNames = {"title"})
public class Gal200Parent {

    @XdStorageObjectId
    private String id;

    private String title;

    private Collection<Gal200Child> children = new ArrayList<>();

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public Collection<Gal200Child> getChildren() {
        return children;
    }

    public void setChildren(Collection<Gal200Child> children) {
        this.children = (children instanceof List) ? (List<Gal200Child>) children : new ArrayList<>(children);
    }

    public void addChild(Gal200Child child) {
        this.children.add(child);
    }
}
