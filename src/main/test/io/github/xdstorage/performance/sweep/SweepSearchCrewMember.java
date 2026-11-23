package io.github.xdstorage.performance.sweep;

import io.github.xdstorage.XdStoragePolicy;
import io.github.xdstorage.annotations.XdStorageObjectId;
import io.github.xdstorage.annotations.XdStorageObjectPolicy;
import io.github.xdstorage.annotations.XdStorageObjectSearchIndex;

/** Sweep child entity (StoreWithParentObject): fixed search-tree t=8; ship classes vary only their own index t. */
@XdStorageObjectPolicy(policy = XdStoragePolicy.StoreWithParentObject)
@XdStorageObjectSearchIndex(indexName = "sweep_crew_idx", indexFieldNames = {"rank"}, t = 8)
public class SweepSearchCrewMember {

    @XdStorageObjectId
    private Long id;
    private String rank;

    public SweepSearchCrewMember() {}

    public SweepSearchCrewMember(Long id, String rank) {
        this.id = id;
        this.rank = rank;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getRank() { return rank; }
    public void setRank(String rank) { this.rank = rank; }
}
