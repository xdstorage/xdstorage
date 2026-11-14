package io.github.xdstorage.tools;

import io.github.xdstorage.IXdFileStorage;
import io.github.xdstorage.XdStorageConfig;
import io.github.xdstorage.XdStorageProvider;

/**
 * W-4c (docs/tasks/2.1.0/48): the {@code compact} CLI command — the operator's manual
 * "make this folder clean" command: open, checkpoint-all, close.
 * <p>
 * Usage: {@code java -cp xdstorage.jar io.github.xdstorage.tools.Compact <folder> [name]}
 * <p>
 * The {@code compact} command opens the storage, force-checkpoints every journal
 * (full committed-state rewrite + journal reset), then closes. After it the folder's
 * base files are authoritative and all journals are empty — a power loss between this
 * call and the next open loses nothing.
 */
public class Compact {

    private Compact() {
        // utility class
    }

    public static void main(String[] args) {
        if (args.length < 1 || args.length > 2) {
            System.err.println("Usage: java -cp xdstorage.jar io.github.xdstorage.tools.Compact <folder> [name]");
            System.err.println("  folder  — the storage folder to compact");
            System.err.println("  name    — storage name (default: \"compact\")");
            System.exit(1);
        }

        final String folder = args[0];
        final String name = args.length == 2 ? args[1] : "compact";

        try {
            final IXdFileStorage storage = XdStorageProvider.newOrGetFileStorage(
                    name, folder, XdStorageConfig.defaults());
            try {
                storage.checkpoint();
                System.out.println("compact: checkpoint done for '" + name + "' in " + folder);
            } finally {
                storage.shutdown();
            }
        } catch (Exception e) {
            System.err.println("compact: failed for folder '" + folder + "': " + e.getMessage());
            System.exit(2);
        }
    }
}
