package io.github.xdstorage.performance.sweep;

import io.github.xdstorage.XdStoragePolicy;
import io.github.xdstorage.annotations.XdStorageObjectId;
import io.github.xdstorage.annotations.XdStorageObjectPolicy;

/** Sweep entity: default Hash id index (fragmented data files, YAML) — fragment-size sweep + hash/btree comparison
 * baseline. */
@XdStorageObjectPolicy(policy = XdStoragePolicy.StoreAsClassObjects)
public class SweepHashEntity {
    @XdStorageObjectId
    private String id;
    private String payload;
    private long number;

    public SweepHashEntity() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; }
    public long getNumber() { return number; }
    public void setNumber(long number) { this.number = number; }
}
