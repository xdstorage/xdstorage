package io.github.xdstorage.performance.entities;

import io.github.xdstorage.XdStoragePolicy;
import io.github.xdstorage.annotations.XdStorageObjectId;
import io.github.xdstorage.annotations.XdStorageObjectPolicy;

@XdStorageObjectPolicy(policy = XdStoragePolicy.StoreWithParentObject)
public class XdBlackHoleAutoSave {

    @XdStorageObjectId
    private String id;

    private long mass;

    private boolean isEventHorizonActive;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public long getMass() {
        return mass;
    }

    public void setMass(long mass) {
        this.mass = mass;
    }

    public boolean getIsEventHorizonActive() {
        return isEventHorizonActive;
    }

    public void setIsEventHorizonActive(boolean eventHorizonActive) {
        isEventHorizonActive = eventHorizonActive;
    }
}
