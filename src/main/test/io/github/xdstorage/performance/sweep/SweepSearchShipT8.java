package io.github.xdstorage.performance.sweep;

import io.github.xdstorage.XdStoragePolicy;
import io.github.xdstorage.annotations.XdStorageObjectId;
import io.github.xdstorage.annotations.XdStorageObjectIdIndexType;
import io.github.xdstorage.annotations.XdStorageObjectChildSearchIndex;
import io.github.xdstorage.annotations.XdStorageObjectPolicy;
import io.github.xdstorage.annotations.XdStorageObjectSearchIndex;
import io.github.xdstorage.index.XdStorageIndexType;

import java.util.ArrayList;
import java.util.List;

/** Sweep ship: id index BTree t=64 (fixed); search index t=8 (the swept variable). Top-level class (wrapper codegen
 * requirement). */
@XdStorageObjectPolicy(policy = XdStoragePolicy.StoreAsClassObjects)
@XdStorageObjectIdIndexType(indexType = XdStorageIndexType.BTree, t = 64)
@XdStorageObjectSearchIndex(indexName = "sweep_idx", indexFieldNames = {"name", "tonnage"}, t = 8, childrenIndexes = {
        @XdStorageObjectChildSearchIndex(childFieldName = "captain", childClassIndexName = "sweep_crew_idx"),
        @XdStorageObjectChildSearchIndex(childFieldName = "crew", childClassIndexName = "sweep_crew_idx"),
})
public class SweepSearchShipT8 {
    @XdStorageObjectId
    private Long id;
    private String name;
    private int tonnage;
    private SweepSearchCrewMember captain;
    private List<SweepSearchCrewMember> crew;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public int getTonnage() { return tonnage; }
    public void setTonnage(int tonnage) { this.tonnage = tonnage; }
    public SweepSearchCrewMember getCaptain() { return captain; }
    public void setCaptain(SweepSearchCrewMember captain) { this.captain = captain; }
    public List<SweepSearchCrewMember> getCrew() { return crew; }
    public void setCrew(List<SweepSearchCrewMember> crew) { this.crew = crew; }

    public void addCrewMember(SweepSearchCrewMember member) {
        if (crew == null) {
            crew = new ArrayList<>();
        }
        crew.add(member);
    }
}
