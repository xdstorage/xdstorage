package io.github.xdstorage.entities;

import io.github.xdstorage.XdStoragePolicy;
import io.github.xdstorage.annotations.XdStorageObjectId;
import io.github.xdstorage.annotations.XdStorageObjectPolicy;

/** An entity of the class-objects policy, for the migration tool's tests (docs/tasks/2.2.0/17). */
@XdStorageObjectPolicy(policy = XdStoragePolicy.StoreAsClassObjects)
public class XdStorageMigrateOrder {

    @XdStorageObjectId
    private String id;
    private String name;
    private Long amount;

    public XdStorageMigrateOrder() {
    }

    public XdStorageMigrateOrder(final String id, final String name, final Long amount) {
        this.id = id;
        this.name = name;
        this.amount = amount;
    }

    public String getId() {
        return id;
    }

    public void setId(final String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(final String name) {
        this.name = name;
    }

    public Long getAmount() {
        return amount;
    }

    public void setAmount(final Long amount) {
        this.amount = amount;
    }
}
