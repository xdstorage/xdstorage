package io.github.xdstorage.properties;

import io.github.xdstorage.annotations.XdStorageObjectId;
import io.github.xdstorage.annotations.XdStorageObjectIdIndexType;
import io.github.xdstorage.annotations.XdStorageObjectPolicy;
import io.github.xdstorage.XdStoragePolicy;
import io.github.xdstorage.index.XdStorageIndexType;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * PROPTEST: the StoreAsClassObjects + HASH id-index arm of the shared shape.
 */
@XdStorageObjectPolicy(policy = XdStoragePolicy.StoreAsClassObjects)
@XdStorageObjectIdIndexType(indexType = XdStorageIndexType.BTree, t = 8)
public class PropWideBTree implements PropWideLike {

    @XdStorageObjectId
    private String id;

    private String name;
    private Long amount;
    private Integer count;
    private Double ratio;
    private Boolean active;
    private Date created;
    private BigDecimal decimal;
    private List<String> tags = new ArrayList<>();
    private Map<String, String> meta = new HashMap<>();
    private String[] labels = new String[0];
    private PropNested nested;

    @Override
    public String getId() {
        return id;
    }

    @Override
    public void setId(String id) {
        this.id = id;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public void setName(String name) {
        this.name = name;
    }

    @Override
    public Long getAmount() {
        return amount;
    }

    @Override
    public void setAmount(Long amount) {
        this.amount = amount;
    }

    @Override
    public Integer getCount() {
        return count;
    }

    @Override
    public void setCount(Integer count) {
        this.count = count;
    }

    @Override
    public Double getRatio() {
        return ratio;
    }

    @Override
    public void setRatio(Double ratio) {
        this.ratio = ratio;
    }

    @Override
    public Boolean getActive() {
        return active;
    }

    @Override
    public void setActive(Boolean active) {
        this.active = active;
    }

    @Override
    public Date getCreated() {
        return created;
    }

    @Override
    public void setCreated(Date created) {
        this.created = created;
    }

    @Override
    public BigDecimal getDecimal() {
        return decimal;
    }

    @Override
    public void setDecimal(BigDecimal decimal) {
        this.decimal = decimal;
    }

    @Override
    public List<String> getTags() {
        return tags;
    }

    @Override
    public void setTags(List<String> tags) {
        this.tags = tags;
    }

    @Override
    public Map<String, String> getMeta() {
        return meta;
    }

    @Override
    public void setMeta(Map<String, String> meta) {
        this.meta = meta;
    }

    @Override
    public String[] getLabels() {
        return labels;
    }

    @Override
    public void setLabels(String[] labels) {
        this.labels = labels;
    }

    @Override
    public PropNested getNested() {
        return nested;
    }

    @Override
    public void setNested(PropNested nested) {
        this.nested = nested;
    }
}
