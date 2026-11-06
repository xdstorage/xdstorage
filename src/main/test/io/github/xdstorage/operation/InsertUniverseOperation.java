package io.github.xdstorage.operation;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import io.github.xdstorage.IXdStorage;
import io.github.xdstorage.entities.*;
import io.github.xdstorage.exceptions.XdStorageConnectionException;
import io.github.xdstorage.exceptions.XdStorageException;
import io.github.xdstorage.transaction.IXdStorageTransaction;

import java.util.*;

public class InsertUniverseOperation implements Runnable {

    private static final Logger log = LogManager.getLogger(InsertUniverseOperation.class);

    private final IXdStorage storage;

    public InsertUniverseOperation(final IXdStorage storage) {
        this.storage = storage;
    }

    @Override
    public void run() {
        final XdUniverse universe = generateBigUniverse(2, 3, 4);
        System.out.println("InsertUniverseOperation Started");
        final IXdStorageTransaction tx = storage.beginTransaction();
        try {
            for (final XdGalaxy galaxy : universe.getGalaxies()) {
                if (galaxy.getSystems().size() > 0) {
                    Collection<XdStarSystem> systems = galaxy.getSystems();
                    for (final XdStarSystem system : systems) {
                        storage.save(system.getPlanets());
                    }
                    storage.save(systems);
                }
            }
            storage.save(universe.getGalaxies());
            storage.save(universe);

            tx.commit();
            System.out.println("InsertUniverseOperation Commited");
        } catch (final Throwable e) {
            log.info("error", e);
            tx.rollback();
            System.out.println("InsertUniverseOperation Rolledback");
        }
    }

    private static XdUniverse generateBigUniverse(int countGalaxies, int countStarSystems, int countPlanets) {
        final XdUniverse universe = new XdUniverse();
        universe.setId(nextStringId());
        universe.setGalaxies(new ArrayList<XdGalaxy>());

        for (int i = 0; i < countGalaxies; ++i) {
            final XdGalaxy galaxy = new XdGalaxy();
            galaxy.setSystems(new LinkedList<XdStarSystem>());

            universe.addGalaxy(galaxy);

            final XdBlackHole hole = new XdBlackHole();
            hole.setId(nextStringId());

            galaxy.setHole(hole);

            for (int j = 0; j < countStarSystems; ++j) {
                final XdStarSystem system = new XdStarSystem();
                system.setId(nextStringId());
                system.setStars(new LinkedList<XdStar>());
                system.setPlanets(new HashSet<>());

                galaxy.addSystem(system);

                final XdStar star = new XdStar();
                star.setId(nextStringId());
                star.setName("Star_" + j);

                system.addStar(star);

                for (int k = 0; k < countPlanets; ++k) {
                    final XdPlanet planet = generatePlanet(k);
//                    planet.setId(nextStringId());

                    system.addPlanet(planet);
                }
            }
        }

        return universe;
    }

    private static XdPlanet generatePlanet(int k) {
        final XdPlanet planet = new XdPlanet();

        planet.setName("Planet_" + k);
        planet.setWaterPercent(k * 10);

        generateSatellites(planet, k);

        generateInternalObjects(planet, k);

        return planet;
    }

    private static void generateInternalObjects(XdPlanet planet, int k) {
        final List<XdInternalObject> internalObjects = new ArrayList<>();

        for(int i = 0; i < k; ++i) {
            final XdInternalObject internalObject = new XdInternalObject();

            internalObject.setName("InternalObject_" + i);
            internalObject.setValue(i);

            internalObjects.add(internalObject);
        }

        planet.setInternalObjects(internalObjects);
    }

    private static void generateSatellites(XdPlanet planet, int k) {
        planet.setSatellite(generateSatellite(k));

        planet.setArrSatellites(generateArrSatellites(k));
    }

    private static XdSatellite[] generateArrSatellites(int k) {
        final XdSatellite[] satellites = new XdSatellite[k];

        for (int i = 0; i < k; ++i) {
            final XdSatellite satellite = new XdSatellite();

            satellite.setName("ArrSAtellite_" + i);

            satellites[i] = satellite;
        }

        return satellites;
    }

    private static XdSatellite generateSatellite(int k) {
        final XdSatellite satellite = new XdSatellite();

        satellite.setName("Satellite_" + k);

        return satellite;
    }

    private static String nextStringId() {
        return UUID.randomUUID().toString();
    }
}
