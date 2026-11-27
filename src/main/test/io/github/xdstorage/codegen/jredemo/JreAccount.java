package io.github.xdstorage.codegen.jredemo;

import io.github.xdstorage.XdStoragePolicy;
import io.github.xdstorage.annotations.XdStorageObjectId;
import io.github.xdstorage.annotations.XdStorageObjectPolicy;
import io.github.xdstorage.annotations.XdStorageObjectSearchIndex;

/**
 * The second entity of the JRE demo application (docs/tasks/2.2.0/08) — one transaction
 * over two classes, as the quickstart does. Test fixture — not part of any API.
 */
@XdStorageObjectPolicy(policy = XdStoragePolicy.StoreAsClassObjects)
@XdStorageObjectSearchIndex(indexName = "by_owner", indexFieldNames = {"owner"})
public class JreAccount {

    @XdStorageObjectId
    private String id;

    private String owner;

    private Long balance;

    public JreAccount() {
    }

    public JreAccount(final String id, final String owner, final Long balance) {
        this.id = id;
        this.owner = owner;
        this.balance = balance;
    }

    public String getId() {
        return id;
    }

    public void setId(final String id) {
        this.id = id;
    }

    public String getOwner() {
        return owner;
    }

    public void setOwner(final String owner) {
        this.owner = owner;
    }

    public Long getBalance() {
        return balance;
    }

    public void setBalance(final Long balance) {
        this.balance = balance;
    }
}
