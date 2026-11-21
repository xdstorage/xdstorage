package tools;

import io.github.xdstorage.XdStoragePolicy;
import io.github.xdstorage.annotations.XdStorageObjectId;
import io.github.xdstorage.annotations.XdStorageObjectPolicy;

/**
 * Task 51 golden-fixture child entity (the 2.0.0 API surface — NO @XdStorageAutoSave:
 * the cascade is a 2.1.0 feature, children are saved explicitly by the generator).
 * Top-level per the house rule (the runtime wrapper generation cannot compile nested
 * classes). Private fields + the full getter/setter pairs — the engine's JavaBeans
 * contract (a getter without a matching setter drops the field from persistence).
 */
@XdStorageObjectPolicy(policy = XdStoragePolicy.StoreAsClassObjects)
public class Gal200Child {

    @XdStorageObjectId
    private String id;

    private String label;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }
}
