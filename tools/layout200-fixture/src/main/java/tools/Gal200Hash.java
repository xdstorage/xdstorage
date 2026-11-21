package tools;

import io.github.xdstorage.XdStoragePolicy;
import io.github.xdstorage.annotations.XdStorageObjectId;
import io.github.xdstorage.annotations.XdStorageObjectPolicy;

/**
 * Task 51 golden-fixture entity with the DEFAULT (hash) id index — covers the hash
 * id-index flavor at its legacy 2.0.0 location (data/&lt;Class&gt;/index.yaml).
 */
@XdStorageObjectPolicy(policy = XdStoragePolicy.StoreAsClassObjects)
public class Gal200Hash {

    @XdStorageObjectId
    private String id;

    private String value;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }
}
