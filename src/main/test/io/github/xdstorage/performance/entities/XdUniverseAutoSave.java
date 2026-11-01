package io.github.xdstorage.performance.entities;

import io.github.xdstorage.XdStoragePolicy;
import io.github.xdstorage.annotations.XdStorageAutoSave;
import io.github.xdstorage.annotations.XdStorageObjectId;
import io.github.xdstorage.annotations.XdStorageObjectPolicy;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;

@XdStorageObjectPolicy(policy = XdStoragePolicy.StoreAsClassObjects)
public class XdUniverseAutoSave {

    @XdStorageObjectId
    private String id;

    @XdStorageAutoSave
    private Collection<XdGalaxyAutoSave> galaxies;

    public String getId() {
        return id;
    }

    public void setId(final String id) {
        this.id = id;
    }

    public Collection<XdGalaxyAutoSave> getGalaxies() {
        return galaxies != null ? galaxies : Collections.emptyList();
    }

    public void setGalaxies(Collection<XdGalaxyAutoSave> galaxies) {
        this.galaxies = galaxies;
    }

    public void addGalaxy(XdGalaxyAutoSave galaxy) {
        if (galaxies == null) {
            galaxies = new ArrayList<>();
        }
        galaxies.add(galaxy);
    }

    public XdGalaxyAutoSave getGalaxy(final int index) {
        final Iterator<XdGalaxyAutoSave> it = galaxies.iterator();
        for (int i = 0; it.hasNext(); ++i) {
            final XdGalaxyAutoSave gal = it.next();
            if (i == index)
                return gal;
        }
        return null;
    }

    public XdGalaxyAutoSave removeGalaxy(int index) {
        final Iterator<XdGalaxyAutoSave> it = galaxies.iterator();
        for (int i = 0; it.hasNext(); ++i) {
            final XdGalaxyAutoSave gal = it.next();
            if (i == index) {
                it.remove();
                return gal;
            }
        }
        return null;
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder("XdUniverse { \r\n");
        sb.append(" idgeneration: ").append(id).append("\r\n");

        if(galaxies != null)
            for (final XdGalaxyAutoSave galaxy : galaxies) {
                sb.append(", ").append(galaxy.toString()).append("\r\n");
            }
        sb.append(" }");
        return sb.toString();
    }
}
