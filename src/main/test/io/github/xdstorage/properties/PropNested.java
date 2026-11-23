package io.github.xdstorage.properties;

import java.util.ArrayList;
import java.util.List;

/**
 * PROPTEST: the embedded nested entity (the DEFAULT StoreWithParentObject policy —
 * no annotation) — stored inside the parent's record, not addressable by id.
 */
public class PropNested {

    private String title;

    private Long weight;

    private List<Long> flags = new ArrayList<>();

    public PropNested() {
    }

    public PropNested(final String title, final Long weight, final List<Long> flags) {
        this.title = title;
        this.weight = weight;
        this.flags = flags;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public Long getWeight() {
        return weight;
    }

    public void setWeight(Long weight) {
        this.weight = weight;
    }

    public List<Long> getFlags() {
        return flags;
    }

    public void setFlags(List<Long> flags) {
        this.flags = flags;
    }
}
