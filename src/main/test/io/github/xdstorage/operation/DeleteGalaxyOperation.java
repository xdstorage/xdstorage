package io.github.xdstorage.operation;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import io.github.xdstorage.IXdStorage;
import io.github.xdstorage.entities.XdGalaxy;
import io.github.xdstorage.entities.XdStarSystem;
import io.github.xdstorage.entities.XdUniverse;
import io.github.xdstorage.exceptions.XdStorageConnectionException;
import io.github.xdstorage.exceptions.XdStorageException;
import io.github.xdstorage.transaction.IXdStorageTransaction;

import java.util.Collection;
import java.util.Iterator;
import java.util.Random;

public class DeleteGalaxyOperation implements Runnable {

    private static final Logger     log = LogManager.getLogger(DeleteGalaxyOperation.class);

    private final        IXdStorage storage;

    public DeleteGalaxyOperation(final IXdStorage storage) {
        this.storage = storage;
    }

    final Random rand = new Random();

    @Override
    public void run() {
        System.out.println("DeleteGalaxyOperation Started");
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
                            final XdGalaxy galaxy = universe.removeGalaxy(galaxyIndex);

                            storage.load(galaxy);
//                            final XdObject object = galaxy.getObject();
//                            if (object != null) {
//                                storage.loadPGConfiguration(object);
//                            }


//                            if (object != null) {
//                                storage.delete(object);
//                            }

                            Collection<XdStarSystem> systems = galaxy.getSystems();
                            if(systems != null) {
                                storage.load(systems);

                                for (XdStarSystem system : systems) {
                                    storage.delete(system.getPlanets());
                                }
                                storage.delete(systems);
                            }
                            storage.delete(galaxy);
                            storage.update(universe);
                        }
                        break;
                    }
                }
            }

            tx.commit();
            System.out.println("DeleteGalaxyOperation Commited");
        } catch (final Throwable e) {
            log.info("error", e);
            tx.rollback();
            System.out.println("DeleteGalaxyOperation Rolledback");
        }
    }

}
