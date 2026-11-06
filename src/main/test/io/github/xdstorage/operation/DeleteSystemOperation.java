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

public class DeleteSystemOperation implements Runnable {

    private static final Logger     log = LogManager.getLogger(DeleteSystemOperation.class);

    private final        IXdStorage storage;

    public DeleteSystemOperation(final IXdStorage storage) {
        this.storage = storage;
    }

    final Random rand = new Random();

    @Override
    public void run() {
        System.out.println("DeleteSystemOperation Started");
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

                            if (galaxy.getSystems() != null && galaxy.getSystems().size() > 0) {
                                final int systemIndex = Math.abs(rand.nextInt()) % galaxy.getSystems().size();
                                final XdStarSystem system = galaxy.removeSystem(systemIndex);

                                storage.load(system);

                                storage.delete(system.getPlanets());
                                storage.delete(system);
                                storage.update(galaxy);
                            }
                        }

                        break;
                    }
                }
            }

            tx.commit();
            System.out.println("DeleteSystemOperation Commited");
        } catch (final Throwable e) {
            log.info("error", e);
            tx.rollback();
            System.out.println("DeleteSystemOperation Rolledback");
        }
    }

}
