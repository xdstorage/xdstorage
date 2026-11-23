package io.github.xdstorage.properties;

import io.github.xdstorage.XdStoragePolicy;
import io.github.xdstorage.annotations.XdStorageObjectId;
import io.github.xdstorage.annotations.XdStorageObjectPolicy;

/** PROPTEST Этап 2: the scalars host (StoreAsClassObjects, hash id index). */
@XdStorageObjectPolicy(policy = XdStoragePolicy.StoreAsClassObjects)
public class PropScalarsHost {
    @XdStorageObjectId
    private String id;
    private PropScalars scalars = new PropScalars();

    public PropScalarsHost() {
    }

    public PropScalarsHost withId(final String id) {
        this.id = id;
        return this;
    }

    public String getId() {
        return id;
    }

    public void setId(final String id) {
        this.id = id;
    }

    public PropScalars getScalars() {
        return scalars;
    }

    public void setScalars(final PropScalars scalars) {
        this.scalars = scalars;
    }
}
