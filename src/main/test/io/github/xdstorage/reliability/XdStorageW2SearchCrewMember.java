package io.github.xdstorage.reliability;

import io.github.xdstorage.XdStoragePolicy;
import io.github.xdstorage.annotations.XdStorageObjectId;
import io.github.xdstorage.annotations.XdStorageObjectPolicy;
import io.github.xdstorage.annotations.XdStorageObjectSearchIndex;

/** W-2: child entity (StoreWithParentObject) поискового индекса — B+Tree внутри. */
@XdStorageObjectPolicy(policy = XdStoragePolicy.StoreWithParentObject)
@XdStorageObjectSearchIndex(indexName = "w2_crew_idx", indexFieldNames = {"rank"}, t = 8)
public class XdStorageW2SearchCrewMember {

    @XdStorageObjectId
    private Long id;
    private String rank;

    public XdStorageW2SearchCrewMember() {}

    public XdStorageW2SearchCrewMember(final Long id, final String rank) {
        this.id = id;
        this.rank = rank;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getRank() { return rank; }
    public void setRank(String rank) { this.rank = rank; }
}
