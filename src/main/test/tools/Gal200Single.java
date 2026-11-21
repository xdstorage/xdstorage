package tools;

import io.github.xdstorage.XdStoragePolicy;
import io.github.xdstorage.annotations.XdStorageObjectId;
import io.github.xdstorage.annotations.XdStorageObjectPolicy;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Task 51 golden-fixture entity with the StoreAsSingleObject policy — covers the
 * references base at its legacy 2.0.0 location (data/&lt;Class&gt;/references.yaml).
 */
@XdStorageObjectPolicy(policy = XdStoragePolicy.StoreAsSingleObject)
public class Gal200Single {

    @XdStorageObjectId
    private String id;

    private Collection<String> tags = new ArrayList<>();

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public Collection<String> getTags() {
        return tags;
    }

    public void setTags(Collection<String> tags) {
        this.tags = (tags instanceof List) ? (List<String>) tags : new ArrayList<String>(tags);
    }

    public void addTag(String tag) {
        this.tags.add(tag);
    }
}
