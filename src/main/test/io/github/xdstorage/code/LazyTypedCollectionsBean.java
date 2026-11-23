package io.github.xdstorage.code;

import io.github.xdstorage.XdStoragePolicy;
import io.github.xdstorage.annotations.XdStorageLoadByGetMethod;
import io.github.xdstorage.annotations.XdStorageObjectId;
import io.github.xdstorage.annotations.XdStorageObjectPolicy;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Set;

/**
 * WRAPGEN fixture (docs/tasks/2.2.0/35): lazy load-by-get fields declared with the
 * collection types applications actually write. Only a {@code Collection}-, {@code List}- or
 * exactly-{@code Map}-declared field took the per-element branch of the generator; a
 * {@code Set}, an {@code ArrayList} or a {@code HashMap} fell through to the single-object one.
 */
@XdStorageObjectPolicy(policy = XdStoragePolicy.StoreAsClassObjects)
public class LazyTypedCollectionsBean {

    @XdStorageObjectId
    private Long id;
    private String name;

    @XdStorageLoadByGetMethod
    private Set<ValueChild> lazySet;

    @XdStorageLoadByGetMethod
    private ArrayList<ValueChild> lazyList;

    @XdStorageLoadByGetMethod
    private HashMap<String, ValueChild> lazyMap;

    /** The control declaration: the one type that took the collection branch before the fix. */
    @XdStorageLoadByGetMethod
    private Collection<ValueChild> lazyCollection;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Set<ValueChild> getLazySet() { return lazySet; }
    public void setLazySet(Set<ValueChild> lazySet) { this.lazySet = lazySet; }
    public ArrayList<ValueChild> getLazyList() { return lazyList; }
    public void setLazyList(ArrayList<ValueChild> lazyList) { this.lazyList = lazyList; }
    public HashMap<String, ValueChild> getLazyMap() { return lazyMap; }
    public void setLazyMap(HashMap<String, ValueChild> lazyMap) { this.lazyMap = lazyMap; }
    public Collection<ValueChild> getLazyCollection() { return lazyCollection; }
    public void setLazyCollection(Collection<ValueChild> lazyCollection) { this.lazyCollection = lazyCollection; }
}
