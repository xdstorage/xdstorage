package io.github.xdstorage.performance.entities;

import io.github.xdstorage.XdStoragePolicy;
import io.github.xdstorage.annotations.*;

import java.util.ArrayList;
import java.util.Collection;

@XdStorageObjectPolicy(policy = XdStoragePolicy.StoreAsClassObjects)
@XdStorageObjectSearchIndex(indexName = "star_system_idx", indexFieldNames = {"newName"}, t = 50,
        childrenIndexes = {
                @XdStorageObjectChildSearchIndex(childFieldName = "stars", childClassIndexName = "test_star_index"),
        })
public class XdStarSystemAutoSave {

    @XdStorageObjectId
    private String id;

    private int primitive = 0;

    private String newName;

    private Collection<XdStarAutoSave> stars;

    @XdStorageAutoSave
    @XdStorageLoadByGetMethod
    private Collection<XdPlanetAutoSave> planets;

    public void setId(String id) {
        this.id = id;
    }

    public String getId() {
        return id;
    }

    public void setNewName(String newName) {
        this.newName = newName;
    }

    public String getNewName() {
        return newName;
    }

    public int getPrimitive() {
        return primitive;
    }

    public void setPrimitive(int primitive) {
        this.primitive = primitive;
    }

    public Collection<XdStarAutoSave> getStars() {
        return stars;
    }

    public void setStars(Collection<XdStarAutoSave> stars) {
        this.stars = stars;
    }

    public void addStar(XdStarAutoSave star) {
        if (stars == null) {
            stars = new ArrayList<>();
        }
        stars.add(star);
    }

    public Collection<XdPlanetAutoSave> getPlanets() {
        return planets;
    }

    public void setPlanets(Collection<XdPlanetAutoSave> planets) {
        this.planets = planets;
    }

    public void addPlanet(XdPlanetAutoSave planet) {
        if (planets == null) {
            planets = new ArrayList<>();
        }
        planets.add(planet);
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder("XdStarSystem { \r\n");
        sb.append(" idgeneration: ").append(id).append("\r\n");

        if (stars != null)
            for (final XdStarAutoSave star : stars) {
                sb.append(", ").append(star.toString()).append("\r\n");
            }
        if (planets != null)
            for (final XdPlanetAutoSave planet : planets) {
                sb.append(", ").append(planet.toString()).append("\r\n");
            }
        sb.append(" }");
        return sb.toString();
    }

}
