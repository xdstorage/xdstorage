package io.github.xdstorage.operation;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import io.github.xdstorage.IXdStorage;
import io.github.xdstorage.entities.*;
import io.github.xdstorage.exceptions.XdStorageConnectionException;
import io.github.xdstorage.exceptions.XdStorageException;
import io.github.xdstorage.transaction.IXdStorageTransaction;

import java.util.*;

public class InsertSystemOperation implements Runnable {

    private static final Logger log = LogManager.getLogger(InsertSystemOperation.class);

    private final IXdStorage storage;

    public InsertSystemOperation(final IXdStorage storage) {
        this.storage = storage;
    }

    final Random rand = new Random();

    @Override
    public void run() {
        System.out.println("InsertSystemOperation Started");
        final IXdStorageTransaction tx = storage.beginTransaction();
        try {
            final Collection<XdUniverse> universes = storage.load(XdUniverse.class);
            if (universes.size() > 0) {
                final int universeIndex = Math.abs(rand.nextInt()) % (universes.size() == 0 ? 1 : universes.size());
                Iterator<XdUniverse> it = universes.iterator();
                for (int i = 0; it.hasNext(); ++i) {
                    final XdUniverse universe = it.next();
                    if (i == universeIndex) {
                        if (universe.getGalaxies().size() > 0) {
                            final int galaxyIndex = Math.abs(rand.nextInt()) % universe.getGalaxies().size();
                            final XdGalaxy galaxy = universe.getGalaxy(galaxyIndex);

                            storage.load(galaxy);

                            final XdStarSystem system = generateSystem(Math.abs(rand.nextInt()) % 10);
                            galaxy.addSystem(system);

                            storage.save(system.getPlanets());
                            storage.save(system);
                            storage.update(galaxy);
                        }
                        break;
                    }
                }
            }

            tx.commit();
            System.out.println("InsertSystemOperation Commited");
        } catch (final Throwable e) {
            log.info("error", e);
            tx.rollback();
            System.out.println("InsertSystemOperation Rolledback");
        }
    }

    private static XdStarSystem generateSystem(int countPlanets) {
        final XdStarSystem system = new XdStarSystem();
        system.setId(nextStringId());
        system.setStars(new LinkedList<XdStar>());
        system.setPlanets(new HashSet<>());

        final XdStar star = new XdStar();
        star.setId(nextStringId());
        star.setName("Star_" + countPlanets % 5);

        system.addStar(star);

        for (int k = 0; k < countPlanets; ++k) {
            final XdPlanet planet = generatePlanet(k);
//            planet.setId(nextStringId());

            system.addPlanet(planet);
        }

        return system;
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
