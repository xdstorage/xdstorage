package io.github.xdstorage.operation;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import io.github.xdstorage.IXdStorage;
import io.github.xdstorage.entities.*;
import io.github.xdstorage.exceptions.XdStorageConnectionException;
import io.github.xdstorage.exceptions.XdStorageException;
import io.github.xdstorage.transaction.IXdStorageTransaction;

import java.util.*;

public class InsertGalaxyOperation implements Runnable {

    private static final Logger log = LogManager.getLogger(InsertGalaxyOperation.class);

    private final IXdStorage storage;

    public InsertGalaxyOperation(final IXdStorage storage) {
        this.storage = storage;
    }

    final Random rand = new Random();

    @Override
    public void run() {
        System.out.println("InsertGalaxyOperation Started");
        final IXdStorageTransaction tx = storage.beginTransaction();
        try {
            final Collection<XdUniverse> universes = storage.load(XdUniverse.class);
            if (universes.size() > 0) {
                final int universeIndex = Math.abs(rand.nextInt()) % (universes.size() == 0 ? 1 : universes.size());
                Iterator<XdUniverse> it = universes.iterator();
                for (int i = 0; it.hasNext(); ++i) {
                    final XdUniverse universe = it.next();
                    if (i == universeIndex) {
                        final XdGalaxy galaxy = generateGalaxy();
                        universe.addGalaxy(galaxy);

//                        if (galaxy.getObject() != null)
//                            storage.save(galaxy.getObject());
                        storage.save(galaxy);
                        storage.update(universe);
                        break;
                    }
                }
            }

            tx.commit();
            System.out.println("InsertGalaxyOperation Commited");
        } catch (final Throwable e) {
            log.info("error", e);
            tx.rollback();
            System.out.println("InsertGalaxyOperation Rolledback");
        }
    }

    private static XdGalaxy generateGalaxy() {
        final XdGalaxy galaxy = new XdGalaxy();
        galaxy.setSystems(new LinkedList<XdStarSystem>());

        final XdBlackHole hole = new XdBlackHole();
        hole.setId(nextStringId());

        galaxy.setHole(hole);

        return galaxy;
    }

    private static String nextStringId() {
        return UUID.randomUUID().toString();
    }
}
