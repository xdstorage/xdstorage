package tools;

import io.github.xdstorage.IXdFileStorage;
import io.github.xdstorage.IXdStorage;
import io.github.xdstorage.XdStorageProvider;
import io.github.xdstorage.transaction.IXdStorageTransaction;

import java.util.ArrayList;
import java.util.List;

/**
 * Task 51 (LAYOUTMIG): generates the golden 2.0.0 storage folder for the
 * 2.0.0-to-2.1.0 layout-migration tests. Runs against the RELEASED 2.0.0 artifact
 * (this generator's pom is pinned to it).
 *
 * <p>The dataset is deterministic; Cyrillic values pin the charset behavior; the
 * close is DIRTY (System.exit without shutdown — the commit journals keep their
 * un-checkpointed tails, the real-world crash-ish state the migration must survive).
 * The committed fixture excludes .xdstorage.lock (recreated at open).
 *
 * <p>Usage: mvn -q package exec:java -Dexec.mainClass=tools.FixtureGenerator
 *           -Dexec.args="TARGET_FOLDER"
 */
public final class FixtureGenerator {

    private static final int PARENTS = 10;
    private static final int CHILDREN_PER_PARENT = 3;
    private static final int HASH_ENTITIES = 10;

    private FixtureGenerator() {
    }

    public static void main(final String[] args) throws Exception {
        final String folder = args.length > 0 ? args[0] : "golden-2000";
        System.out.println("[gen200] generating the 2.0.0 golden fixture at " + folder
                + " (cwd=" + new java.io.File(".").getAbsolutePath() + ")"
                + " — RELATIVE path on purpose: the id-index payload stores the fragment"
                + " paths verbatim, so a relative folder keeps the committed fixture"
                + " relocatable (open it from any cwd as ./<fixture-root>).");

        final IXdStorage storage = XdStorageProvider.newOrGetFileStorage("golden-2000", folder, 64);

        // 1. the seed wave: parents with children, hash entities, single objects
        for (int i = 0; i < PARENTS; i++) {
            final IXdStorageTransaction tx = storage.beginTransaction();
            final Gal200Parent parent = new Gal200Parent();
            parent.setId("gal-" + i);
            parent.setTitle("Система-альфа-" + i);
            for (int c = 0; c < CHILDREN_PER_PARENT; c++) {
                final Gal200Child child = new Gal200Child();
                child.setId("gal-" + i + "-c" + c);
                child.setLabel("Планета-" + i + "-" + c);
                storage.save(child, tx);
                parent.addChild(child);
            }
            storage.save(parent, tx);
            tx.commit();

            final IXdStorageTransaction hashTx = storage.beginTransaction();
            final Gal200Hash hash = new Gal200Hash();
            hash.setId("hash-" + i);
            hash.setValue("значение-" + i);
            storage.save(hash, hashTx);
            hashTx.commit();
        }
        for (int i = 0; i < 2; i++) {
            final IXdStorageTransaction tx = storage.beginTransaction();
            final Gal200Single single = new Gal200Single();
            single.setId("single-" + i);
            single.addTag("тег-раз-" + i);
            single.addTag("тег-два-" + i);
            storage.save(single, tx);
            tx.commit();
        }
        System.out.println("[gen200] seed wave committed");

        // 2. the journal tail: one more commit AFTER everything — the dirty exit keeps
        //    the tail in the commit journals (no shutdown checkpoint)
        final List<String> tailIds = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            final IXdStorageTransaction tx = storage.beginTransaction();
            final Gal200Hash tail = new Gal200Hash();
            tail.setId("hash-tail-" + i);
            tail.setValue("хвост-" + i);
            storage.save(tail, tx);
            tx.commit();
            tailIds.add(tail.getId());
        }
        System.out.println("[gen200] journal tail committed: " + tailIds);

        // 3. THE DIRTY CLOSE: no shutdown() — the journals keep the tail, exactly the
        //    real-world state of a 2.0.0 process killed by an operator/OOM
        System.out.println("[gen200] dirty exit (no shutdown checkpoint)");
        System.exit(0);
    }
}
