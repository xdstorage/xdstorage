package io.github.xdstorage.entities;

import io.github.xdstorage.XdStoragePolicy;
import io.github.xdstorage.annotations.*;

import java.util.ArrayList;
import java.util.Collection;

@XdStorageObjectPolicy(policy = XdStoragePolicy.StoreAsClassObjects)
@XdStorageObjectSearchIndex(indexName = "star_system_idx", indexFieldNames = {"newName"}, t = 50,
        childrenIndexes = {
                @XdStorageObjectChildSearchIndex(childFieldName = "stars", childClassIndexName = "test_star_index"),
        })
public class XdStarSystem {

    @XdStorageObjectId
    private String id;

    private int primitive = 0;

    private String newName;

    private Collection<XdStar> stars;

    @XdStorageLoadByGetMethod
    private Collection<XdPlanet> planets;

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

    public Collection<XdStar> getStars() {
        return stars;
    }

    public void setStars(Collection<XdStar> stars) {
        this.stars = stars;
    }

    public void addStar(XdStar star) {
        if (stars == null) {
            stars = new ArrayList<>();
        }
        stars.add(star);
    }

    public Collection<XdPlanet> getPlanets() {
        return planets;
    }

    public void setPlanets(Collection<XdPlanet> planets) {
        this.planets = planets;
    }

    public void addPlanet(XdPlanet planet) {
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
            for (final XdStar star : stars) {
                sb.append(", ").append(star.toString()).append("\r\n");
            }
        if (planets != null)
            for (final XdPlanet planet : planets) {
                sb.append(", ").append(planet.toString()).append("\r\n");
            }
        sb.append(" }");
        return sb.toString();
    }

}
