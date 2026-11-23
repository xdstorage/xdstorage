package io.github.xdstorage.entities;

import io.github.xdstorage.XdStoragePolicy;
import io.github.xdstorage.annotations.*;
import io.github.xdstorage.idgeneration.XdStorageIdGeneratorType;
import io.github.xdstorage.index.XdStorageIndexType;

import java.util.*;

@XdStorageObjectPolicy(policy = XdStoragePolicy.StoreAsClassObjects)
@XdStorageObjectSearchIndex(indexName = "planet_idx", indexFieldNames = {"name", "waterPercent"}, t = 64,
    childrenIndexes = {
        @XdStorageObjectChildSearchIndex(childFieldName = "satellite", childClassIndexName = "satellite_idx"),
        @XdStorageObjectChildSearchIndex(childFieldName = "colSatellites", childClassIndexName = "satellite_idx")
})
@XdStorageObjectIdIndexType(indexType = XdStorageIndexType.BTree, t = 64)
public class XdPlanet {

    @XdStorageObjectId
    @XdStorageObjectFieldProperties(idGeneratorType = XdStorageIdGeneratorType.DATABASE_GENERATOR)
    private Long id;

    //    @XdStorageObjectId
    @XdStorageObjectFieldProperties(length = 54)
    private String strId;

    //    @XdStorageObjectSearchField(indexesNames = {XdConstants.TEST_INDEX_NAME}, fieldName = XdConstants.NAME_FIELD)
    @XdStorageObjectFieldProperties(length = 1000)
    private String name;

    // @XdStorageObjectSearchField(indexesNames = {XdConstants.TEST_INDEX_NAME}, fieldName =
    // XdConstants.WATER_PERCENT_FIELD)
    private int waterPercent;

    private XdSatellite satellite;

    private XdSatellite[] arrSatellites;

    private Collection<XdSatellite> colSatellites;

    private Map<String, XdSatellite> mapSatellites;

    private List<XdInternalObject> internalObjects;

    private Date date = new Date();

    public Date getDate() {
        return date;
    }

    public void setDate(Date date) {
        this.date = date;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getStrId() {
        return strId;
    }

    public void setStrId(String strId) {
        this.strId = strId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public int getWaterPercent() {
        return waterPercent;
    }

    public void setWaterPercent(int waterPercent) {
        this.waterPercent = waterPercent;
    }

    public XdSatellite getSatellite() {
        return satellite;
    }

    public void setSatellite(XdSatellite satellite) {
        this.satellite = satellite;
    }

    public XdSatellite[] getArrSatellites() {
        return arrSatellites;
    }

    public void setArrSatellites(XdSatellite[] arrSatellites) {
        this.arrSatellites = arrSatellites;
    }

    public Collection<XdSatellite> getColSatellites() {
        return colSatellites;
    }

    public void setColSatellites(Collection<XdSatellite> colSatellites) {
        this.colSatellites = colSatellites;
    }

    public List<XdInternalObject> getInternalObjects() {
        return internalObjects;
    }

    public void setInternalObjects(List<XdInternalObject> internalObjects) {
        this.internalObjects = internalObjects;
    }

    public void addSatellite(final XdSatellite satellite) {
        if (colSatellites == null) {
            colSatellites = new ArrayList<>();
        }
        colSatellites.add(satellite);
    }

    public void addSatelliteToMap(final String key, final XdSatellite satellite) {
        if (mapSatellites == null) {
            mapSatellites = new HashMap<>();
        }
        mapSatellites.put(key, satellite);
    }

    public Map<String, XdSatellite> getMapSatellites() {
        return mapSatellites;
    }

    public void setMapSatellites(Map<String, XdSatellite> mapSatellites) {
        this.mapSatellites = mapSatellites;
    }

    @Override
    public Object clone() {
        XdPlanet copy = new XdPlanet();
        copy.setId(id);
        return copy;
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder("XdPlanet { ");
        sb.append(" id: ").append(id);
        sb.append(", name: ").append(name);
        sb.append(", waterPercent: ").append(waterPercent);
        sb.append(" }");
        return sb.toString();
    }
}
