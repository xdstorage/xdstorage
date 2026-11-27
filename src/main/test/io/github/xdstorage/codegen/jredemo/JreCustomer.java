package io.github.xdstorage.codegen.jredemo;

import io.github.xdstorage.XdStoragePolicy;
import io.github.xdstorage.annotations.XdStorageObjectId;
import io.github.xdstorage.annotations.XdStorageObjectPolicy;
import io.github.xdstorage.annotations.XdStorageObjectSearchIndex;

/**
 * An entity of the JRE demo application (docs/tasks/2.2.0/08): a plain JavaBean, as an
 * application's class would be. Test fixture — not part of any API.
 */
@XdStorageObjectPolicy(policy = XdStoragePolicy.StoreAsClassObjects)
@XdStorageObjectSearchIndex(indexName = "by_country", indexFieldNames = {"country"})
public class JreCustomer {

    @XdStorageObjectId
    private String id;

    private String name;

    private String country;

    public JreCustomer() {
    }

    public JreCustomer(final String id, final String name, final String country) {
        this.id = id;
        this.name = name;
        this.country = country;
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

    public String getCountry() {
        return country;
    }

    public void setCountry(final String country) {
        this.country = country;
    }
}
