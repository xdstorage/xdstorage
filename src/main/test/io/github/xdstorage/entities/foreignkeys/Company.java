package io.github.xdstorage.entities.foreignkeys;

import io.github.xdstorage.XdStoragePolicy;
import io.github.xdstorage.annotations.XdStorageAutoSave;
import io.github.xdstorage.annotations.XdStorageObjectId;
import io.github.xdstorage.annotations.XdStorageObjectPolicy;

import java.util.ArrayList;
import java.util.Collection;

@XdStorageObjectPolicy(policy = XdStoragePolicy.StoreAsClassObjects)
public class Company {
    @XdStorageObjectId
    private String id;

    @XdStorageAutoSave
    private Collection<Employee> employees = new ArrayList<>();

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public Collection<Employee> getEmployees() {
        return employees;
    }

    public void addEmployee(Employee emp) {
        this.employees.add(emp);
    }

    public void setEmployees(Collection<Employee> employees) {
        this.employees = employees;
    }
}
