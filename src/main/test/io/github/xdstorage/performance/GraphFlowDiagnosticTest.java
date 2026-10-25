package io.github.xdstorage.performance;

import io.github.xdstorage.IXdStorage;
import io.github.xdstorage.XdStorageProvider;
import io.github.xdstorage.btree.XdStorageBTree;
import io.github.xdstorage.btree.XdStorageBTreeId;
import io.github.xdstorage.entities.XdGalaxy;
import io.github.xdstorage.entities.XdUniverse;
import io.github.xdstorage.transaction.IXdStorageTransaction;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.Collection;
import java.util.UUID;

/**
 * Диагностика (T-3b, docs/tasks/2.1.0/07): save(universe)+save(galaxies) →
 * load(XdGalaxy, id) — где теряется ключ id_index. Запускается вручную
 * ({@code mvn test -Dtest=GraphFlowDiagnosticTest}) при расследовании регрессий
 * маршрутизации индекса: тест печатает каждый шаг потока графа (вставка, индексация,
 * чтение), поэтому в обычных прогонах он просто проходит и ничего не ломает.
 */
public class GraphFlowDiagnosticTest {

    @Test
    public void diagnoseGraphFlow() throws Exception {
        final File dir = new File("./target/graph_diag_db");
        deleteDir(dir);
        final IXdStorage storage = XdStorageProvider.newOrGetFileStorage("graph_diag", "./target/graph_diag_db", 250);
        try {
            final IXdStorageTransaction tx = storage.beginTransaction();
            final XdUniverse universe = new XdUniverse();
            universe.setId(UUID.randomUUID().toString());
            final XdGalaxy galaxy = new XdGalaxy();
            galaxy.setId(UUID.randomUUID().toString());
            universe.addGalaxy(galaxy);
            storage.save(universe, tx);
            storage.save(universe.getGalaxies(), tx);
            tx.commit();

            // Диагностика id_index
            final IXdStorageTransaction dtx = storage.beginTransaction(5000L);
            final XdStorageBTreeId treeId = new XdStorageBTreeId(XdGalaxy.class, "id_index");
            final XdStorageBTree tree = storage.load(XdStorageBTree.class, treeId, dtx);
            System.out.println("### tree=" + (tree != null ? "loaded" : "NULL")
                + " t=" + (tree != null ? tree.getT() : "-")
                + " multiple=" + (tree != null ? tree.isMultiple() : "-")
                + " root=" + (tree != null && tree.getRoot() != null ? tree.getRoot().getId() : "null")
                + " rootKeys=" + (tree != null && tree.getRoot() != null ? tree.getRoot().getKeys() : "-")
                + " firstLeaf=" + (tree != null && tree.getFirstLeaf() != null ? tree.getFirstLeaf().getId() : "null"));
            if (tree != null) {
                System.out.println("### tree.find(galaxyId) = " + tree.find(galaxy.getId(), storage, dtx));
            }
            final Object direct = storage.load(XdGalaxy.class, galaxy.getId(), dtx);
            System.out.println("### load(XdGalaxy, id) = " + (direct != null ? "FOUND" : "NULL"));
            dtx.commit();
        } finally {
            storage.shutdown();
            deleteDir(dir);
        }
    }

    private static void deleteDir(final File file) {
        final File[] contents = file.listFiles();
        if (contents != null) {
            for (final File f : contents) deleteDir(f);
        }
        file.delete();
    }
}
