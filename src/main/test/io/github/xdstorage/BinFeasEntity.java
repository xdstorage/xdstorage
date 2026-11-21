package io.github.xdstorage;

import io.github.xdstorage.annotations.XdStorageObjectId;
import io.github.xdstorage.annotations.XdStorageObjectPolicy;

import java.util.ArrayList;
import java.util.List;

/**
 * BINFEAS (docs/tasks/2.1.0/33): the feasibility-probe entity — a MODERATE-width shape
 * (10 fields, one small collection) standing in for a realistic domain object: the YAML
 * verbosity (and thus the parse cost) scales with the field count, and a 2-field entity
 * would understate it. TOP-LEVEL per the house rule (the runtime wrapper generation
 * cannot compile nested test classes).
 */
@XdStorageObjectPolicy(policy = XdStoragePolicy.StoreAsClassObjects)
public class BinFeasEntity {

    @XdStorageObjectId
    private String id;

    private String name;
    private String category;
    private String region;
    private String comment;
    private Long amountA;
    private Long amountB;
    private Long quantity;
    private Double ratio;
    private Boolean active;
    private List<String> tags = new ArrayList<>();

    public BinFeasEntity() {
    }

    public BinFeasEntity(final String id, final int seed) {
        this.id = id;
        this.name = "entity-" + seed;
        this.category = "cat-" + (seed % 50);
        this.region = "region-" + (seed % 10);
        this.comment = "comment payload for seed " + seed + " — moderately long text to carry bytes";
        this.amountA = (long) (seed * 17);
        this.amountB = (long) (seed * 29);
        this.quantity = (long) (seed % 1000);
        this.ratio = seed * 0.75;
        this.active = seed % 2 == 0;
        this.tags.add("t" + (seed % 7));
        this.tags.add("t" + (seed % 11));
        this.tags.add("t" + (seed % 13));
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String getComment() {
        return comment;
    }

    public void setComment(String comment) {
        this.comment = comment;
    }

    public Long getAmountA() {
        return amountA;
    }

    public void setAmountA(Long amountA) {
        this.amountA = amountA;
    }

    public Long getAmountB() {
        return amountB;
    }

    public void setAmountB(Long amountB) {
        this.amountB = amountB;
    }

    public Long getQuantity() {
        return quantity;
    }

    public void setQuantity(Long quantity) {
        this.quantity = quantity;
    }

    public Double getRatio() {
        return ratio;
    }

    public void setRatio(Double ratio) {
        this.ratio = ratio;
    }

    public Boolean getActive() {
        return active;
    }

    public void setActive(Boolean active) {
        this.active = active;
    }

    public List<String> getTags() {
        return tags;
    }

    public void setTags(List<String> tags) {
        this.tags = tags;
    }
}
