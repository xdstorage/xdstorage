package io.github.xdstorage.performance.entities;

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
public class XdPlanetAutoSave {

    @XdStorageObjectId
    @XdStorageObjectFieldProperties(idGeneratorType = XdStorageIdGeneratorType.DATABASE_GENERATOR)
    private Long id;

    //    @XdStorageObjectId
    @XdStorageObjectFieldProperties(length = 54)
    private String strId;

    //    @XdStorageObjectSearchField(indexesNames = {XdConstants.TEST_INDEX_NAME}, fieldName = XdConstants.NAME_FIELD)
    @XdStorageObjectFieldProperties(length = 1000)
    private String name;

    //    @XdStorageObjectSearchField(indexesNames = {XdConstants.TEST_INDEX_NAME}, fieldName = XdConstants.WATER_PERCENT_FIELD)
    private int waterPercent;

    private XdSatelliteAutoSave satellite;

    private XdSatelliteAutoSave[] arrSatellites;

    private Collection<XdSatelliteAutoSave> colSatellites;

    private Map<String, XdSatelliteAutoSave> mapSatellites;

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

    public XdSatelliteAutoSave getSatellite() {
        return satellite;
    }

    public void setSatellite(XdSatelliteAutoSave satellite) {
        this.satellite = satellite;
    }

    public XdSatelliteAutoSave[] getArrSatellites() {
        return arrSatellites;
    }

    public void setArrSatellites(XdSatelliteAutoSave[] arrSatellites) {
        this.arrSatellites = arrSatellites;
    }

    public Collection<XdSatelliteAutoSave> getColSatellites() {
        return colSatellites;
    }

    public void setColSatellites(Collection<XdSatelliteAutoSave> colSatellites) {
        this.colSatellites = colSatellites;
    }

    public void addSatellite(final XdSatelliteAutoSave satellite) {
        if (colSatellites == null) {
            colSatellites = new ArrayList<>();
        }
        colSatellites.add(satellite);
    }

    public void addSatelliteToMap(final String key, final XdSatelliteAutoSave satellite) {
        if (mapSatellites == null) {
            mapSatellites = new HashMap<>();
        }
        mapSatellites.put(key, satellite);
    }

    public Map<String, XdSatelliteAutoSave> getMapSatellites() {
        return mapSatellites;
    }

    public void setMapSatellites(Map<String, XdSatelliteAutoSave> mapSatellites) {
        this.mapSatellites = mapSatellites;
    }

    @Override
    public Object clone() {
        XdPlanetAutoSave copy = new XdPlanetAutoSave();
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
