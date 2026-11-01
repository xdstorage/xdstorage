package io.github.xdstorage.entities.foreignkeys;

import io.github.xdstorage.XdStoragePolicy;
import io.github.xdstorage.annotations.XdStorageObjectId;
import io.github.xdstorage.annotations.XdStorageObjectPolicy;
import io.github.xdstorage.annotations.XdStorageParentObject;

@XdStorageObjectPolicy(policy = XdStoragePolicy.StoreAsClassObjects)
public class Employee {
    @XdStorageObjectId
    private String id;

    @XdStorageParentObject
    private Company parentCompany; // СУБД должна заполнить это поле автоматически при загрузке

    public Employee() {
    }

    public Employee(String id) {
        this.id = id;
    }

    public String getId() {
        return id;
    }

    public Company getParentCompany() {
        return parentCompany;
    }

    public void setId(String id) {
        this.id = id;
    }

    public void setParentCompany(Company parentCompany) {
        this.parentCompany = parentCompany;
    }
}
