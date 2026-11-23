package io.github.xdstorage.properties;

import io.github.xdstorage.XdStoragePolicy;
import io.github.xdstorage.annotations.XdStorageObjectId;
import io.github.xdstorage.annotations.XdStorageObjectPolicy;

/** PROPTEST Этап 2: the jungle host (StoreAsClassObjects, hash id index). */
@XdStorageObjectPolicy(policy = XdStoragePolicy.StoreAsClassObjects)
public class PropJungleHost {
    @XdStorageObjectId
    private String id;
    private PropJungle jungle = new PropJungle();

    public PropJungleHost() {
    }

    public PropJungleHost withId(final String id) {
        this.id = id;
        return this;
    }

    public String getId() {
        return id;
    }

    public void setId(final String id) {
        this.id = id;
    }

    public PropJungle getJungle() {
        return jungle;
    }

    public void setJungle(final PropJungle jungle) {
        this.jungle = jungle;
    }
}
