package io.github.xdstorage.performance.entities;

import io.github.xdstorage.XdStoragePolicy;
import io.github.xdstorage.annotations.*;
import io.github.xdstorage.idgeneration.MyStringIdGenerator;
import io.github.xdstorage.idgeneration.XdStorageIdGeneratorType;
import io.github.xdstorage.index.XdStorageIndexType;

import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedList;

@XdStorageObjectPolicy(policy = XdStoragePolicy.StoreAsClassObjects)
@XdStorageObjectIdIndexType(indexType = XdStorageIndexType.BTree, t = 10)
public class XdGalaxyAutoSave {

    @XdStorageObjectId
    @XdStorageObjectFieldProperties(
            idGeneratorType = XdStorageIdGeneratorType.CUSTOM_GENERATOR,
            idGeneratorClass = MyStringIdGenerator.class)
    private String id;

    private XdBlackHoleAutoSave hole;

    @XdStorageAutoSave
    private Collection<XdStarSystemAutoSave> systems;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public XdBlackHoleAutoSave getHole() {
        return hole;
    }

    public void setHole(XdBlackHoleAutoSave hole) {
        this.hole = hole;
    }

    public Collection<XdStarSystemAutoSave> getSystems() {
        return systems;
    }

    public void setSystems(Collection<XdStarSystemAutoSave> systems) {
        this.systems = systems;
    }

    public void addSystem(final XdStarSystemAutoSave system) {
        if (systems == null) {
            systems = new LinkedList<>();
        }
        systems.add(system);
    }

    public XdStarSystemAutoSave getSystem(int index) {
        final Iterator<XdStarSystemAutoSave> it = systems.iterator();
        for (int i = 0; it.hasNext(); ++i) {
            final XdStarSystemAutoSave ssys = it.next();
            if (i == index)
                return ssys;
        }
        return null;
    }

    public XdStarSystemAutoSave removeSystem(int index) {
        final Iterator<XdStarSystemAutoSave> it = systems.iterator();
        for (int i = 0; it.hasNext(); ++i) {
            final XdStarSystemAutoSave ssys = it.next();
            if (i == index) {
                it.remove();
                return ssys;
            }
        }
        return null;
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder("XdGalaxy { \r\n");
        sb.append(" idgeneration: ").append(id).append("\r\n");

        if(systems != null)
            for (final XdStarSystemAutoSave system : systems) {
                sb.append(", ").append(system.toString()).append("\r\n");
            }
        sb.append(" }");
        return sb.toString();
    }
}
